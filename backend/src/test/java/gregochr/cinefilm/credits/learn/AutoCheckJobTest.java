package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sheet job with a mocked still source: the tile times, the black tiles, the files written and the list. */
class AutoCheckJobTest {

    private static final String RUN = "auto-check-20261122T120000Z";
    private static final long END_MS = 6_000_000;

    @TempDir
    Path temp;

    private final StillSource source = mock(StillSource.class);
    private final StillSource.Reading reading = mock(StillSource.Reading.class);
    private final List<Long> asked = new ArrayList<>();
    private AutoCheckJob job;
    private Path media;
    private Path out;
    private Path candidates;

    @BeforeEach
    void setUp() throws IOException {
        job = new AutoCheckJob(Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC), source);
        media = Files.createDirectories(temp.resolve("media"));
        out = temp.resolve("out");
        candidates = temp.resolve("candidates.json");
        when(source.open(any(Path.class))).thenReturn(reading);
        // A frame is a 4x2 picture whose grey is the file time in seconds, mod 256, unless the time is past the end.
        when(reading.frameAt(anyLong())).thenAnswer(call -> {
            long ms = call.getArgument(0);
            asked.add(ms);
            return ms >= END_MS ? Optional.empty() : Optional.of(grey(ms / 1000 % 256));
        });
        when(reading.fullPngAt(anyLong())).thenAnswer(call -> {
            long ms = call.getArgument(0);
            asked.add(ms);
            return ms >= END_MS ? Optional.empty() : Optional.of(("png@" + ms).getBytes(StandardCharsets.UTF_8));
        });
    }

    private static GreyFrame grey(long value) {
        byte[] pixels = new byte[8];
        Arrays.fill(pixels, (byte) value);
        return new GreyFrame(4, 2, pixels);
    }

    private static String json(long edition, String path, long offset, String kind, long start, long marker,
                               boolean owner) {
        return ("{\"editionId\":%d,\"filmId\":9,\"mediaFileId\":5,\"path\":\"%s\",\"offsetMs\":%d,\"kind\":\"%s\","
            + "\"startMs\":%d,\"markerMs\":%d,\"owner\":%s}").formatted(edition, path, offset, kind, start, marker,
            owner);
    }

    private void candidates(String... items) throws IOException {
        Files.writeString(candidates, "[" + String.join(",", items) + "]");
    }

    private Path film(String name) throws IOException {
        Path file = media.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[] {1});
    }

    private AutoCheckOptions options(AutoCheckOptions.Full full) {
        return new AutoCheckOptions(candidates, out, media, full);
    }

    private List<String> listLines() throws IOException {
        return Files.readAllLines(out.resolve(RUN).resolve("list.csv"));
    }

    private GreyFrame sheet(String name) throws IOException {
        return FramePrep.decodePng(Files.readAllBytes(out.resolve(RUN).resolve(name)));
    }

    @Test
    void theNineTilesAreTheStartAroundTheStartAndTheMarkerWithItsFiveOffsets() {
        assertThat(AutoCheckJob.editionTimes(100_000, 120_000)).containsExactly(90_000, 100_000, 110_000, 120_000,
            135_000, 150_000, 180_000, 240_000, 360_000);
    }

    @Test
    void aTwoPartEditionsFileTimeIsTheEditionTimeMinusTheStartMinusTheOffset() {
        AutoCheckCandidate two = new AutoCheckCandidate(1, 1, 1, "p", 5_000, "CRAWL", 7_205_000, 7_225_000, false);

        assertThat(AutoCheckJob.fileTime(two, 7_205_000)).isEqualTo(5_000);
        assertThat(AutoCheckJob.fileTime(two, 7_225_000)).isEqualTo(25_000);
        assertThat(AutoCheckJob.fileTime(two, 7_195_000)).isEqualTo(-5_000);
    }

    @Test
    void aSinglePartEditionReadsEachTileAtItsOwnTimeInTheFile() throws IOException {
        film("a/one.mkv");
        candidates(json(7, "a/one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        List<Path> written = job.run(options(null));

        assertThat(asked).containsExactly(90_000L, 100_000L, 110_000L, 120_000L, 135_000L, 150_000L, 180_000L,
            240_000L, 360_000L);
        assertThat(written).containsExactly(out.resolve(RUN), out.resolve(RUN).resolve("edition-7.png"),
            out.resolve(RUN).resolve("list.csv"));
        assertThat(listLines()).containsExactly(
            "editionId,kind,owner,startMs,markerMs,sheet,blackTiles,note",
            "7,CRAWL,false,100000,120000,edition-7.png,,");
    }

    @Test
    void aTwoPartEditionIsReadInTheLastPartsFileTime() throws IOException {
        film("two.mkv");
        // The edition starts its credits at 7,205 s; inside the last part's file that is 5 s.
        candidates(json(8, "two.mkv", 5_000, "CRAWL", 7_205_000, 7_225_000, true));

        job.run(options(null));

        assertThat(asked).containsExactly(5_000L, 15_000L, 25_000L, 40_000L, 55_000L, 85_000L, 145_000L, 265_000L);
    }

    @Test
    void theSheetHoldsTheNineTilesInReadingOrder() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        GreyFrame sheet = sheet("edition-7.png");
        long[] fileSeconds = {90, 100, 110, 120, 135, 150, 180, 240, 360};
        for (int i = 0; i < 9; i++) {
            assertThat(sheet.luma(i % 3 * 8, i / 3 * 6)).as("tile %d", i + 1).isEqualTo((int) (fileSeconds[i] % 256));
        }
    }

    @Test
    void aTilePastTheEndOfTheFileIsBlackAndListed() throws IOException {
        film("one.mkv");
        // The marker is at 5,900 s, so tiles 8 and 9 fall at 6,020 s and 6,140 s; the file ends at 6,000 s.
        candidates(json(7, "one.mkv", 5_000_000, "CRAWL", 5_000_000, 5_900_000, false));

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("7,CRAWL,false,5000000,5900000,edition-7.png,8:past-end 9:past-end,");
        GreyFrame sheet = sheet("edition-7.png");
        assertThat(sheet.luma(1 * 8, 2 * 6)).isZero();
        assertThat(sheet.luma(2 * 8, 2 * 6)).isZero();
        assertThat(sheet.luma(0, 6)).isEqualTo(5_900 % 256);
    }

    private void emptyAt(long... times) {
        when(reading.frameAt(anyLong())).thenAnswer(call -> {
            long ms = call.getArgument(0);
            return Arrays.stream(times).anyMatch(t -> t == ms) ? Optional.empty() : Optional.of(grey(ms / 1000));
        });
    }

    @Test
    void anEmptyMiddleTileIsNoFrameAndTheNoteSaysTheCandidateWasNotFullyRead() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        emptyAt(120_000);

        job.run(options(null));

        assertThat(listLines().get(1))
            .isEqualTo("7,CRAWL,false,100000,120000,edition-7.png,4:no-frame,tile 4 had no picture");
        assertThat(sheet("edition-7.png").luma(0, 6)).isZero();
    }

    @Test
    void anEmptyTileFollowedByAReadOneIsNoFrameEvenAmongTheLastThree() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        emptyAt(240_000);

        job.run(options(null));

        assertThat(listLines().get(1))
            .isEqualTo("7,CRAWL,false,100000,120000,edition-7.png,8:no-frame,tile 8 had no picture");
    }

    @Test
    void emptyLastTilesAreNoFrameAfterAnEarlierGapAndPastEndOtherwise() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        emptyAt(135_000, 360_000);

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo(
            "7,CRAWL,false,100000,120000,edition-7.png,5:no-frame 9:past-end,tile 5 had no picture");
    }

    @Test
    void everyTileFromSixOnEmptyIsNoFrameForSixAndPastEndForTheLastThree() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        emptyAt(150_000, 180_000, 240_000, 360_000);

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("7,CRAWL,false,100000,120000,edition-7.png,"
            + "6:no-frame 7:past-end 8:past-end 9:past-end,tile 6 had no picture");
    }

    @Test
    void everyReadingIsClosedOncePerCandidate() throws IOException {
        film("a.mkv");
        film("b.mkv");
        candidates(json(1, "a.mkv", 100_000, "CRAWL", 100_000, 120_000, false),
            json(2, "b.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        verify(reading, times(2)).close();
    }

    @Test
    void aReadingWhoseReadFailedIsStillClosed() throws IOException {
        film("bad.mkv");
        when(reading.frameAt(anyLong())).thenThrow(new CreditsFrameReader.ReadException("ffmpeg timed out"));
        candidates(json(3, "bad.mkv", 0, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        verify(reading, times(1)).close();
    }

    @Test
    void aFullReadingIsClosed() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        job.run(options(new AutoCheckOptions.Full(7, 2)));

        verify(reading, times(1)).close();
    }

    @Test
    void aTileBeforeTheLastPartsStartIsBlackListedAndNotRead() throws IOException {
        film("two.mkv");
        // The start is 5 s into the last part's file, so the start minus 10 s is 5 s before the file begins.
        candidates(json(8, "two.mkv", 5_000, "CRAWL", 7_205_000, 7_225_000, false));

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("8,CRAWL,false,7205000,7225000,edition-8.png,1:before-part,");
        assertThat(asked).hasSize(8).doesNotContain(-5_000L);
        assertThat(sheet("edition-8.png").luma(0, 0)).isZero();
    }

    @Test
    void aMissingFileIsListedByEditionIdAndSkippedAndOthersStillRun() throws IOException {
        film("ok.mkv");
        candidates(json(1, "gone.mkv", 0, "CARD", 100_000, 120_000, true),
            json(2, "ok.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        assertThat(listLines()).containsExactly(
            "editionId,kind,owner,startMs,markerMs,sheet,blackTiles,note",
            "1,CARD,true,100000,120000,,,the file is missing",
            "2,CRAWL,false,100000,120000,edition-2.png,,");
        assertThat(out.resolve(RUN).resolve("edition-1.png")).doesNotExist();
    }

    @Test
    void aPathThatLeavesTheMediaRootIsTreatedAsMissingAndNeverOpened() throws IOException {
        Files.write(temp.resolve("outside.mkv"), new byte[] {1});
        candidates(json(1, "../outside.mkv", 0, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("1,CRAWL,false,100000,120000,,,the file is missing");
        verifyNoInteractions(source);
    }

    @Test
    void aFileFfmpegCannotReadIsListedWithTheReasonAndSkipped() throws IOException {
        film("bad.mkv");
        when(source.open(any(Path.class)))
            .thenThrow(new CreditsFrameReader.ReadException("ffmpeg could not be run: nope"));
        candidates(json(3, "bad.mkv", 0, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("3,CRAWL,false,100000,120000,,,ffmpeg could not be run: nope");
    }

    @Test
    void aReadThatFailsPartWayListsTheFileAndWritesNoSheet() throws IOException {
        film("bad.mkv");
        when(reading.frameAt(anyLong())).thenThrow(new CreditsFrameReader.ReadException("ffmpeg timed out"));
        candidates(json(3, "bad.mkv", 0, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("3,CRAWL,false,100000,120000,,,ffmpeg timed out");
        assertThat(out.resolve(RUN).resolve("edition-3.png")).doesNotExist();
    }

    @Test
    void aFileWithNoReadableTileIsListedAndSkipped() throws IOException {
        film("short.mkv");
        // Every tile is past the end of a file of 6,000 s.
        candidates(json(4, "short.mkv", 7_000_000, "CRAWL", 7_000_000, 7_020_000, false));

        job.run(options(null));

        assertThat(listLines().get(1)).isEqualTo("4,CRAWL,false,7000000,7020000,,,no tile could be read");
        assertThat(out.resolve(RUN).resolve("edition-4.png")).doesNotExist();
    }

    @Test
    void theListAndTheSheetNamesHoldNoPathAndNoTitle() throws IOException {
        film("Some Title (2000)/Some Title.mkv");
        candidates(json(7, "Some Title (2000)/Some Title.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        job.run(options(null));

        assertThat(String.join("\n", listLines())).doesNotContain("Title").doesNotContain("mkv").doesNotContain("/");
        try (var names = Files.list(out.resolve(RUN))) {
            assertThat(names.map(p -> p.getFileName().toString()).toList())
                .containsExactlyInAnyOrder("edition-7.png", "list.csv");
        }
    }

    @Test
    void anEmptyListWritesTheHeaderOnly() throws IOException {
        candidates();

        List<Path> written = job.run(options(null));

        assertThat(listLines()).containsExactly("editionId,kind,owner,startMs,markerMs,sheet,blackTiles,note");
        assertThat(written).containsExactly(out.resolve(RUN), out.resolve(RUN).resolve("list.csv"));
    }

    @Test
    void aRunDirectoryThatAlreadyExistsIsRefusedAndNothingIsRead() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        Files.createDirectories(out.resolve(RUN));
        Files.writeString(out.resolve(RUN).resolve("keep.txt"), "earlier run");

        assertThatThrownBy(() -> job.run(options(null))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("this run's directory already exists: " + out.resolve(RUN));

        assertThat(Files.readString(out.resolve(RUN).resolve("keep.txt"))).isEqualTo("earlier run");
        assertThat(asked).isEmpty();
    }

    @Test
    void fullWritesTheOneStillOfTheTileInItsOwnDirectory() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false),
            json(8, "one.mkv", 0, "CRAWL", 500_000, 520_000, false));

        List<Path> written = job.run(options(new AutoCheckOptions.Full(7, 5)));

        Path still = out.resolve(RUN).resolve("edition-7-tile-5.png");
        assertThat(written).containsExactly(out.resolve(RUN), still);
        assertThat(asked).containsExactly(135_000L);
        assertThat(Files.readString(still)).isEqualTo("png@135000");
        assertThat(out.resolve(RUN).resolve("list.csv")).doesNotExist();
    }

    @Test
    void fullThatFailsToReadIsRefusedWithTheReasonAndMakesNoDirectory() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        when(reading.fullPngAt(anyLong())).thenThrow(new CreditsFrameReader.ReadException("ffmpeg timed out"));

        assertThatThrownBy(() -> job.run(options(new AutoCheckOptions.Full(7, 1))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("edition 7: ffmpeg timed out");

        assertThat(out.resolve(RUN)).doesNotExist();
    }

    @Test
    void fullForAnEditionNotInTheListIsRefusedAndMakesNoDirectory() throws IOException {
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        assertThatThrownBy(() -> job.run(options(new AutoCheckOptions.Full(99, 1))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("no candidate for edition 99");

        assertThat(out.resolve(RUN)).doesNotExist();
    }

    @Test
    void fullForAMissingFileIsRefusedWithTheReason() throws IOException {
        candidates(json(7, "gone.mkv", 100_000, "CRAWL", 100_000, 120_000, false));

        assertThatThrownBy(() -> job.run(options(new AutoCheckOptions.Full(7, 1))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("edition 7: the file is missing");
    }

    @Test
    void fullForATilePastTheEndIsRefusedAndWritesNothing() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 5_990_000, "CRAWL", 5_990_000, 6_010_000, false));

        assertThatThrownBy(() -> job.run(options(new AutoCheckOptions.Full(7, 4))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("edition 7 tile 4 is no-frame: there is no still to write");

        assertThat(out.resolve(RUN)).doesNotExist();
    }

    @Test
    void fullForATileBeforeThePartIsRefusedWithoutReadingIt() throws IOException {
        film("two.mkv");
        candidates(json(8, "two.mkv", 5_000, "CRAWL", 7_205_000, 7_225_000, false));

        assertThatThrownBy(() -> job.run(options(new AutoCheckOptions.Full(8, 1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("edition 8 tile 1 is before-part: there is no still to write");

        assertThat(asked).isEmpty();
    }

    @Test
    void fullRefusesAnExistingRunDirectory() throws IOException {
        film("one.mkv");
        candidates(json(7, "one.mkv", 100_000, "CRAWL", 100_000, 120_000, false));
        Files.createDirectories(out.resolve(RUN));

        assertThatThrownBy(() -> job.run(options(new AutoCheckOptions.Full(7, 2))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }
}
