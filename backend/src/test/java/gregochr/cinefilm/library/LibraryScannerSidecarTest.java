package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.library.LibraryScanner.FoundSidecar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one walk also collects forced English subtitle files beside the films, with the same skips. */
class LibraryScannerSidecarTest {

    private static final Instant T1 = Instant.parse("2020-01-01T00:00:00Z");

    @TempDir
    Path base;

    private Path library;
    private LibraryScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectory(base.resolve("library"));
        scanner = new LibraryScanner(new LibraryRoot(library.toString()));
    }

    private Path file(String relative, String content) throws IOException {
        Path path = library.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    @Test
    void collectsBothSuffixesInAnyCaseWithSizeAndModifiedTimeInPathOrder() throws IOException {
        file("Taken (2008)/Taken.m4v", "film");
        Path forced = file("Taken (2008)/Taken.en.forced.srt", "12345");
        Files.setLastModifiedTime(forced, FileTime.from(T1));
        file("Heat/Heat.forced.en.srt", "x");
        file("Alien/ALIEN.EN.FORCED.SRT", "xy");

        Found found = scanner.scan();

        assertThat(found.files()).extracting(FoundFile::path).containsExactly("Taken (2008)/Taken.m4v");
        assertThat(found.sidecars()).extracting(FoundSidecar::path).containsExactly("Alien/ALIEN.EN.FORCED.SRT",
            "Heat/Heat.forced.en.srt", "Taken (2008)/Taken.en.forced.srt");
        assertThat(found.sidecars().get(2).sizeBytes()).isEqualTo(5);
        assertThat(found.sidecars().get(2).modifiedAt()).isEqualTo(T1);
    }

    @Test
    void otherSubtitleFilesAreNotCollected() throws IOException {
        file("Taken.en.srt", "x");
        file("Taken.srt", "x");
        file("Taken.forced.srt", "x");
        file("Taken.en.forced.srt.bak", "x");
        file(".en.forced.srt", "x");

        assertThat(scanner.scan().sidecars()).isEmpty();
    }

    @Test
    void hiddenFilesAppleDoubleFilesHiddenFoldersAndPlexVersionsAreSkipped() throws IOException {
        file("._Taken.en.forced.srt", "x");
        file("Folder/._Alien.en.forced.srt", "x");
        file(".Trashes/Heat.en.forced.srt", "x");
        file("Plex Versions/Optimized/Heat.en.forced.srt", "x");
        file("Sub/Plex Versions/Alien.en.forced.srt", "x");
        file("Sub/Alien.en.forced.srt", "x");

        assertThat(scanner.scan().sidecars()).extracting(FoundSidecar::path).containsExactly("Sub/Alien.en.forced.srt");
    }

    @Test
    void aSidecarSymlinkEscapingTheRootIsRejectedAndOneInsideIsListed() throws IOException {
        Path outside = Files.writeString(base.resolve("outside.en.forced.srt"), "secret");
        Files.createSymbolicLink(library.resolve("Escape.en.forced.srt"), outside);
        Path inside = file("Real.en.forced.srt", "12345");
        Files.createSymbolicLink(library.resolve("Alias.en.forced.srt"), inside);

        Found found = scanner.scan();

        assertThat(found.sidecars()).extracting(FoundSidecar::path).containsExactly("Alias.en.forced.srt",
            "Real.en.forced.srt");
        assertThat(found.rejected()).isEqualTo(1);
    }

    @Test
    void theShorterConstructorsCarryNoSidecars() {
        assertThat(new Found(List.of(), 0).sidecars()).isEmpty();
        assertThat(new Found(List.of(), 0, List.of("Locked")).sidecars()).isEmpty();
    }
}
