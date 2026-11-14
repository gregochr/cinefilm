package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.TestData.MIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.ActiveArea;
import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.FakeFfmpeg;
import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frames of one file, written from a stand-in for ffmpeg: the exact file names, bytes equal to {@link FramePrep}'s
 * for the same frames, one dense decode with the range clamped to the file, and a checkpoint between dense writes.
 */
class DatasetFramesTest {

    /** A 10 minute file: its window is the last 4 minutes, so 48 coarse frames from 6:00. */
    private static final long LENGTH = 10 * MIN;
    private static final Path SOURCE = Path.of("/library/film.m4v");

    @TempDir
    Path temp;

    private final FakeFfmpeg ffmpeg = new FakeFfmpeg();
    private CreditsFrameReader reader;
    private Path dir;

    @BeforeEach
    void setUp() {
        reader = new CreditsFrameReader(ffmpeg, "ffmpeg", 10, temp.resolve("cache").toString());
        dir = temp.resolve("frames/7");
        ffmpeg.denseCount = 4;
        // A different colour at every offset, so a frame stored under the wrong name would not match.
        ffmpeg.rgbAt = ms -> FakeFfmpeg.flat((int) (ms / 500 % 256), 50, 90);
        ffmpeg.frameAt = ms -> new GreyFrame(4, 2, new byte[] {100, 100, 100, 100, 100, 100, 100, 100});
    }

    private DatasetTarget target(long startMs) {
        return new DatasetTarget(7L, "film.m4v", "1:1", LENGTH, startMs);
    }

