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
    void anUnsetOrUnresolvableRootIsSilent(@TempDir Path dir) throws IOException {
        Path uploads = Files.createDirectory(dir.resolve("uploads"));

        // Unset is the common case, and "" resolves to the process working directory — which is
        // how this check would otherwise warn about every deployment whose uploads sit under it.
        check.fileRoot = uploads.toString();
        check.downloadRoot = "";
        check.run(null);

        // Set but missing: whichever tool needs it will say so, with the parameter its caller
        // used. Half a comparison establishes nothing, so nothing is claimed from it.
        check.downloadRoot = dir.resolve("nope").toString();
        check.run(null);

        assertThat(appender.list).isEmpty();
    }
}
