package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryScannerTest {

    private static final Instant T1 = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2021-06-01T00:00:00Z");

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
    void findsPlayableAndConversionCandidatesRecursivelyInPathOrder() throws IOException {
        file("10 Cloverfield Lane.m4v", "a");
        file("Sub/Deeper/Heat.MP4", "b");
        file("Old/Clip.mov", "c");
        file("Old/Classic.avi", "d");
        file("Old/Rip.mkv", "e");

        Found found = scanner.scan();

        assertThat(found.rejected()).isZero();
        assertThat(found.files()).extracting(FoundFile::path).containsExactly("10 Cloverfield Lane.m4v",
            "Old/Classic.avi", "Old/Clip.mov", "Old/Rip.mkv", "Sub/Deeper/Heat.MP4");
        assertThat(found.files()).extracting(FoundFile::needsConversion)
            .containsExactly(false, true, false, true, false);
    }

    @Test
    void ignoresOtherFileTypesHiddenFilesAppleDoubleFilesAndHiddenFolders() throws IOException {
        file("Heat.m4v", "film");
        file("notes.txt", "x");
        file("poster.jpg", "x");
        file("noextension", "x");
        file(".hidden.m4v", "x");
        file("._Heat.m4v", "x");
        file(".Trashes/Deleted.m4v", "x");
        file("Folder/._Alien.m4v", "x");
        Files.createDirectories(library.resolve("empty"));
        Files.createDirectories(library.resolve("folder.m4v"));

        assertThat(scanner.scan().files()).extracting(FoundFile::path).containsExactly("Heat.m4v");
    }

    @Test
    void thereIsNoSizeFilter() throws IOException {
        file("Tiny.m4v", "");

        assertThat(scanner.scan().files()).singleElement().satisfies(f -> assertThat(f.sizeBytes()).isZero());
    }

    @Test
    void reportsSizeAndTimes() throws IOException {
        Path film = file("Heat.m4v", "0123456789");
        Instant modified = Instant.parse("2020-05-17T10:15:30Z");
        Files.setLastModifiedTime(film, FileTime.from(modified));

        FoundFile found = scanner.scan().files().get(0);

        assertThat(found.sizeBytes()).isEqualTo(10);
        assertThat(found.modifiedAt()).isEqualTo(modified);
        assertThat(found.createdAt()).isNotNull().isAfter(Instant.EPOCH);
        assertThat(found.fileName()).isEqualTo("Heat.m4v");
    }

    @Test
    void aFileNameIsWhatFollowsTheLastSlash() throws IOException {
        file("A/B/Heat.m4v", "x");

        assertThat(scanner.scan().files().get(0).fileName()).isEqualTo("Heat.m4v");
    }

    @Test
    void aSymlinkEscapingTheRootIsRejectedAndNotListed() throws IOException {
        Path outside = Files.writeString(base.resolve("outside.m4v"), "secret");
        Files.createSymbolicLink(library.resolve("Escape.m4v"), outside);
        Path outsideDir = Files.createDirectory(base.resolve("elsewhere"));
        Files.writeString(outsideDir.resolve("Inside.m4v"), "secret");
        Files.createSymbolicLink(library.resolve("shared"), outsideDir);
        file("Heat.m4v", "film");

        Found found = scanner.scan();

        assertThat(found.files()).extracting(FoundFile::path).containsExactly("Heat.m4v");
        assertThat(found.rejected()).isEqualTo(1);
    }

    @Test
    void aSymlinkInsideTheRootIsListedWithTheTargetsSize() throws IOException {
        Path film = file("Real.m4v", "12345");
        Files.createSymbolicLink(library.resolve("Alias.m4v"), film);

        assertThat(scanner.scan().files()).extracting(FoundFile::path).containsExactly("Alias.m4v", "Real.m4v");
    }

    @Test
    void aBrokenSymlinkIsRejected() throws IOException {
        Files.createSymbolicLink(library.resolve("Broken.m4v"), base.resolve("nowhere.m4v"));

        Found found = scanner.scan();

        assertThat(found.files()).isEmpty();
        assertThat(found.rejected()).isEqualTo(1);
    }

    @Test
    void aMissingRootIsAnError() {
        LibraryScanner missing = new LibraryScanner(new LibraryRoot(base.resolve("not-mounted").toString()));

        assertThatThrownBy(missing::scan).isInstanceOf(UncheckedIOException.class)
            .hasMessage("Cannot read the library folder");
    }

    @Test
    void anUnreadableSubfolderIsSkippedAndTheRestIsStillScanned() throws IOException {
        file("Heat.m4v", "film");
        Path locked = Files.createDirectory(library.resolve("locked"));
        Files.writeString(locked.resolve("Hidden.m4v"), "x");
        assumeFalse(!locked.toFile().setReadable(false) || Files.isReadable(locked),
            "running as a user that ignores file permissions");
        try {
            Found found = scanner.scan();
            assertThat(found.files()).extracting(FoundFile::path).containsExactly("Heat.m4v");
            assertThat(found.unreadable()).containsExactly("locked");
            assertThat(found.isUnder("locked/Hidden.m4v")).isTrue();
        } finally {
            assertThat(locked.toFile().setReadable(true)).isTrue();
        }
    }

    @Test
    void aFileNamedLikeAnExtensionButWithoutADotIsNotAFilm() throws IOException {
        file("m4v", "x");
        file("mp4", "x");
        file("Sub/avi", "x");

        assertThat(scanner.scan().files()).isEmpty();
    }

    @Test
    void anEmptyRootFindsNothing() {
        Found found = scanner.scan();

        assertThat(found.files()).isEmpty();
        assertThat(found.rejected()).isZero();
    }

    @Test
    void aFileSystemWithNoCreationTimeFallsBackToTheModifiedTime() {
        assertThat(LibraryScanner.created(Instant.EPOCH, T2)).isEqualTo(T2);
        assertThat(LibraryScanner.created(Instant.ofEpochMilli(1), T2)).isEqualTo(Instant.ofEpochMilli(1));
        assertThat(LibraryScanner.created(T1, T2)).isEqualTo(T1);
    }

    @Test
    void aPathIsUnderAnUnreadableFolderOnlyIfItIsThatFolderOrInsideIt() {
        Found found = new Found(List.of(), 0, List.of("Locked", "A/B"));

        assertThat(found.isUnder("Locked")).isTrue();
        assertThat(found.isUnder("Locked/Heat.m4v")).isTrue();
        assertThat(found.isUnder("A/B/C/Alien.m4v")).isTrue();
        assertThat(found.isUnder("Lockedness.m4v")).isFalse();
        assertThat(found.isUnder("A/Heat.m4v")).isFalse();
        assertThat(new Found(List.of(), 0).isUnder("Locked/Heat.m4v")).isFalse();
        assertThat(new Found(List.of(), 0).unreadable()).isEmpty();
    }

    @Test
    void aCleanWalkHasNoUnreadableFolders() throws IOException {
        file("Heat.m4v", "x");

        assertThat(scanner.scan().unreadable()).isEmpty();
    }
}
