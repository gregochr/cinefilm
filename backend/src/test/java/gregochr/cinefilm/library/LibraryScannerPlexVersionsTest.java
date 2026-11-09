package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plex's optimised copies are not films: the scanner skips folders named {@code Plex Versions}. */
class LibraryScannerPlexVersionsTest {

    @TempDir
    Path library;

    private void file(String relative) throws IOException {
        Path path = library.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
    }

    @Test
    void aPlexVersionsFolderIsSkippedAtTheRootAndWhenNested() throws IOException {
        file("Heat.m4v");
        file("Plex Versions/Optimized for Motorhome/Heat.m4v");
        file("Sub/Plex Versions/Alien.m4v");
        file("Sub/Alien.m4v");

        var found = new LibraryScanner(new LibraryRoot(library.toString())).scan();

        assertThat(found.files()).extracting(FoundFile::path).containsExactly("Heat.m4v", "Sub/Alien.m4v");
    }

    @Test
    void aFolderThatOnlyStartsWithTheWordsOrAFileCalledThatIsNotSkipped() throws IOException {
        file("Plex Versions Extra/Heat.m4v");
        file("plex versions/Alien.m4v");

        var found = new LibraryScanner(new LibraryRoot(library.toString())).scan();

        assertThat(found.files()).extracting(FoundFile::path)
            .containsExactly("Plex Versions Extra/Heat.m4v", "plex versions/Alien.m4v");
    }

    @Test
    void aLibraryRootThatIsItselfNamedPlexVersionsIsStillScanned(@TempDir Path other) throws IOException {
        Path root = Files.createDirectory(other.resolve("Plex Versions"));
        Files.writeString(root.resolve("Heat.m4v"), "x");

        var found = new LibraryScanner(new LibraryRoot(root.toString())).scan();

        assertThat(found.files()).extracting(FoundFile::path).containsExactly("Heat.m4v");
    }
}
