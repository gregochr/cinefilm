package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SECURITY.md rule 3: nothing outside the library root is ever resolved. */
class LibraryRootTest {

    @TempDir
    Path base;

    private Path library;
    private LibraryRoot root;

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectory(base.resolve("library"));
        root = new LibraryRoot(library.toString());
    }

    @Test
    void aRelativePathResolvesToTheExistingFileUnderTheRoot() throws IOException {
        Path film = Files.writeString(Files.createDirectories(library.resolve("Sci-Fi")).resolve("Alien.m4v"), "x");

        assertThat(root.resolveFile("Sci-Fi/Alien.m4v")).contains(film.toRealPath());
    }

    @Test
    void theRootIsAbsoluteAndNormalised() {
        assertThat(new LibraryRoot(library + "/sub/..").root()).isEqualTo(library.toAbsolutePath());
    }

    @Test
    void parentTraversalIsRefusedEvenWhenTheTargetExists() throws IOException {
        Files.writeString(base.resolve("secret.m4v"), "secret");

        assertThat(root.resolveFile("../secret.m4v")).isEmpty();
        assertThat(root.resolveFile("a/../../secret.m4v")).isEmpty();
    }

    @Test
    void traversalThatStaysInsideTheRootIsFine() throws IOException {
        Path film = Files.writeString(Files.createDirectories(library.resolve("a")).resolve("f.m4v"), "x");

        assertThat(root.resolveFile("a/../a/f.m4v")).contains(film.toRealPath());
    }

    @Test
    void anAbsolutePathIsRefusedEvenWhenItPointsInsideTheRoot() throws IOException {
        Path film = Files.writeString(library.resolve("f.m4v"), "x");

        assertThat(root.resolveFile(film.toString())).isEmpty();
    }

    @Test
    void aSymlinkOutOfTheRootIsRefused() throws IOException {
        Path outside = Files.writeString(base.resolve("outside.m4v"), "secret");
        Files.createSymbolicLink(library.resolve("link.m4v"), outside);

        assertThat(root.resolveFile("link.m4v")).isEmpty();
    }

    @Test
    void aSymlinkedFolderOutOfTheRootIsRefused() throws IOException {
        Path outsideDir = Files.createDirectory(base.resolve("elsewhere"));
        Files.writeString(outsideDir.resolve("f.m4v"), "secret");
        Files.createSymbolicLink(library.resolve("shared"), outsideDir);

        assertThat(root.resolveFile("shared/f.m4v")).isEmpty();
    }

    @Test
    void aSymlinkThatStaysInsideTheRootIsFollowed() throws IOException {
        Path film = Files.writeString(library.resolve("real.m4v"), "x");
        Files.createSymbolicLink(library.resolve("alias.m4v"), film);

        assertThat(root.resolveFile("alias.m4v")).contains(film.toRealPath());
    }

    @Test
    void aSymlinkedRootIsStillTheRoot() throws IOException {
        Path film = Files.writeString(library.resolve("f.m4v"), "x");
        Path linkedRoot = Files.createSymbolicLink(base.resolve("mount"), library);

        assertThat(new LibraryRoot(linkedRoot.toString()).resolveFile("f.m4v")).contains(film.toRealPath());
    }

    @Test
    void missingBlankDirectoryAndInvalidPathsAreEmpty() throws IOException {
        Files.createDirectory(library.resolve("folder"));

        assertThat(root.resolveFile("nope.m4v")).isEmpty();
        assertThat(root.resolveFile("")).isEmpty();
        assertThat(root.resolveFile("  ")).isEmpty();
        assertThat(root.resolveFile("folder")).isEmpty();
        assertThat(root.resolveFile("bad\0name.m4v")).isEmpty();
    }

    @Test
    void relativizeGivesForwardSlashPathsRelativeToTheRoot() {
        assertThat(root.relativize(library.resolve("Sci-Fi").resolve("Alien.m4v"))).isEqualTo("Sci-Fi/Alien.m4v");
    }
}
