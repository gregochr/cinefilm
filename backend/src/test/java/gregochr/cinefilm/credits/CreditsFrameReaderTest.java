package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import gregochr.cinefilm.credits.CreditsFrameReader.DenseRange;
import gregochr.cinefilm.credits.CreditsFrameReader.Job;
import gregochr.cinefilm.credits.CreditsFrameReader.ReadException;
import gregochr.cinefilm.credits.CreditsFrameReader.TimedFrame;
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
    void theDenseDecodeIsThreeMinutesCentredOnTheCandidate() {
        assertThat(CreditsFrameReader.denseRange(100 * MIN, 120 * MIN))
            .isEqualTo(new DenseRange(100 * MIN - 90_000, 180_000));
    }

    @Test
    void theDenseDecodeIsClampedAtTheStartOfTheFile() {
        assertThat(CreditsFrameReader.denseRange(30_000, 600_000)).isEqualTo(new DenseRange(0, 120_000));
        assertThat(CreditsFrameReader.denseRange(90_000, 600_000)).isEqualTo(new DenseRange(0, 180_000));
    }

    @Test
    void theDenseDecodeIsClampedAtTheEndOfTheFile() {
        assertThat(CreditsFrameReader.denseRange(590_000, 600_000)).isEqualTo(new DenseRange(500_000, 100_000));
        assertThat(CreditsFrameReader.denseRange(510_000, 600_000)).isEqualTo(new DenseRange(420_000, 180_000));
    }

    @Test
    void aFileShorterThanTheDenseSpanIsDecodedWhole() {
        assertThat(CreditsFrameReader.denseRange(30_000, 60_000)).isEqualTo(new DenseRange(0, 60_000));
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
        DenseRange range = new DenseRange(5_000_000, 180_000);

        List<String> command = CreditsFrameReader.denseCommand(10, "ffmpeg", FILM, range,
            Path.of("/tmp/job/d_%05d.pgm"));

        assertThat(command).containsExactly("nice", "-n", "10", "ffmpeg", "-nostdin", "-hide_banner", "-v", "error",
            "-protocol_whitelist", "file", "-threads", "1", "-ss", "5000.000", "-i", "/library/Film (2000).mkv",
            "-map", "0:v:0", "-t", "180.000", "-frames:v", "360", "-an", "-sn", "-dn", "-vf",
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
        assertThat(frames).hasSize(360);
        assertThat(frames.getFirst().offsetMs()).isEqualTo(210_000);
        assertThat(frames.get(1).offsetMs()).isEqualTo(210_500);
        assertThat(frames.getLast().offsetMs()).isEqualTo(389_500);
        // Each frame is the picture ffmpeg wrote for that index, so offset and picture stay together.
        assertThat(frames.get(7).frame().luma(0, 0)).isEqualTo((int) (213_500 / 500 % 256));
    }

    @Test
    void aClampedDenseDecodeStartsAtTheStartOfTheFile() {
        List<DenseFrame> frames;
        try (Job job = reader().open(FILM, 600_000, () -> { })) {
            frames = job.denseFrames(20_000);
        }

        assertThat(frames).hasSize(220);
        assertThat(frames.getFirst().offsetMs()).isZero();
        assertThat(frames.getLast().offsetMs()).isEqualTo(109_500);
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

            assertThat(frames).extracting(DenseFrame::offsetMs).containsExactly(210_000L, 210_500L, 211_000L);
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
