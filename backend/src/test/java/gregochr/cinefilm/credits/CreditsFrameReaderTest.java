package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import gregochr.cinefilm.credits.CreditsFrameReader.DenseRange;
import gregochr.cinefilm.credits.CreditsFrameReader.Job;
import gregochr.cinefilm.credits.CreditsFrameReader.ReadException;
import gregochr.cinefilm.credits.CreditsFrameReader.TimedFrame;
import gregochr.cinefilm.credits.CreditsFrameReader.TimedRgb;
import gregochr.cinefilm.library.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The frame reader against a stand-in for ffmpeg: the exact argument lists, the window and offset arithmetic, what a
 * failure, a timeout or unreadable output does, and that a job's temporary directory is always deleted.
 */
class CreditsFrameReaderTest {

    private static final Path FILM = Path.of("/library/Film (2000).mkv");
    private static final long MIN = 60_000L;

    @TempDir
    Path cache;

    private final FakeFfmpeg ffmpeg = new FakeFfmpeg();

    private CreditsFrameReader reader() {
        return new CreditsFrameReader(ffmpeg, "ffmpeg", 10, cache.toString());
    }

    private static List<Long> offsets(List<TimedFrame> frames) {
        return frames.stream().map(TimedFrame::offsetMs).toList();
    }

    // ---- Arithmetic ----------------------------------------------------------------------------------------------

    @Test
    void theWindowIsTheLastThirtyFiveMinutesOrFortyPercentOfAShorterFile() {
        assertThat(CreditsFrameReader.windowMs(120 * MIN)).isEqualTo(35 * MIN);
        assertThat(CreditsFrameReader.windowMs(10 * MIN)).isEqualTo(4 * MIN);
        // 40% of 87.5 minutes is exactly 35 minutes: one millisecond either side is 40% below and the cap above.
        assertThat(CreditsFrameReader.windowMs(5_249_999)).isEqualTo(2_099_999);
        assertThat(CreditsFrameReader.windowMs(5_250_000)).isEqualTo(2_100_000);
        assertThat(CreditsFrameReader.windowMs(5_250_001)).isEqualTo(2_100_000);
    }

    @Test
    void theActiveAreaIsMeasuredOnFiveFramesBetweenThirtyAndSixtyPercent() {
        assertThat(CreditsFrameReader.activeOffsets(1_000_000))
            .containsExactly(300_000L, 375_000L, 450_000L, 525_000L, 600_000L);
    }

    @Test
    void coarseFramesStartWithTheWindowAndAreFiveSecondsApart() {
        List<Long> offsets = CreditsFrameReader.coarseOffsets(120 * MIN);

        assertThat(offsets).hasSize(420).startsWith(85 * MIN, 85 * MIN + 5_000).endsWith(120 * MIN - 5_000);
        assertThat(offsets).isSortedAccordingTo(Long::compare);
    }

    @Test
    void aFileShorterThanThirtyFiveMinutesUsesFortyPercentAsItsWindow() {
        List<Long> offsets = CreditsFrameReader.coarseOffsets(20 * MIN);

        assertThat(offsets.getFirst()).isEqualTo(12 * MIN);
        assertThat(offsets).hasSize(96).endsWith(20 * MIN - 5_000);
    }

    @Test
    void noCoarseFrameIsCloserThanOneSecondToTheEnd() {
        // 502,500 ms: the window starts at 301,500 and the 41st frame lands exactly one second before the end.
        assertThat(CreditsFrameReader.coarseOffsets(502_500).getLast()).isEqualTo(501_500);
        // One millisecond shorter and that frame would be 999 ms from the end, so it is left out.
        assertThat(CreditsFrameReader.coarseOffsets(502_499).getLast()).isEqualTo(496_500);
    }

    @Test
    void theDenseDecodeStartsTenSecondsBeforeTheCandidateAndLastsFiveMinutes() {
        assertThat(CreditsFrameReader.denseRange(100 * MIN, 120 * MIN))
            .isEqualTo(new DenseRange(100 * MIN - 10_000, 300_000));
    }

    @Test
    void theDenseDecodeIsClampedAtTheStartOfTheFile() {
        assertThat(CreditsFrameReader.denseRange(5_000, 600_000)).isEqualTo(new DenseRange(0, 295_000));
        assertThat(CreditsFrameReader.denseRange(0, 600_000)).isEqualTo(new DenseRange(0, 290_000));
        assertThat(CreditsFrameReader.denseRange(10_000, 600_000)).isEqualTo(new DenseRange(0, 300_000));
    }

