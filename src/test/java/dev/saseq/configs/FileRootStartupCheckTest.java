package dev.saseq.configs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The warning exists for an audience the per-call refusal cannot reach, so what matters is that
 * it fires exactly when the configuration is the dangerous one — and stays quiet otherwise, since
 * a warning that cries wolf on ordinary deployments is one operators learn to skip.
 */
class FileRootStartupCheckTest {

    private FileRootStartupCheck check;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        check = new FileRootStartupCheck();
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FileRootStartupCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String warnings() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + b);
    }

    @Test
    void overlappingRootsWarn(@TempDir Path dir) throws IOException {
        Path shared = Files.createDirectory(dir.resolve("shared"));
        check.fileRoot = shared.toString();
        check.downloadRoot = shared.toString();

        check.run(null);

        assertThat(warnings())
                .contains("DISCORD_MCP_FILE_ROOT")
                .contains("overlap")
                .contains("send_file does not");
    }

    @Test
    void nestingCountsAsOverlap(@TempDir Path dir) throws IOException {
        Path uploads = Files.createDirectory(dir.resolve("uploads"));
        check.fileRoot = uploads.toString();
        check.downloadRoot = Files.createDirectory(uploads.resolve("downloads")).toString();

        check.run(null);

        assertThat(warnings()).contains("overlap");
    }

    @Test
    void separateRootsAreSilent(@TempDir Path dir) throws IOException {
        check.fileRoot = Files.createDirectory(dir.resolve("uploads")).toString();
        check.downloadRoot = Files.createDirectory(dir.resolve("downloads")).toString();

        check.run(null);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void anUnsetRootIsSilent(@TempDir Path dir) throws IOException {
        // Unset is the common case, and "" resolves to the process working directory — which is
        // how this check would otherwise warn about every deployment whose uploads sit under it.
        check.fileRoot = Files.createDirectory(dir.resolve("uploads")).toString();
        check.downloadRoot = "";

        check.run(null);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void anUnusableUploadRootIsReportedEvenWithDownloadsOff(@TempDir Path dir) {
        // Whether the upload root exists has nothing to do with whether downloads are configured.
        // Gating both checks on both variables left a deployment that enables uploads and leaves
        // downloads off — which the README presents as supported — with no warning at all, back
        // to the tool-refusal-only signal this class exists to escape.
        check.fileRoot = dir.resolve("never-created").toString();
        check.downloadRoot = "";

        check.run(null);

        assertThat(warnings())
                .contains("DISCORD_MCP_FILE_ROOT is set but unusable")
                .doesNotContain("overlap");
    }

    @Test
    void aRootThatIsSetButUnusableIsWorthSayingOnItsOwn(@TempDir Path dir) throws IOException {
        // The more common misconfiguration of the two — the compose bind mount left commented
        // produces exactly it — and it has the property this class exists for: the only other
        // signal is a tool refusal, which the model reads and the operator never does.
        check.fileRoot = Files.createDirectory(dir.resolve("uploads")).toString();
        check.downloadRoot = dir.resolve("nope").toString();

        check.run(null);

        assertThat(warnings())
                .contains("DISCORD_MCP_DOWNLOAD_ROOT is set but unusable")
                // Half a comparison establishes nothing about overlap, so nothing is claimed.
                .doesNotContain("overlap");
    }
}
