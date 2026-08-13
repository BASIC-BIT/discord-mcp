package dev.saseq.configs;

import dev.saseq.services.LocalFileGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Warns once, at startup, when the upload root and the download root overlap.
 *
 * <p>{@code set_guild_scheduled_event_image} refuses a local {@code filePath} in this
 * configuration, but that refusal is a tool result: it goes to the model, and the person who can
 * fix it — the operator who set the two variables — never sees it. This is the same finding
 * addressed to the audience that can act on it.
 *
 * <p>{@code DISCORD_MCP_ALLOW_SHARED_ROOT=true} removes that refusal and this line is then the
 * only thing left saying the roots are chained, so the overlap still warns — with different
 * wording, because the fix it should suggest is different once the layout is deliberate.
 *
 * <p>A warning and not a failure. {@code send_file} and {@code download_attachment} work on a
 * shared root and always have, so refusing to start would break deployments that chose this
 * deliberately over a capability they already had.
 *
 * <p>How far the audience argument reaches depends on the profile. Under {@code http} — what
 * Compose runs — this lands on the console and so in {@code docker logs}. Under the stdio
 * profile it goes to the log file only, which a container does not expose, and there it is about
 * as reachable as the tool refusal it was written to replace.
 */
@Component
public class FileRootStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileRootStartupCheck.class);

    @Value("${DISCORD_MCP_FILE_ROOT:}")
    String fileRoot;

    @Value("${DISCORD_MCP_DOWNLOAD_ROOT:}")
    String downloadRoot;

    /**
     * Opt-in for the shared-root layout, read here only to say which warning is true.
     *
     * <p>Parsed exactly as {@code ScheduledEventService} parses it: the string {@code "true"},
     * case-insensitively, and nothing else. A looser reading here would describe a configuration
     * the server is not running — the worse failure of the two, because this is the line an
     * operator trusts instead of reading the code.
     */
    @Value("${DISCORD_MCP_ALLOW_SHARED_ROOT:}")
    String allowSharedRoot;

    @Override
    public void run(ApplicationArguments args) {
        // Each root on its own first. Whether DISCORD_MCP_FILE_ROOT points at a directory that
        // exists has nothing to do with whether downloads are configured, and gating both checks
        // on both variables meant a deployment that enables uploads and leaves downloads off —
        // a shape the README presents as supported — got no warning about a missing upload root
        // at all, which is the case this class was written for.
        LocalFileGuard.Root uploads = isSet(fileRoot)
                ? resolved(fileRoot, "DISCORD_MCP_FILE_ROOT") : null;
        LocalFileGuard.Root downloads = isSet(downloadRoot)
                ? resolved(downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT") : null;
        if (uploads != null) {
            // Who reads this root, said on every boot. A deployment that set the variable for
            // send_file acquires a second reader of it the moment the jar is updated, with no
            // config change and nothing to notice — and the README, which argues that case at
            // length, is the document nobody opens on a version bump. Phrased as what is true
            // now rather than as what changed, since a fresh install did not change anything.
            //
            // info, not warn: this is the configuration working as documented. The warnings
            // below are for configurations that are not.
            log.info("DISCORD_MCP_FILE_ROOT ({}) is readable by send_file and by"
                            + " set_guild_scheduled_event_image. A cover is gated to PNG/JPEG"
                            + " signatures, and it lands on a permanent unauthenticated CDN URL"
                            + " rather than in a permission-gated channel. Refuse the tool by name"
                            + " if that is not wanted.",
                    uploads.path());
        }
        if (downloads != null) {
            // The resolved path, because nothing else prints it any more. LocalFileGuard stopped
            // echoing a configured value into its refusals — those reach the model and often a
            // channel — which leaves an operator who typo'd this variable with a message naming
            // only the variable. This log is not caller-reachable, so it is the right place for
            // the value: it says what the server actually resolved, which is the thing a typo
            // makes surprising.
            log.info("DISCORD_MCP_DOWNLOAD_ROOT resolved to {}, where download_attachment writes.",
                    downloads.path());
        }
        if (uploads == null || downloads == null) {
            // Unset, or unusable and already reported. Either way there is nothing to compare
            // against, and half a comparison establishes nothing about overlap.
            return;
        }
        if (!LocalFileGuard.overlaps(uploads, downloads)) {
            return;
        }
        if (allowSharedRoot != null && allowSharedRoot.trim().equalsIgnoreCase("true")) {
            // Still a warning, and still every boot. The opt-in says the operator meant the
            // layout, not that the consequence stopped applying — and this is the only line that
            // will say so on the day somebody inherits the deployment. Softer than the refusal it
            // replaced because there is nothing left to fix by hand.
            log.warn("DISCORD_MCP_FILE_ROOT ({}) and DISCORD_MCP_DOWNLOAD_ROOT ({}) overlap, and"
                            + " DISCORD_MCP_ALLOW_SHARED_ROOT=true allows it. Covers can be set"
                            + " from files download_attachment wrote, so a caller that can reach"
                            + " that tool chooses what gets pinned to a permanent unauthenticated"
                            + " CDN URL. Unset the opt-in to go back to refusing local cover paths"
                            + " in this configuration.",
                    uploads.path(), downloads.path());
            return;
        }
        log.warn("DISCORD_MCP_FILE_ROOT ({}) and DISCORD_MCP_DOWNLOAD_ROOT ({}) overlap."
                        + " Files written by download_attachment are readable by the tools that"
                        + " read local paths, so their contents are chosen by whoever can call"
                        + " this server. set_guild_scheduled_event_image refuses local paths in"
                        + " this configuration; send_file does not. Point them at separate"
                        + " directories, or set DISCORD_MCP_ALLOW_SHARED_ROOT=true if this is"
                        + " deliberate.",
                uploads.path(), downloads.path());
    }

    private static boolean isSet(String configured) {
        return configured != null && !configured.isBlank();
    }

    /**
     * A configured root, or null with a warning naming why it did not resolve.
     *
     * <p>Worth its own warning rather than a silent return. A root set to a directory that is not
     * there is the more common misconfiguration of the two — the compose bind mount left
     * commented produces exactly it — and it has the same property this class exists for: the
     * only signal is a tool refusal, which reaches the model and not the operator.
     */
    private LocalFileGuard.Root resolved(String configured, String variableName) {
        try {
            return LocalFileGuard.resolveRoot(configured, variableName);
        } catch (RuntimeException unresolvable) {
            // The configured value, not just the exception's text. LocalFileGuard keeps it out of
            // the refusal, because those reach the model, so getMessage() names the variable and
            // nothing else — and a line built only from that names the variable twice and the bad
            // value never. The typo case is the one this whole log exists for.
            log.warn("{} is set to \"{}\", which is unusable, so the tools that need it will"
                            + " refuse: {}",
                    variableName, configured, unresolvable.getMessage());
            return null;
        }
    }
}