    @Test
    void theDenseDecodeIsClampedAtTheEndOfTheFile() {
        assertThat(CreditsFrameReader.denseRange(590_000, 600_000)).isEqualTo(new DenseRange(580_000, 20_000));
        assertThat(CreditsFrameReader.denseRange(310_000, 600_000)).isEqualTo(new DenseRange(300_000, 300_000));
        assertThat(CreditsFrameReader.denseRange(400_000, 600_000)).isEqualTo(new DenseRange(390_000, 210_000));
    }

    @Test
    void aFileShorterThanTheDenseSpanIsDecodedWhole() {
        assertThat(CreditsFrameReader.denseRange(10_000, 60_000)).isEqualTo(new DenseRange(0, 60_000));
    }

    @Test
    void aSeekTimeIsSecondsAndThreeDecimals() {
        assertThat(CreditsFrameReader.seekTime(0)).isEqualTo("0.000");
        assertThat(CreditsFrameReader.seekTime(999)).isEqualTo("0.999");
        assertThat(CreditsFrameReader.seekTime(3_723_450)).isEqualTo("3723.450");
    }

    // ---- Commands ------------------------------------------------------------------------------------------------

    @Test
    void theFrameCommandIsTheExactArgumentList() {
        List<String> command = CreditsFrameReader.frameCommand(10, "ffmpeg", FILM, 123_500, Path.of("/tmp/job/f.pgm"));

        assertThat(command).containsExactly("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
            "-protocol_whitelist", "file", "-threads", "1", "-ss", "123.500", "-i", "/library/Film (2000).mkv",
            "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn", "-vf",
            "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2,format=gray", "-f", "image2", "-y",
            "/tmp/job/f.pgm");
    }

    @Test
    void withoutNicenessTheCommandStartsWithFfmpegItself() {
        List<String> command = CreditsFrameReader.frameCommand(0, "/usr/bin/ffmpeg", FILM, 0, Path.of("/tmp/f.pgm"));

        assertThat(command).startsWith("/usr/bin/ffmpeg", "-nostdin").doesNotContain("nice");
    }

    @Test
    void theDenseCommandIsTheExactArgumentList() {
        DenseRange range = new DenseRange(5_000_000, 300_000);

        List<String> command = CreditsFrameReader.denseCommand(10, "ffmpeg", FILM, range,
            Path.of("/tmp/job/d_%05d.pgm"));

        assertThat(command).containsExactly("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
            "-protocol_whitelist", "file", "-threads", "1", "-ss", "5000.000", "-i", "/library/Film (2000).mkv",
            "-map", "0:v:0", "-t", "300.000", "-frames:v", "600", "-an", "-sn", "-dn", "-vf",
            "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2,fps=2,format=gray", "-start_number", "0",
            "-f", "image2", "-y", "/tmp/job/d_%05d.pgm");
    }

    @Test
    void aShortenedDenseRangeAsksForTheFramesThatFitRoundedUp() {
        List<String> command = CreditsFrameReader.denseCommand(0, "ffmpeg", FILM, new DenseRange(0, 100_250),
            Path.of("/tmp/d_%05d.pgm"));

        assertThat(command.get(command.indexOf("-frames:v") + 1)).isEqualTo("201");
    }

    // ---- Jobs ----------------------------------------------------------------------------------------------------

    @Test
    void theActiveAreaFramesAreFiveSingleSeeksBetweenThirtyAndSixtyPercent() {
        try (Job job = reader().open(FILM, 1_000_000, () -> { })) {
            List<GreyFrame> frames = job.activeAreaFrames();

            assertThat(frames).hasSize(5);
        }

        assertThat(ffmpeg.commands).extracting(c -> c.get(c.indexOf("-ss") + 1))
            .containsExactly("300.000", "375.000", "450.000", "525.000", "600.000");
        assertThat(ffmpeg.timeouts).containsOnly(CreditsFrameReader.SEEK_TIMEOUT);
    }

    @Test
    void eachCoarseFrameCarriesItsSeekTimeAsItsOffsetAndItsOwnPicture() {
        ffmpeg.frameAt = ms -> ms >= 510_000 ? SyntheticFrames.black() : SyntheticFrames.picture();
        List<TimedFrame> frames;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            frames = job.coarseFrames();
        }