    private DatasetFrames.Written write(long startMs, Runnable checkpoint) throws IOException {
        try (CreditsFrameReader.Job job = reader.open(SOURCE, LENGTH, () -> { }, 19)) {
            return DatasetFrames.write(job, target(startMs), dir, checkpoint);
        }
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> namesFor(List<Long> offsets) {
        List<String> names = new ArrayList<>();
        for (long offset : offsets) {
            names.add(offset + ".jpg");
            names.add(offset + ".png");
        }
        return names.stream().sorted().toList();
    }

    @Test
    void theDensePassIsThirtySecondsBeforeTheTargetForSixtySeconds() {
        assertThat(DatasetFrames.DENSE_LEAD_MS).isEqualTo(30_000);
        assertThat(DatasetFrames.DENSE_LENGTH_MS).isEqualTo(60_000);
    }

    @Test
    void everyCoarseOffsetIsWrittenAsAPngAndAJpegNamedByItsOffset() throws IOException {
        DatasetFrames.Written written = write(570_000, () -> { });

        List<Long> offsets = CreditsFrameReader.coarseOffsets(LENGTH);
        assertThat(offsets).hasSize(48).startsWith(360_000L).endsWith(595_000L);
        assertThat(names(dir.resolve("coarse"))).isEqualTo(namesFor(offsets));
        assertThat(written.coarse()).isEqualTo(offsets);
    }

    @Test
    void theDenseFramesAreNamedByTheirOwnOffsetsFromTheClampedStart() throws IOException {
        DatasetFrames.Written written = write(570_000, () -> { });

        // The target at 9:30 starts the decode at 9:00 and the file allows the full 60 seconds.
        List<Long> offsets = List.of(540_000L, 540_500L, 541_000L, 541_500L);
        assertThat(names(dir.resolve("dense"))).isEqualTo(namesFor(offsets));
        assertThat(written.dense()).isEqualTo(offsets);
    }

    @Test
    void theBytesAreExactlyWhatFramePrepMakesOfTheSameFrames() throws IOException {
        write(570_000, () -> { });

        ActiveArea area = new ActiveArea(0, 0, 4, 2);
        for (long offset : List.of(360_000L, 365_000L, 595_000L)) {
            RgbFrame rgb = ffmpeg.rgbAt.apply(offset);
            assertThat(Files.readAllBytes(dir.resolve("coarse/" + offset + ".png")))
                .isEqualTo(FramePrep.png(FramePrep.grey(rgb)));
            assertThat(Files.readAllBytes(dir.resolve("coarse/" + offset + ".jpg")))
                .isEqualTo(FramePrep.colourJpeg(rgb, area));
        }
        for (long offset : List.of(540_000L, 541_500L)) {
            RgbFrame rgb = ffmpeg.rgbAt.apply(offset);
            assertThat(Files.readAllBytes(dir.resolve("dense/" + offset + ".png")))
                .isEqualTo(FramePrep.png(FramePrep.grey(rgb)));
            assertThat(Files.readAllBytes(dir.resolve("dense/" + offset + ".jpg")))
                .isEqualTo(FramePrep.colourJpeg(rgb, area));
        }
    }

    @Test
    void theColourPictureIsCroppedToTheActiveAreaMeasuredOnTheGreyFrames() throws IOException {
        byte[] barred = new byte[40];
        Arrays.fill(barred, (byte) 128);
        Arrays.fill(barred, 0, 8, (byte) 16);
        Arrays.fill(barred, 32, 40, (byte) 16);
        GreyFrame letterboxed = new GreyFrame(4, 10, barred);
        ffmpeg.frameAt = ms -> letterboxed;
        ffmpeg.rgbAt = ms -> {
            byte[] pixels = new byte[4 * 10 * 3];
            for (int i = 0; i < pixels.length; i += 3) {
                pixels[i] = (byte) (i / 3 * 6);
                pixels[i + 1] = 80;
                pixels[i + 2] = 20;
            }
            return new RgbFrame(4, 10, pixels);
        };
        ActiveArea expected = ActiveArea.of(List.of(letterboxed));
        assertThat(expected).isEqualTo(new ActiveArea(0, 2, 4, 6));

        DatasetFrames.Written written = write(570_000, () -> { });

        assertThat(written.area()).isEqualTo(expected);
        assertThat(written.frameWidth()).isEqualTo(4);
        assertThat(written.frameHeight()).isEqualTo(10);
        assertThat(Files.readAllBytes(dir.resolve("coarse/360000.jpg")))
            .isEqualTo(FramePrep.colourJpeg(ffmpeg.rgbAt.apply(360_000L), expected));
        assertThat(Files.readAllBytes(dir.resolve("dense/540000.jpg")))
            .isEqualTo(FramePrep.colourJpeg(ffmpeg.rgbAt.apply(540_000L), expected));
    }

    @Test
    void itReturnsWhatCompleteJsonNeeds() throws IOException {
        DatasetFrames.Written written = write(570_000, () -> { });

        assertThat(written.area()).isEqualTo(new ActiveArea(0, 0, 4, 2));
        assertThat(written.frameWidth()).isEqualTo(4);
        assertThat(written.frameHeight()).isEqualTo(2);
        assertThat(written.activeAreaOffsets()).containsExactly(180_000L, 225_000L, 270_000L, 315_000L, 360_000L);
        assertThat(Files.exists(dir.resolve("complete.json"))).isFalse();
    }

    @Test
    void theActiveAreaIsReadFirstThenEveryCoarseFrameThenOneDenseDecode() throws IOException {
        write(570_000, () -> { });

        // 5 grey frames for the area, 48 colour frames across the window, and one decode for the dense frames.
        assertThat(ffmpeg.commands).hasSize(5 + 48 + 1);
        assertThat(ffmpeg.commands.subList(0, 5)).allMatch(command -> command.contains(
            CreditsFrameReader.FILTER + ",format=gray"));
        assertThat(ffmpeg.commands.subList(5, 53)).allMatch(command -> command.contains(
            CreditsFrameReader.FILTER + ",format=rgb24"));
        assertThat(ffmpeg.commands.stream().filter(command -> command.getLast().contains("%05d"))).hasSize(1);
    }

    @Test
    void aTargetNearTheStartOfTheFileClampsTheDecodeToTheFile() throws IOException {
        ffmpeg.denseCount = 2;

        DatasetFrames.Written written = write(10_000, () -> { });

        // 10 s minus 30 s is before the file starts: the decode runs from 0 for 40 s (to 10 s plus 30 s).
        List<String> dense = ffmpeg.commands.getLast();
        assertThat(dense.get(dense.indexOf("-ss") + 1)).isEqualTo("0.000");
        assertThat(dense.get(dense.indexOf("-t") + 1)).isEqualTo("40.000");
        assertThat(written.dense()).containsExactly(0L, 500L);
    }

    @Test
    void aTargetNearTheEndOfTheFileClampsTheDecodeToTheFile() throws IOException {
        ffmpeg.denseCount = 2;

        DatasetFrames.Written written = write(LENGTH - 5_000, () -> { });

        // 5 s before the end: it starts 30 s before that and the file ends 35 s later.
        List<String> dense = ffmpeg.commands.getLast();
        assertThat(dense.get(dense.indexOf("-ss") + 1)).isEqualTo("565.000");
        assertThat(dense.get(dense.indexOf("-t") + 1)).isEqualTo("35.000");
        assertThat(written.dense()).containsExactly(565_000L, 565_500L);
    }

    @Test
    void theCheckpointRunsBeforeEveryDenseWriteAndNotForTheCoarseOnes() throws IOException {
        AtomicInteger runs = new AtomicInteger();

        write(570_000, runs::incrementAndGet);

        assertThat(runs).hasValue(4);
    }

    @Test
    void aCheckpointThatThrowsStopsTheDenseWritesWhereItIs() {
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> write(570_000, () -> {
            if (runs.incrementAndGet() == 2) {
                throw new IllegalStateException("stopped");
            }
        })).isInstanceOf(IllegalStateException.class).hasMessage("stopped");

        assertThat(dir.resolve("dense/540000.png")).exists();
        assertThat(dir.resolve("dense/540000.jpg")).exists();
        assertThat(dir.resolve("dense/540500.png")).doesNotExist();
    }

    @Test
    void anFfmpegFailureAbandonsTheFileWithTheReadersException() {
        ffmpeg.failOnCall = 7;

        assertThatThrownBy(() -> write(570_000, () -> { }))
            .isInstanceOf(CreditsFrameReader.ReadException.class);
    }
}
