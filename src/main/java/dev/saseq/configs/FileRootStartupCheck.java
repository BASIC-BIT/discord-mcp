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
 * <p>{@code set_guild_scheduled_event_image} already refuses a local {@code filePath} in this
 * configuration, but that refusal is a tool result: it goes to the model, and the person who can
 * fix it — the operator who set the two variables — never sees it. This is the same finding
 * addressed to the audience that can act on it.
 *
 * <p>A warning and not a failure. {@code send_file} and {@code download_attachment} work on a
 * shared root and always have, so refusing to start would break deployments that chose this
 * deliberately over a capability they already had.
 */
@Component
public class FileRootStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FileRootStartupCheck.class);

    @Value("${DISCORD_MCP_FILE_ROOT:}")
    String fileRoot;

    @Value("${DISCORD_MCP_DOWNLOAD_ROOT:}")
    String downloadRoot;

    @Override
    public void run(ApplicationArguments args) {
        if (fileRoot == null || fileRoot.isBlank() || downloadRoot == null || downloadRoot.isBlank()) {
            return;
        }
        LocalFileGuard.Root uploads = resolved(fileRoot, "DISCORD_MCP_FILE_ROOT");
        LocalFileGuard.Root downloads = resolved(downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT");
        if (uploads == null || downloads == null) {
            // Half a comparison establishes nothing about overlap, so nothing is claimed from it.
            // The failure itself was already reported by resolved().
            return;
        }
        if (LocalFileGuard.overlaps(uploads, downloads)) {
            log.warn("DISCORD_MCP_FILE_ROOT ({}) and DISCORD_MCP_DOWNLOAD_ROOT ({}) overlap."
                            + " Files written by download_attachment are readable by the tools that"
                            + " read local paths, so their contents are chosen by whoever can call"
                            + " this server. set_guild_scheduled_event_image refuses local paths in"
                            + " this configuration; send_file does not. Point them at separate"
                            + " directories unless this is deliberate.",
                    uploads.path(), downloads.path());
        }
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
            log.warn("{} is set but unusable, so the tools that need it will refuse: {}",
                    variableName, unresolvable.getMessage());
            return null;
        }
    }
}
