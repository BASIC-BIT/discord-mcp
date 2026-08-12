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
import static org.assertj.core.api.Assertions.catchThrowable;

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
        Path dirRoot = Files.createDirectory(dir.resolve("root"));
        Path file = Files.writeString(dirRoot.resolve("poster.png"), "x");
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(dirRoot.toString(), "VAR");

        assertThat(LocalFileGuard.resolveWithinRoot(file.toString(), root, "filePath", "upload"))
                .extracting(LocalFileGuard.ConfinedPath::path).isEqualTo(file.toRealPath());
    }

    @Test
    void aSiblingSharingTheRootsNamePrefixIsNotInsideIt(@TempDir Path dir) throws IOException {
        // The reason the check is Path.startsWith and not String.startsWith: "/x/rootX" has
        // "/x/root" as a string prefix but is a different directory.
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Path sibling = Files.createDirectory(dir.resolve("rootX"));
        Path outside = Files.writeString(sibling.resolve("secret"), "x");

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(outside.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a readable file inside the allowed upload directory");
    }

    @Test
    void aSymlinkOutOfTheRootIsRejectedOnItsResolvedTarget(@TempDir Path dir) throws IOException {
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Path secret = Files.writeString(dir.resolve("secret"), "token");
        Path link = root.path().resolve("innocent.png");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation not permitted on this host");
        }

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(link.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a readable file inside the allowed upload directory");
    }

    @Test
    void theRootItselfIsNotAFileInsideIt(@TempDir Path dir) throws IOException {
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(root.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDirectoryInsideTheRootIsNotARegularFile(@TempDir Path dir) throws IOException {
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Path sub = Files.createDirectory(root.path().resolve("sub"));

        assertThatThrownBy(() -> LocalFileGuard.resolveWithinRoot(sub.toString(), root, "filePath", "upload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a regular file");
    }

    @Test
    void neitherWrapperCanBeMintedOutsideItsFactory() {
        // The confinement is in the types, not in a javadoc line — but a type only holds if the
        // wrapper cannot be built around something unchecked. Asserting the parameter types alone
        // would not show that: `new ConfinedPath(Paths.get("/etc/shadow"))` type-checks perfectly.
        // The constructors are what has to hold.
        //
        // Both wrappers, because both sides matter. A bare Path root skips toRealPath,
        // is-a-directory and is-not-a-filesystem-root, and "/" is the fail-open one: every path on
        // the host starts with it, so the guard would confine nothing while carrying its name.
        assertThat(LocalFileGuard.ConfinedPath.class.getDeclaredConstructors())
                .allSatisfy(c -> assertThat(java.lang.reflect.Modifier.isPrivate(c.getModifiers()))
                        .as("ConfinedPath constructor must be private, or the wrapper guarantees nothing")
                        .isTrue());
        assertThat(LocalFileGuard.Root.class.getDeclaredConstructors())
                .allSatisfy(c -> assertThat(java.lang.reflect.Modifier.isPrivate(c.getModifiers()))
                        .as("Root constructor must be private, or resolveRoot's checks are optional")
                        .isTrue());

        assertThat(LocalFileGuard.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("readBounded"))
                .allSatisfy(m -> assertThat(m.getParameterTypes()[0])
                        .isEqualTo(LocalFileGuard.ConfinedPath.class));
        assertThat(LocalFileGuard.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("resolveWithinRoot"))
                .allSatisfy(m -> assertThat(m.getParameterTypes()[1])
                        .isEqualTo(LocalFileGuard.Root.class));
    }

    @Test
    void anUnsetRootIsNotTheWorkingDirectory() {
        // The shape that made this necessary: Paths.get("") is a relative empty path, so
        // toRealPath() resolves it against wherever the JVM was launched and hands back a valid
        // Root over the process's working directory. Nobody configured that directory, and a
        // caller that trusted the result would confine reads to the deployment's install dir.
        // Spring's `${VAR:}` default makes "" the ordinary shape of an unset variable.
        assertThatThrownBy(() -> LocalFileGuard.resolveRoot("", "VAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VAR is not set");
        assertThatThrownBy(() -> LocalFileGuard.resolveRoot("   ", "VAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VAR is not set");
        assertThatThrownBy(() -> LocalFileGuard.resolveRoot(null, "VAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VAR is not set");
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
        // Through the factory, because there is no other way to make one — which is the point.
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Files.write(root.path().resolve("f"), new byte[64]);
        LocalFileGuard.ConfinedPath file = LocalFileGuard.resolveWithinRoot(
                root.path().resolve("f").toString(), root, "filePath", "upload");

        assertThat(LocalFileGuard.readBounded(file, 64, "file")).hasSize(64);
        assertThatThrownBy(() -> LocalFileGuard.readBounded(file, 63, "cover image"))
                .isInstanceOf(LocalFileGuard.TooLargeException.class)
                // Capitalized at the start of its own sentence, lowercase mid-sentence in the read
                // failure. One noun cannot be spelled correctly for both without this.
                .hasMessageContaining("Cover image exceeds");
    }

    @Test
    void anUnreadableFileFailsWithTheNounInMidSentenceForm(@TempDir Path dir) throws IOException {
        // Confined when resolved, then removed — the only honest way to reach the IO failure now
        // that a ConfinedPath cannot be minted around a path that was never checked.
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Files.write(root.path().resolve("gone"), new byte[1]);
        LocalFileGuard.ConfinedPath vanishing = LocalFileGuard.resolveWithinRoot(
                root.path().resolve("gone").toString(), root, "filePath", "upload");
        Files.delete(root.path().resolve("gone"));

        assertThatThrownBy(() -> LocalFileGuard.readBounded(vanishing, 10, "cover image"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(LocalFileGuard.TooLargeException.class)
                .hasMessageContaining("Failed to read cover image");
    }

    @Test
    void aMissingPathAndAnOutsidePathAreIndistinguishable(@TempDir Path dir) throws IOException {
        // Deliberately one message. Two would let any caller ask "does /etc/shadow exist?" and
        // read the answer off which refusal came back — an existence oracle over the whole host,
        // one tool call at a time. Both still name the parameter, which is what an operator needs.
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Path outside = Files.writeString(dir.resolve("real-but-outside"), "x");

        String missing = catchThrowable(() -> LocalFileGuard.resolveWithinRoot(
                root.path().resolve("nope").toString(), root, "filePath", "upload")).getMessage();
        String elsewhere = catchThrowable(() -> LocalFileGuard.resolveWithinRoot(
                outside.toString(), root, "filePath", "upload")).getMessage();

        // A path the OS will not even parse. Paths.get throws InvalidPathException, which is
        // unchecked, so it escaped this refusal entirely until it was caught alongside
        // IOException. "One answer" has to mean one answer, not one answer usually.
        //
        // Built with a char cast rather than written as a literal, so the source file stays plain
        // text and no editor or tool has to preserve an embedded control character.
        String unparseable = catchThrowable(() -> LocalFileGuard.resolveWithinRoot(
                String.valueOf((char) 0), root, "filePath", "upload")).getMessage();

        // Paths.get(null) raises NullPointerException, which the catch does not cover — the same
        // hole InvalidPathException was, one step earlier. Both callers gate on isBlank() so it is
        // unreachable today, but shared code cannot rely on every future caller gating first.
        String nothing = catchThrowable(() -> LocalFileGuard.resolveWithinRoot(
                null, root, "filePath", "upload")).getMessage();

        assertThat(missing).isEqualTo(elsewhere).isEqualTo(unparseable).isEqualTo(nothing)
                .isEqualTo("filePath is not a readable file inside the allowed upload directory");
    }

    @Test
    void theBytesReadAreTheFilesOwn(@TempDir Path dir) throws IOException {
        LocalFileGuard.Root root = LocalFileGuard.resolveRoot(
                Files.createDirectory(dir.resolve("root")).toString(), "VAR");
        Files.writeString(root.path().resolve("f"), "hello");
        LocalFileGuard.ConfinedPath file = LocalFileGuard.resolveWithinRoot(
                root.path().resolve("f").toString(), root, "filePath", "upload");

        assertThat(new String(LocalFileGuard.readBounded(file, 1024, "file"), StandardCharsets.UTF_8))
                .isEqualTo("hello");
    }
}
