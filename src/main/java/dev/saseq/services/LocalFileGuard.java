package dev.saseq.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Confine caller-supplied local file reads to an allowlisted root.
 *
 * <p>Shared code on purpose, and the counterpart to {@link RemoteFetchGuard}. Every tool here is
 * reachable by a model, so a tool that opens a caller-supplied path will read whatever the process
 * can read. On a host that loads its bot token from the environment, that is one call from posting
 * a credential into a chat channel.
 *
 * <p>This lives in its own class because the repo has already paid for the alternative: the SSRF
 * guard existed as a private method inside one service, and {@code send_file} then shipped with its
 * own unguarded fetch. A private helper protects the file it lives in and nothing else. The second
 * caller — {@code set_guild_scheduled_event_image} — is what prompted the extraction.
 *
 * <h2>What this defends against, and what it does not</h2>
 *
 * <p>The threat is the <b>caller</b>: a model that has been talked into asking for
 * {@code /proc/self/environ}. Against that, resolving both sides and opening the resolved path is
 * sufficient, and it is the whole job.
 *
 * <p>It is <b>not</b> a defence against another process that can already write inside the root.
 * There is a window between {@link #resolveWithinRoot} returning and {@link #readBounded} opening,
 * and {@code NOFOLLOW_LINKS} only refuses a symlink in the final component — so a process able to
 * replace a checked ancestor directory with a symlink in that window could redirect the read.
 * Closing it properly needs a securely held directory handle, which the default filesystem
 * provider does not offer portably.
 *
 * <p>That gap is narrow because of what it presupposes. An attacker who can rename directories
 * inside the root can equally well place any file they like inside the root, and the tools here
 * would read that one without needing a race at all. The root is therefore load-bearing: it must
 * be a directory only the operator writes. The Compose deployment mounts it read-only for exactly
 * this reason. Widening it — pointing it at a directory some other tool writes into — is what
 * turns the race from theoretical into reachable, which is the deeper reason the README argues
 * against chaining it to the download root.
 */
public final class LocalFileGuard {

    private LocalFileGuard() {
    }

    /**
     * A file that was rejected for its size alone, so a caller can add advice specific to what it
     * was reading. Mirrors {@link RemoteFetchGuard.TooLargeException}: still an
     * {@link IllegalArgumentException}, so callers that do not care are unaffected.
     */
    public static class TooLargeException extends IllegalArgumentException {
        TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Resolve a configured root directory, rejecting the shapes that would confine nothing.
     *
     * @param configured   the raw configured value
     * @param variableName the environment variable it came from, for error messages
     * @return the fully resolved real path of the root
     */
    public static Path resolveRoot(String configured, String variableName) {
        Path root;
        try {
            root = Paths.get(configured).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    variableName + " does not exist or cannot be resolved: " + configured);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(variableName + " is not a directory: " + configured);
        }
        // A filesystem root has no name components. Accepting "/" would confine
        // nothing at all and silently re-open the whole vulnerability.
        if (root.getNameCount() == 0) {
            throw new IllegalArgumentException(variableName + " must not be a filesystem root");
        }
        return root;
    }

    /**
     * Confine one caller-supplied path to an already-resolved root.
     *
     * <p>The returned path is the one the caller must open. Opening the requested path instead
     * would defeat the check entirely: the whole point is that the two can differ.
     *
     * @param filePath  the caller-supplied path
     * @param allowed   the resolved root, from {@link #resolveRoot}
     * @param paramName the tool parameter the path came from, for error messages
     * @param rootName  what the root is for, naming the grant an operator would have to widen.
     *                  Both callers pass "upload" today, correctly: they read from the same
     *                  {@code DISCORD_MCP_FILE_ROOT}. It is a parameter rather than a constant so
     *                  that a caller reading from a different root cannot silently describe it as
     *                  the upload directory.
     * @return the fully resolved real path
     */
    public static Path resolveWithinRoot(String filePath, Path allowed, String paramName, String rootName) {
        Path real;
        try {
            // toRealPath, not normalize: normalize is purely lexical, so a symlink
            // inside the root pointing at /etc/shadow passes a prefix check on the
            // normalized path. Both sides must be resolved for the comparison to mean
            // anything, and the resolved path is what gets opened.
            real = Paths.get(filePath).toRealPath();
        } catch (IOException e) {
            // Same message as the outside-the-root case below, deliberately. Two distinguishable
            // answers would make this an existence oracle over the whole host: /etc/shadow
            // resolves and reports "outside the allowed directory", /etc/nope does not and
            // reports "not found", so any caller could map the filesystem one tool call at a
            // time. No content leaks either way, so the cost is reconnaissance rather than
            // exfiltration — but this is shared code now, and the distinction is worth less to a
            // legitimate operator than it is to someone probing.
            throw new IllegalArgumentException(refusal(paramName, rootName));
        }
        if (!real.startsWith(allowed) || real.equals(allowed)) {
            throw new IllegalArgumentException(refusal(paramName, rootName));
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(paramName + " is not a regular file: " + filePath);
        }
        return real;
    }

    /** One answer for "no such file" and "not inside the root", so the pair cannot be probed. */
    private static String refusal(String paramName, String rootName) {
        return paramName + " is not a readable file inside the allowed " + rootName + " directory";
    }

    /**
     * Read a resolved path, refusing anything over the limit.
     *
     * <p>Reads one byte past the limit rather than consulting the file's size: a size check
     * followed by a full read is a time-of-check/time-of-use gap, and {@code readAllBytes} on a
     * path the caller chose would exhaust the heap long before any check could reject it, on a
     * JVM that may be running with only a few hundred megabytes.
     *
     * @param real     a path already resolved by {@link #resolveWithinRoot}
     * @param maxBytes the largest body to accept
     * @param what     a lowercase noun for what is being read ("file", "cover image"). It appears
     *                 mid-sentence in one message and at the start of the other, so it is stored
     *                 lowercase and capitalized at the point of use rather than reading as
     *                 "Failed to read Cover image".
     */
    public static byte[] readBounded(Path real, int maxBytes, String what) {
        if (maxBytes < 0 || maxBytes == Integer.MAX_VALUE) {
            // maxBytes + 1 overflows to negative at MAX_VALUE, and readNBytes would throw
            // IllegalArgumentException from inside the try, where it would be reported as a read
            // failure of the file rather than a caller mistake. RemoteFetchGuard.readBounded
            // guards its own bound for the same reason; unreachable from either caller today.
            throw new IllegalArgumentException("maxBytes must be between 0 and Integer.MAX_VALUE - 1,"
                    + " was " + maxBytes);
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            // The file name, not just the noun and not the absolute path. The extraction dropped
            // send_file's "at filePath", and for a tool taking both a URL and a path the noun
            // alone does not identify what failed. The name does; the full path would put the
            // deployment's directory layout into a string that goes back to the model and often
            // onward into a channel.
            throw new IllegalArgumentException(
                    "Failed to read " + what + " " + real.getFileName() + ": " + e.getMessage());
        }
        if (bytes.length > maxBytes) {
            throw new TooLargeException(capitalize(what)
                    + " exceeds the " + FileSizes.format(maxBytes) + " limit.");
        }
        return bytes;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