        assertThat(offsets(frames)).isEqualTo(CreditsFrameReader.coarseOffsets(600_000));
        assertThat(frames.get(30).offsetMs()).isEqualTo(510_000);
        assertThat(frames.get(30).frame().luma(0, 0)).isZero();
        assertThat(frames.get(29).frame().luma(0, 0)).isEqualTo(128);
        assertThat(ffmpeg.commands).hasSize(frames.size());
    }

    @Test
    void theDenseFramesAreOneDecodeWithOffsetsFromTheSeekTimeAtHalfSecondSteps() {
        ffmpeg.frameAt = ms -> SyntheticFrames.fill((int) (ms / 500 % 256));
        List<DenseFrame> frames;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            frames = job.denseFrames(300_000);
        }

        assertThat(ffmpeg.commands).hasSize(1);
        assertThat(ffmpeg.timeouts).containsExactly(CreditsFrameReader.DENSE_TIMEOUT);
        assertThat(frames).hasSize(600);
        assertThat(frames.getFirst().offsetMs()).isEqualTo(290_000);
        assertThat(frames.get(1).offsetMs()).isEqualTo(290_500);
        assertThat(frames.getLast().offsetMs()).isEqualTo(589_500);
        // Each frame is the picture ffmpeg wrote for that index, so offset and picture stay together.
        assertThat(frames.get(7).frame().luma(0, 0)).isEqualTo((int) (293_500 / 500 % 256));
    }

    @Test
    void aClampedDenseDecodeStartsAtTheStartOfTheFile() {
        List<DenseFrame> frames;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            frames = job.denseFrames(5_000);
        }

        assertThat(frames).hasSize(590);
        assertThat(frames.getFirst().offsetMs()).isZero();
        assertThat(frames.getLast().offsetMs()).isEqualTo(294_500);
    }

    @Test
    void theCheckpointRunsBeforeEveryFfmpegCall() {
        AtomicInteger checks = new AtomicInteger();
        try (Job job = reader().open(FILM, 600_000, () -> {
            assertThat(ffmpeg.commands).hasSize(checks.getAndIncrement());
        })) {
            job.activeAreaFrames();
            job.denseFrames(300_000);
        }

        assertThat(checks).hasValue(6);
    }

    @Test
    void aCheckpointThatStopsTheJobStopsItBeforeFfmpegRunsAndTheDirectoryIsDeleted() {
        Job job = reader().open(FILM, 600_000, () -> {
            throw new IllegalStateException("playback needs the machine");
        });
        Path dir = null;
        try (job) {
            assertThatThrownBy(job::activeAreaFrames).hasMessage("playback needs the machine");
            dir = onlyJobDirectory();
        }

        assertThat(ffmpeg.commands).isEmpty();
        assertThat(dir).doesNotExist();
    }

    @Test
    void theDirectoryIsDeletedWhenTheJobSucceeds() {
        Path dir;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            job.activeAreaFrames();
            dir = onlyJobDirectory();
            assertThat(dir).exists();
        }

        assertThat(dir).doesNotExist();
        assertThat(cache.resolve("credits")).isEmptyDirectory();
    }

    @Test
    void aFailingFfmpegAbandonsTheFileAtTheFirstFailureAndDeletesTheDirectory() {
        ffmpeg.failOnCall = 3;
        Path dir;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(job::coarseFrames).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg exited with status 1 reading the frame at 370.000");
            dir = onlyJobDirectory();
        }

        assertThat(ffmpeg.commands).hasSize(3);
        assertThat(dir).doesNotExist();
    }

    @Test
    void aTimedOutSeekAbandonsTheFileAndDeletesTheDirectory() {
        ffmpeg.failOnCall = 1;
        ffmpeg.failure = new CommandRunner.Result(-1, "", true);
        Path dir;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(job::activeAreaFrames).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg timed out reading the frame at 180.000");
            dir = onlyJobDirectory();
        }

        assertThat(dir).doesNotExist();
    }

    @Test
    void aTimedOutDenseDecodeAbandonsTheFileAndDeletesTheDirectory() {
        ffmpeg.failOnCall = 1;
        ffmpeg.failure = new CommandRunner.Result(-1, "", true);
        Path dir;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.denseFrames(300_000)).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg timed out reading the frames around 300.000");
            dir = onlyJobDirectory();
        }

        assertThat(dir).doesNotExist();
    }

    @Test
    void ffmpegThatCannotBeStartedIsAReadFailure() {
        ffmpeg.failOnCall = 1;
        ffmpeg.ioFailure = new IOException("Cannot run program \"ffmpeg\"");
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(job::activeAreaFrames).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg could not be run: Cannot run program \"ffmpeg\"")
                .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void aSuccessfulRunThatWroteNoFrameIsAReadFailureNotAStaleFrame() {
        ffmpeg.writeNothing = true;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(job::activeAreaFrames).isInstanceOf(ReadException.class)
                .hasMessageStartingWith("ffmpeg wrote no readable frame")
                .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void aFrameThatIsNotAGreyscalePgmIsAReadFailure() {
        ffmpeg.garbage = "not a picture".getBytes(StandardCharsets.US_ASCII);
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(job::coarseFrames).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg wrote a frame that is not a greyscale picture: "
                    + "Only binary PGM (P5) is supported, found not")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aDenseDecodeThatWroteNoFramesIsAReadFailure() {
        ffmpeg.denseCount = 0;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.denseFrames(300_000)).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg wrote no frames around 300.000");
        }
    }

    @Test
    void aDenseDecodeThatStoppedShortKeepsTheFramesItWroteWithTheirOffsets() {
        ffmpeg.denseCount = 3;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            List<DenseFrame> frames = job.denseFrames(300_000);

            assertThat(frames).extracting(DenseFrame::offsetMs).containsExactly(290_000L, 290_500L, 291_000L);
        }
    }

    @Test
    void aSingleFrameJobNeverLeavesAStaleFrameBehindForTheNextSeek() {
        List<Long> sizes = new ArrayList<>();
        CommandRunner counting = (command, timeout) -> {
            Path output = Path.of(command.getLast());
            sizes.add(Files.exists(output) ? 1L : 0L);
            return ffmpeg.run(command, timeout);
        };
        try (Job job = new CreditsFrameReader(counting, "ffmpeg", 0, cache.toString())
            .open(FILM, 600_000, () -> { })) {
            job.coarseFrames();
        }

        assertThat(sizes).containsOnly(0L);
    }

    // ---- Colour frames -------------------------------------------------------------------------------------------

    @Test
    void theRgbFrameCommandIsTheGreyCommandWithRgb24AndAPpmFile() {
        List<String> command = CreditsFrameReader.frameCommand(10, "ffmpeg", FILM, 123_500, Path.of("/tmp/job/f.ppm"),
            "rgb24");

        assertThat(command).containsExactly("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
            "-protocol_whitelist", "file", "-threads", "1", "-ss", "123.500", "-i", "/library/Film (2000).mkv",
            "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn", "-vf",
            "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2,format=rgb24", "-f", "image2", "-y",
            "/tmp/job/f.ppm");
    }

    @Test
    void theRgbDenseCommandIsTheGreyCommandWithRgb24AndPpmFiles() {
        List<String> command = CreditsFrameReader.denseCommand(10, "ffmpeg", FILM, new DenseRange(5_000_000, 300_000),
            Path.of("/tmp/job/r_%05d.ppm"), "rgb24");

        assertThat(command).containsExactly("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
            "-protocol_whitelist", "file", "-threads", "1", "-ss", "5000.000", "-i", "/library/Film (2000).mkv",
            "-map", "0:v:0", "-t", "300.000", "-frames:v", "600", "-an", "-sn", "-dn", "-vf",
            "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2,fps=2,format=rgb24", "-start_number", "0",
            "-f", "image2", "-y", "/tmp/job/r_%05d.ppm");
    }

    @Test
    void theRgbSeekIsTheExactCommandInsideTheJobsDirectoryAndReadsThePpm() {
        ffmpeg.rgbAt = ms -> FakeFfmpeg.flat(200, 100, 50);
        RgbFrame frame;
        Path dir;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            frame = job.rgbFrame(123_500);
            dir = onlyJobDirectory();
            // The file is deleted once read, so the next seek can never meet a stale one.
            assertThat(dir).isEmptyDirectory();
        }

        assertThat(ffmpeg.commands).containsExactly(List.of("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner",
            "-v", "error", "-protocol_whitelist", "file", "-threads", "1", "-ss", "123.500", "-i",
            "/library/Film (2000).mkv", "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn", "-vf",
            "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2,format=rgb24", "-f", "image2", "-y",
            dir.resolve("frame.ppm").toString()));
        assertThat(ffmpeg.timeouts).containsExactly(CreditsFrameReader.SEEK_TIMEOUT);
        assertThat(frame.width()).isEqualTo(4);
        assertThat(frame.height()).isEqualTo(2);
        assertThat(frame.rgb(3, 1)).isEqualTo(0xC86432);
    }

    @Test
    void theRgbDenseRangeIsOneSequentialDecodeWithOffsetsFromTheStartAtHalfSecondSteps() {
        ffmpeg.rgbAt = ms -> FakeFfmpeg.flat((int) (ms / 500 % 256), 0, 0);
        List<TimedRgb> frames;
        Path dir;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            frames = job.rgbDense(290_000, 300_000);
            dir = onlyJobDirectory();
            assertThat(dir).isEmptyDirectory();
        }

        assertThat(ffmpeg.commands).containsExactly(List.of("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner",
            "-v", "error", "-protocol_whitelist", "file", "-threads", "1", "-ss", "290.000", "-i",
            "/library/Film (2000).mkv", "-map", "0:v:0", "-t", "300.000", "-frames:v", "600", "-an", "-sn", "-dn",
            "-vf", "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2,fps=2,format=rgb24", "-start_number", "0",
            "-f", "image2", "-y", dir.resolve("r_%05d.ppm").toString()));
        assertThat(ffmpeg.timeouts).containsExactly(CreditsFrameReader.DENSE_TIMEOUT);
        assertThat(frames).hasSize(600);
        assertThat(frames.getFirst().offsetMs()).isEqualTo(290_000);
        assertThat(frames.get(1).offsetMs()).isEqualTo(290_500);
        assertThat(frames.getLast().offsetMs()).isEqualTo(589_500);
        // Each frame is the picture ffmpeg wrote for that index, so offset and picture stay together.
        assertThat(frames.get(7).frame().rgb(0, 0)).isEqualTo((int) (293_500 / 500 % 256) << 16);
    }

    @Test
    void theRgbDenseRangeIsClampedToTheFile() {
        List<TimedRgb> before;
        List<TimedRgb> after;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            before = job.rgbDense(-5_000, 20_000);
            after = job.rgbDense(590_000, 60_000);
        }

        assertThat(before).hasSize(30);
        assertThat(before.getFirst().offsetMs()).isZero();
        assertThat(before.getLast().offsetMs()).isEqualTo(14_500);
        assertThat(after).hasSize(20);
        assertThat(after.getFirst().offsetMs()).isEqualTo(590_000);
        assertThat(after.getLast().offsetMs()).isEqualTo(599_500);
        assertThat(ffmpeg.commands).extracting(c -> c.get(c.indexOf("-t") + 1)).containsExactly("15.000", "10.000");
        assertThat(ffmpeg.commands).extracting(c -> c.get(c.indexOf("-frames:v") + 1)).containsExactly("30", "20");
    }

    @Test
    void anRgbDenseRangeThatEndsBeforeTheFileStartsIsRefusedAndRunsNothing() {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbDense(-9_000, 5_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense range from -9000 ms for 5000 ms ends before the file starts");
            assertThatThrownBy(() -> job.rgbDense(-5_000, 5_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense range from -5000 ms for 5000 ms ends before the file starts");
            assertThatThrownBy(() -> job.rgbDense(Long.MIN_VALUE, 300_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense range from -9223372036854775808 ms for 300000 ms ends before the file starts");
        }

        assertThat(ffmpeg.commands).isEmpty();
    }

    @Test
    void anRgbDenseRangeThatStartsAtOrPastTheEndOfTheFileIsRefusedAndRunsNothing() {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbDense(700_000, 5_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense range at 700000 ms starts at or past the end of the file (600000 ms)");
            assertThatThrownBy(() -> job.rgbDense(600_000, 5_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense range at 600000 ms starts at or past the end of the file (600000 ms)");
            assertThatThrownBy(() -> job.rgbDense(Long.MAX_VALUE, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense range at 9223372036854775807 ms starts at or past the end of the file "
                    + "(600000 ms)");
        }

        assertThat(ffmpeg.commands).isEmpty();
    }

    @Test
    void anRgbDenseRangeOfOneMillisecondAtTheEdgesIsAllowed() {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThat(job.rgbDense(599_999, 1)).hasSize(1);
            assertThat(job.rgbDense(-4_999, 5_000)).hasSize(1);
        }
    }

    @Test
    void anRgbDenseLengthThatIsNotOneToFiveMinutesIsRefused() {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbDense(0, -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense length -1 ms must be 1 to 300000 ms");
            assertThatThrownBy(() -> job.rgbDense(100_000, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense length 0 ms must be 1 to 300000 ms");
            assertThatThrownBy(() -> job.rgbDense(0, 300_001)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The dense length 300001 ms must be 1 to 300000 ms");
            assertThat(job.rgbDense(0, 300_000)).hasSize(600);
        }

        assertThat(ffmpeg.commands).hasSize(1);
    }

    @Test
    void colourReadsRefuseAJobWhoseDurationIsNotAUsableLengthAndRunNothing() {
        AtomicInteger checks = new AtomicInteger();
        long tooLong = Long.MAX_VALUE - CreditsFrameReader.DENSE_SPAN_MS + 1;
        for (long duration : new long[] {0, -1}) {
            try (Job job = reader().open(FILM, duration, checks::incrementAndGet)) {
                assertThatThrownBy(() -> job.rgbFrame(0)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The file's duration " + duration + " ms is not a usable length");
                assertThatThrownBy(() -> job.rgbDense(0, 1_000)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The file's duration " + duration + " ms is not a usable length");
            }
        }
        try (Job job = reader().open(FILM, tooLong, checks::incrementAndGet)) {
            assertThatThrownBy(() -> job.rgbFrame(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The file's duration " + tooLong + " ms is not a usable length");
            assertThatThrownBy(() -> job.rgbDense(0, 1_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The file's duration " + tooLong + " ms is not a usable length");
        }

        assertThat(ffmpeg.commands).isEmpty();
        assertThat(checks).hasValue(0);
    }

    @Test
    void theLargestUsableDurationStillWorksAndTheGreyReadsDoNotCheckIt() {
        long largest = Long.MAX_VALUE - CreditsFrameReader.DENSE_SPAN_MS;
        try (Job job = reader().open(FILM, largest, () -> { })) {
            assertThat(job.rgbFrame(1_000)).isNotNull();
        }
        try (Job job = reader().open(FILM, 0, () -> { })) {
            assertThat(job.activeAreaFrames()).hasSize(5);
        }
    }

    @Test
    void anRgbSeekOutsideTheFileIsRefusedAndRunsNothing() {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbFrame(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The frame offset -1 ms is outside the file (0 to 599999 ms)");
            assertThatThrownBy(() -> job.rgbFrame(600_000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The frame offset 600000 ms is outside the file (0 to 599999 ms)");
            assertThat(ffmpeg.commands).isEmpty();

            assertThat(job.rgbFrame(0)).isNotNull();
            assertThat(job.rgbFrame(599_999)).isNotNull();
        }

        assertThat(ffmpeg.commands).extracting(c -> c.get(c.indexOf("-ss") + 1))
            .containsExactly("0.000", "599.999");
    }

    @Test
    void framesLeftByAFailedRgbDenseCallAreNotReturnedByTheNextOne() {
        ffmpeg.failOnCall = 1;
        ffmpeg.writeBeforeFailing = true;
        ffmpeg.denseCount = 3;
        ffmpeg.corruptDenseIndex = 0;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            // ffmpeg wrote r_00000 to r_00002 and exited with an error (and the first one is unreadable).
            assertThatThrownBy(() -> job.rgbDense(100_000, 5_000)).isInstanceOf(ReadException.class);
            Path dir = onlyJobDirectory();
            assertThat(dir.resolve("r_00001.ppm")).exists();

            ffmpeg.failOnCall = 0;
            ffmpeg.denseCount = 1;
            ffmpeg.corruptDenseIndex = -1;
            ffmpeg.writeBeforeFailing = false;
            List<TimedRgb> frames = job.rgbDense(200_000, 5_000);

            assertThat(frames).extracting(TimedRgb::offsetMs).containsExactly(200_000L);
        }
    }

    @Test
    void framesLeftAfterAnUnreadableSecondFrameAreNotReturnedByTheNextCall() {
        ffmpeg.denseCount = 3;
        ffmpeg.corruptDenseIndex = 1;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbDense(100_000, 5_000)).isInstanceOf(ReadException.class);
            assertThat(onlyJobDirectory().resolve("r_00002.ppm")).exists();

            ffmpeg.denseCount = 2;
            ffmpeg.corruptDenseIndex = -1;
            List<TimedRgb> frames = job.rgbDense(200_000, 5_000);

            assertThat(frames).extracting(TimedRgb::offsetMs).containsExactly(200_000L, 200_500L);
        }
    }

    @Test
    void aFrameLeftByAFailedRgbSeekIsNotReadByTheNextOne() {
        ffmpeg.failOnCall = 1;
        ffmpeg.writeBeforeFailing = true;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbFrame(1_000)).isInstanceOf(ReadException.class);
            assertThat(onlyJobDirectory().resolve("frame.ppm")).exists();

            ffmpeg.writeNothing = true;
            assertThatThrownBy(() -> job.rgbFrame(2_000)).isInstanceOf(ReadException.class)
                .hasMessageStartingWith("ffmpeg wrote no readable frame");
        }
    }

    @Test
    void anOldFileThatCannotBeRemovedIsAReadFailure() throws IOException {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            Path dir = onlyJobDirectory();
            Files.createDirectories(dir.resolve("frame.ppm").resolve("inside"));
            Files.createDirectories(dir.resolve("r_00000.ppm").resolve("inside"));

            assertThatThrownBy(() -> job.rgbFrame(1_000)).isInstanceOf(ReadException.class)
                .hasMessageStartingWith("An old frame could not be removed");
            assertThatThrownBy(() -> job.rgbDense(1_000, 5_000)).isInstanceOf(ReadException.class)
                .hasMessageStartingWith("Old frames could not be removed");
        }

        assertThat(ffmpeg.commands).isEmpty();
    }

    @Test
    void aJobOpenedWithoutNicenessRunsEveryCommandWithoutNice() {
        try (Job job = reader().open(FILM, 600_000, () -> { }, 0)) {
            job.rgbFrame(1_000);
            job.rgbDense(2_000, 1_000);
            job.activeAreaFrames();
            job.denseFrames(300_000);
        }

        assertThat(ffmpeg.commands).hasSize(8).allSatisfy(c -> {
            assertThat(c).startsWith("ffmpeg", "-nostdin").doesNotContain("nice");
        });
    }

    @Test
    void aJobOpenedWithItsOwnNicenessUsesItForEveryCommandInsteadOfTheConfiguredOne() {
        try (Job job = reader().open(FILM, 600_000, () -> { }, 19)) {
            job.rgbFrame(1_000);
            job.rgbDense(2_000, 1_000);
            job.activeAreaFrames();
            job.denseFrames(300_000);
        }

        assertThat(ffmpeg.commands).hasSize(8).allSatisfy(c -> {
            assertThat(c).startsWith("nice", "-n", "19", "ffmpeg");
        });
    }

    @Test
    void openingWithoutNicenessUsesTheConfiguredOne() {
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            job.rgbFrame(1_000);
        }

        assertThat(ffmpeg.commands.getFirst()).startsWith("nice", "-n", "10", "ffmpeg");
    }

    @Test
    void aReaderConfiguredWithoutNicenessStillGivesAJobItsOwn() {
        CreditsFrameReader plain = new CreditsFrameReader(ffmpeg, "ffmpeg", 0, cache.toString());
        try (Job job = plain.open(FILM, 600_000, () -> { }, 5)) {
            job.rgbFrame(1_000);
        }
        try (Job job = plain.open(FILM, 600_000, () -> { })) {
            job.rgbFrame(1_000);
        }

        assertThat(ffmpeg.commands.get(0)).startsWith("nice", "-n", "5", "ffmpeg");
        assertThat(ffmpeg.commands.get(1)).startsWith("ffmpeg");
    }

    @Test
    void theCheckpointRunsBeforeEveryRgbCommand() {
        AtomicInteger checks = new AtomicInteger();
        try (Job job = reader().open(FILM, 600_000, () -> {
            assertThat(ffmpeg.commands).hasSize(checks.getAndIncrement());
        })) {
            job.rgbFrame(1_000);
            job.rgbDense(2_000, 1_000);
        }

        assertThat(checks).hasValue(2);
    }

    @Test
    void aCheckpointThatStopsTheJobStopsRgbReadsBeforeFfmpegRuns() {
        try (Job job = reader().open(FILM, 600_000, () -> {
            throw new IllegalStateException("playback needs the machine");
        })) {
            assertThatThrownBy(() -> job.rgbFrame(1_000)).hasMessage("playback needs the machine");
            assertThatThrownBy(() -> job.rgbDense(1_000, 5_000)).hasMessage("playback needs the machine");
        }

        assertThat(ffmpeg.commands).isEmpty();
    }

    @Test
    void anRgbSeekThatFailsOrTimesOutAbandonsTheFile() {
        ffmpeg.failOnCall = 1;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbFrame(123_500)).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg exited with status 1 reading the frame at 123.500");
        }

        ffmpeg.commands.clear();
        ffmpeg.failOnCall = 1;
        ffmpeg.failure = new CommandRunner.Result(-1, "", true);
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbDense(100_000, 5_000)).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg timed out reading the frames around 100.000");
        }
    }

    @Test
    void anRgbSeekThatWroteNoFrameIsAReadFailureNotAStaleFrame() {
        ffmpeg.writeNothing = true;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbFrame(1_000)).isInstanceOf(ReadException.class)
                .hasMessageStartingWith("ffmpeg wrote no readable frame")
                .hasCauseInstanceOf(IOException.class);
        }
    }

    @Test
    void anRgbDenseDecodeThatWroteNoFramesIsAReadFailure() {
        ffmpeg.denseCount = 0;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbDense(300_000, 5_000)).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg wrote no frames around 300.000");
        }
    }

    @Test
    void anRgbDenseDecodeThatStoppedShortKeepsTheFramesItWroteWithTheirOffsets() {
        ffmpeg.denseCount = 3;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            List<TimedRgb> frames = job.rgbDense(300_000, 60_000);

            assertThat(frames).extracting(TimedRgb::offsetMs).containsExactly(300_000L, 300_500L, 301_000L);
        }
    }

    @Test
    void aFrameThatIsNotAPpmIsAReadFailure() {
        ffmpeg.garbage = "P5\n1 1\n255\n.".getBytes(StandardCharsets.US_ASCII);
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThatThrownBy(() -> job.rgbFrame(1_000)).isInstanceOf(ReadException.class)
                .hasMessage("ffmpeg wrote a frame that is not a colour picture: "
                    + "Only binary PPM (P6) is supported, found P5")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- Directories ---------------------------------------------------------------------------------------------

    @Test
    void everyJobGetsAFreshDirectoryUnderTheCacheDirectory() {
        CreditsFrameReader reader = reader();
        try (Job first = reader.open(FILM, 600_000, () -> { }); Job second = reader.open(FILM, 600_000, () -> { })) {
            first.activeAreaFrames();
            Path firstDir = ffmpeg.lastDirectory;
            second.activeAreaFrames();

            assertThat(ffmpeg.lastDirectory).isNotEqualTo(firstDir);
            assertThat(firstDir.getParent()).isEqualTo(cache.resolve("credits").toAbsolutePath().normalize());
        }
    }

    @Test
    void whenTheDirectoryCannotBeMadeOpeningFailsAsAReadFailure() throws IOException {
        Path file = Files.writeString(cache.resolve("not-a-directory"), "x");
        CreditsFrameReader broken = new CreditsFrameReader(ffmpeg, "ffmpeg", 10, file.toString());

        assertThatThrownBy(() -> broken.open(FILM, 600_000, () -> { })).isInstanceOf(ReadException.class)
            .hasMessageStartingWith("The frame directory could not be made")
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void leftoverDirectoriesFromAKilledProcessAreDeletedAtStartUp() throws IOException {
        Path left = Files.createDirectories(cache.resolve("credits").resolve("job-1"));
        Files.writeString(left.resolve("frame.pgm"), "x");
        Files.writeString(left.resolve("d_00001.pgm"), "x");

        reader().deleteLeftovers();

        assertThat(cache.resolve("credits")).doesNotExist();
        // The next job makes the directory again.
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            assertThat(job).isNotNull();
        }
    }

    @Test
    void clearingLeftoversWhenThereIsNoDirectoryDoesNothing() {
        reader().deleteLeftovers();

        assertThat(cache.resolve("credits")).doesNotExist();
    }

    @Test
    void aDirectoryThatCannotBeDeletedIsLoggedNotThrown() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root can delete anything");
        Path locked = Files.createDirectories(cache.resolve("credits").resolve("job-locked"));
        Files.writeString(locked.resolve("frame.pgm"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            reader().deleteLeftovers();

            assertThat(locked.resolve("frame.pgm")).exists();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void theCoarseOffsetsOfALongFileAreExactlyFiveSecondsApart() {
        List<Long> offsets = CreditsFrameReader.coarseOffsets(120 * MIN);

        assertThat(LongStream.range(1, offsets.size()).map(i -> offsets.get((int) i) - offsets.get((int) i - 1)))
            .containsOnly(5_000L);
    }

    private Path onlyJobDirectory() {
        try (var children = Files.list(cache.resolve("credits"))) {
            List<Path> dirs = children.toList();
            assertThat(dirs).hasSize(1);
            return dirs.getFirst();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
