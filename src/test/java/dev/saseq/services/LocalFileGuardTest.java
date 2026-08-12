package dev.saseq.services;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage for the shared guard.
 *
 * <p>Its behaviour is also exercised through {@code send_file} and
 * {@code set_guild_scheduled_event_image}, which is what proves each of those wires it in. These
 * tests exist because the guard is now shared: a change here breaks every caller at once, and
 * finding that out through two service tests says less about what actually broke.
 */
class LocalFileGuardTest {

    @Test
    void aPathInsideTheRootResolvesToItsRealLocation(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Path file = Files.writeString(root.resolve("poster.png"), "x");

        assertThat(LocalFileGuard.resolveWithinRoot(file.toString(), root.toRealPath(), "filePath", "upload"))
                .isEqualTo(file.toRealPath());
    }

    @Test
    void aSiblingSharingTheRootsNamePrefixIsNotInsideIt(@TempDir Path dir) throws IOException {
        // The reason the check is Path.startsWith and not String.startsWith: "/x/rootX" has
        // "/x/root" as a string prefix but is a different directory.
        Path root = Files.createDirectory(dir.resolve("root")).toRealPath();
        Path sibling = Files.createDirectory(dir.resolve("rootX"));
        Path outside = Files.writeString(sibling.resolve("secret"), "x");

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(outside.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
    }

    @Test
    void aSymlinkOutOfTheRootIsRejectedOnItsResolvedTarget(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root")).toRealPath();
        Path secret = Files.writeString(dir.resolve("secret"), "token");
        Path link = root.resolve("innocent.png");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation not permitted on this host");
        }

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(link.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
    }

    @Test
    void theRootItselfIsNotAFileInsideIt(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root")).toRealPath();

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(root.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDirectoryInsideTheRootIsNotARegularFile(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root")).toRealPath();
        Path sub = Files.createDirectory(root.resolve("sub"));

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(sub.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void aFilesystemRootWouldConfineNothing(@TempDir Path dir) {
        assertThatThrownBy(() -> LocalFileGuard.resolveRoot(dir.getRoot().toString(), "VAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a filesystem root");
    }

    @Test
    void aRootThatIsNotADirectoryIsRejected(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("notadir"), "x");

        assertThatThrownBy(() -> LocalFileGuard.resolveRoot(file.toString(), "VAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a directory");
        assertThatThrownBy(() -> LocalFileGuard.resolveRoot(dir.resolve("nope").toString(), "VAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void readingStopsOneBytePastTheLimitRatherThanTrustingTheFileSize(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("f"), new byte[64]);

        assertThat(LocalFileGuard.readBounded(file, 64, "file")).hasSize(64);
        assertThatThrownBy(() -> LocalFileGuard.readBounded(file, 63, "cover image"))
                .isInstanceOf(LocalFileGuard.TooLargeException.class)
                // Capitalized at the start of its own sentence, lowercase mid-sentence in the read
                // failure. One noun cannot be spelled correctly for both without this.
                .hasMessageContaining("Cover image exceeds");
    }

    @Test
    void anUnreadableFileFailsWithTheNounInMidSentenceForm(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("gone");

        assertThatThrownBy(() -> LocalFileGuard.readBounded(missing, 10, "cover image"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(LocalFileGuard.TooLargeException.class)
                .hasMessageContaining("Failed to read cover image");
    }

    @Test
    void aMissingPathNamesTheParameterItCameFrom(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root")).toRealPath();

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(
                root.resolve("nope").toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found at filePath");
    }

    @Test
    void theBytesReadAreTheFilesOwn(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("f"), "hello");

        assertThat(new String(LocalFileGuard.readBounded(file, 1024, "file"), StandardCharsets.UTF_8))
                .isEqualTo("hello");
    }
}
