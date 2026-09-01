package dev.saseq.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Shared startup checks for credential, audit, and other operator-controlled sensitive files. */
public final class SensitiveFileGuard {

    private SensitiveFileGuard() {
    }

    /** Require an existing file to be regular, non-symlink, and single-link where supported. */
    public static void requireExclusiveRegularFile(Path file, boolean allowMissing) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (allowMissing) {
                return;
            }
            throw new IOException("required sensitive file is missing");
        }
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("must resolve to a regular file and must not be a symbolic link");
        }
        if (file.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            Object value = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof Number linkCount) || linkCount.longValue() != 1L) {
                throw new IOException("must not have additional hard links");
            }
        }
    }

    /**
     * Reject lexical or resolved containment in a caller-readable or caller-writable root.
     * Missing path components are resolved from their nearest existing ancestor, so this check can
     * run before creating a parent directory or the sensitive file itself.
     */
    public static void requireOutsideRoot(Path sensitiveFile, String configuredRoot,
                                          String sensitiveVariable, String rootVariable) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return;
        }
        Path lexicalRoot;
        try {
            lexicalRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        } catch (InvalidPathException unusableRoot) {
            return;
        }
        Path normalizedSensitive = sensitiveFile.toAbsolutePath().normalize();
        if (normalizedSensitive.startsWith(lexicalRoot)) {
            throw new IllegalArgumentException(sensitiveVariable + " must be outside " + rootVariable);
        }

        LocalFileGuard.Root resolvedRoot;
        try {
            resolvedRoot = LocalFileGuard.resolveRoot(configuredRoot, rootVariable);
        } catch (IllegalArgumentException unusableRoot) {
            return;
        }
        try {
            if (resolveExistingAncestor(normalizedSensitive).startsWith(resolvedRoot.path())) {
                throw new IllegalArgumentException(
                        sensitiveVariable + " must be outside " + rootVariable);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    sensitiveVariable + " cannot be resolved before startup", error);
        }
    }

    private static Path resolveExistingAncestor(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return path.toRealPath();
        }
        Deque<Path> missingNames = new ArrayDeque<>();
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            Path name = current.getFileName();
            if (name != null) {
                missingNames.addFirst(name);
            }
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("no existing path ancestor");
        }
        Path resolved = current.toRealPath();
        for (Path name : missingNames) {
            resolved = resolved.resolve(name);
        }
        return resolved.normalize();
    }
}
