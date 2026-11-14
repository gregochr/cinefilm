package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilmCheckTest {

    private static final Instant TOUCHED = Instant.parse("2026-11-10T12:05:00Z");

    @TempDir
    Path temp;

    private Path dir;
    private final List<FilmCheck.Measured> seen = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        dir = MeasureFixtures.film(temp, "7", MeasureFixtures.complete(7, "ffmpeg 7", "abc",
            "2026-11-10T12:10:00Z", List.of(0L, 5_000L), List.of(100_000L)));
        MeasureFixtures.touch(dir, TOUCHED);
    }

    private FilmCheck.Result check() throws IOException {
        seen.clear();
        return FilmCheck.check(dir, seen::add);
    }

    private long size(String relative) throws IOException {
        return Files.size(dir.resolve(relative));
    }

    @Test
    void aCleanFilmIsOkWithEveryFrameHandedOverInOrderAndTheBytesAdded() throws IOException {
        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.OK);
        assertThat(result.complete().mediaFileId()).isEqualTo(7);
        assertThat(result.missing()).isZero();
        assertThat(result.corrupt()).isZero();
        assertThat(result.extra()).isZero();
        assertThat(seen).extracting(FilmCheck.Measured::kind, FilmCheck.Measured::offsetMs)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("coarse", 0L),
                org.assertj.core.groups.Tuple.tuple("coarse", 5_000L),
                org.assertj.core.groups.Tuple.tuple("dense", 100_000L));
        long pngs = size("coarse/0.png") + size("coarse/5000.png") + size("dense/100000.png");
        long jpgs = size("coarse/0.jpg") + size("coarse/5000.jpg") + size("dense/100000.jpg");
        assertThat(result.pngBytes()).isEqualTo(pngs);
        assertThat(result.jpgBytes()).isEqualTo(jpgs);
        assertThat(result.totalBytes()).isEqualTo(pngs + jpgs + size("complete.json"));
        assertThat(result.earliestModified()).isEqualTo(TOUCHED);
        FilmCheck.Measured first = seen.getFirst();
        assertThat(first.pngBytes()).isEqualTo(size("coarse/0.png"));
        assertThat(first.jpgBytes()).isEqualTo(size("coarse/0.jpg"));
        assertThat(first.grey().width()).isEqualTo(4);
        assertThat(first.colour().width()).isEqualTo(4);
    }

    @Test
    void theEarliestModificationTimeOfAnyListedFileIsReported() throws IOException {
        Instant earlier = Instant.parse("2026-11-10T12:00:00.900Z");
        Files.setLastModifiedTime(dir.resolve("dense/100000.jpg"), java.nio.file.attribute.FileTime.from(earlier));

        assertThat(check().earliestModified()).isEqualTo(earlier);
    }

    @Test
    void aMissingPngIsDamagedAndItsFrameIsNotHandedOver() throws IOException {
        Files.delete(dir.resolve("coarse/5000.png"));

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.DAMAGED);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.corrupt()).isZero();
        assertThat(seen).extracting(FilmCheck.Measured::offsetMs).containsExactly(0L, 100_000L);
        assertThat(result.pngBytes()).isEqualTo(size("coarse/0.png") + size("dense/100000.png"));
    }

    @Test
    void aMissingFolderCountsBothFilesOfEveryFrame() throws IOException {
        try (var files = Files.list(dir.resolve("dense"))) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(dir.resolve("dense"));

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.DAMAGED);
        assertThat(result.missing()).isEqualTo(2);
        assertThat(result.extra()).isZero();
    }

    @Test
    void anUndecodableJpegIsCorrupt() throws IOException {
        Files.write(dir.resolve("dense/100000.jpg"), new byte[] {1, 2, 3, 4});

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.DAMAGED);
        assertThat(result.corrupt()).isEqualTo(1);
        assertThat(result.missing()).isZero();
        assertThat(seen).extracting(FilmCheck.Measured::offsetMs).containsExactly(0L, 5_000L);
    }

    @Test
    void anUndecodablePngIsCorrupt() throws IOException {
        Files.write(dir.resolve("coarse/0.png"), new byte[] {1, 2, 3, 4});

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.DAMAGED);
        assertThat(result.corrupt()).isEqualTo(1);
        assertThat(seen).hasSize(2);
    }

    @Test
    void aGreyFrameOfTheWrongWidthIsCorrupt() throws IOException {
        // The frame is 8 wide, so a grey frame must be 4 wide.
        Files.write(dir.resolve("coarse/0.png"), FramePrep.png(MeasureFixtures.grey(5, 2, 20)));

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.DAMAGED);
        assertThat(result.corrupt()).isEqualTo(1);
        assertThat(seen).extracting(FilmCheck.Measured::offsetMs).containsExactly(5_000L, 100_000L);
    }

    @Test
    void aGreyFrameOfTheRightWidthWhateverItsHeightIsAccepted() throws IOException {
        Files.write(dir.resolve("coarse/0.png"), FramePrep.png(MeasureFixtures.grey(4, 3, 20)));

        assertThat(check().status()).isEqualTo(FilmCheck.Status.OK);
    }

    @Test
    void aColourFrameOfTheWrongSizeIsCorrupt() throws IOException {
        Files.write(dir.resolve("coarse/5000.jpg"), FramePrep.jpeg(MeasureFixtures.colour(5, 20)));

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.DAMAGED);
        assertThat(result.corrupt()).isEqualTo(1);
    }

    @Test
    void aColourFrameThatIsTheRightWidthButNotSquareIsCorrupt() throws IOException {
        Files.write(dir.resolve("coarse/5000.jpg"),
            FramePrep.jpeg(new gregochr.cinefilm.credits.RgbFrame(4, 5, new byte[4 * 5 * 3])));

        assertThat(check().corrupt()).isEqualTo(1);
    }

    @Test
    void aMissingFileAndACorruptFileAreCountedSeparately() throws IOException {
        Files.delete(dir.resolve("coarse/0.png"));
        Files.write(dir.resolve("coarse/5000.png"), new byte[] {9});

        FilmCheck.Result result = check();

        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.corrupt()).isEqualTo(1);
    }

    @Test
    void filesThatAreNotListedAreExtraAndDoNotDamageTheFilm() throws IOException {
        Files.writeString(dir.resolve("coarse/notes.txt"), "x");
        Files.write(dir.resolve("dense/9.png"), new byte[] {1});
        Files.createDirectories(dir.resolve("dense/sub"));

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.OK);
        assertThat(result.extra()).isEqualTo(3);
        assertThat(result.totalBytes()).isEqualTo(
            size("coarse/0.png") + size("coarse/0.jpg") + size("coarse/5000.png") + size("coarse/5000.jpg")
            + size("dense/100000.png") + size("dense/100000.jpg") + size("complete.json") + 1 + 1);
    }

    @Test
    void aLeftOverTemporaryFileCountsInTheTotalButIsNotAFrameFile() throws IOException {
        Files.write(dir.resolve("complete.json.tmp"), new byte[10]);

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.OK);
        assertThat(result.extra()).isZero();
        assertThat(result.totalBytes()).isEqualTo(result.pngBytes() + result.jpgBytes() + size("complete.json") + 10);
    }

    @Test
    void aMissingCompleteJsonIsIncompleteAndNothingElseIsRead() throws IOException {
        Files.delete(dir.resolve("complete.json"));

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.INCOMPLETE);
        assertThat(result.complete()).isNull();
        assertThat(result.totalBytes()).isZero();
        assertThat(result.earliestModified()).isNull();
        assertThat(seen).isEmpty();
    }

    @Test
    void anOldFormatIsIncomplete() throws IOException {
        Files.writeString(dir.resolve("complete.json"), "{\"format\":0,\"mediaFileId\":7}");

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.INCOMPLETE);
        assertThat(seen).isEmpty();
    }

    @Test
    void anUnparseableCompleteJsonIsIncomplete() throws IOException {
        Files.writeString(dir.resolve("complete.json"), "{not json");

        assertThat(check().status()).isEqualTo(FilmCheck.Status.INCOMPLETE);
    }

    @Test
    void aCompleteJsonForAnotherMediaFileIsMismatchedAndNothingElseIsRead() throws IOException {
        Path other = Files.move(dir, temp.resolve("8"));
        dir = other;

        FilmCheck.Result result = check();

        assertThat(result.status()).isEqualTo(FilmCheck.Status.MISMATCHED);
        assertThat(result.complete()).isNull();
        assertThat(result.totalBytes()).isZero();
        assertThat(seen).isEmpty();
    }

    @Test
    void aDirectoryNameThatIsNotTheIdWrittenOutIsMismatched() throws IOException {
        dir = Files.move(dir, temp.resolve("007"));

        assertThat(check().status()).isEqualTo(FilmCheck.Status.MISMATCHED);
    }

    @Test
    void aFilmWithNoFramesAtAllIsOk() throws IOException {
        Path empty = MeasureFixtures.film(temp, "9", MeasureFixtures.complete(9, "f", "c", "2026-11-10T12:10:00Z",
            List.of(), List.of()));
        seen.clear();

        FilmCheck.Result result = FilmCheck.check(empty, seen::add);

        assertThat(result.status()).isEqualTo(FilmCheck.Status.OK);
        assertThat(result.earliestModified()).isNull();
        assertThat(seen).isEmpty();
    }
}
