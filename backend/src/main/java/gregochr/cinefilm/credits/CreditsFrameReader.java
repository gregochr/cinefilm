package gregochr.cinefilm.credits;

import gregochr.cinefilm.library.CommandRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the frames the credits analysis needs from a film file with {@code ffmpeg}: greyscale PGM files written into a
 * fresh temporary directory per job, scaled to 480 pixels wide after un-squeezing a non-square pixel aspect ratio (as
 * {@code StillCommand} does). Nothing binary is read from standard output. Every call is an argument list with
 * {@code -nostdin}, {@code -protocol_whitelist file}, one thread, the absolute path last before the output, under
 * {@code nice}, and has a timeout after which the process is killed. A job abandons the whole file on the first
 * failure or timeout, and its directory is always deleted when the job is closed.
 *
 * <p>Three kinds of read, all single-frame input seeks except the last (a seek reads about one group of pictures, so
 * the window costs a little over SMB instead of a sequential decode of the whole ending): five frames for the active
 * area, one frame every {@link CreditsTuning#SAMPLE_MS} across the window (the coarse pass), and one sequential decode
 * at two frames a second from just before the candidate for five minutes (the dense pass).
 */
@Component
public class CreditsFrameReader {

    private static final Logger LOG = LoggerFactory.getLogger(CreditsFrameReader.class);

    /** How long one single-frame seek may run: seconds normally, over a slow share a good deal more. */
    static final Duration SEEK_TIMEOUT = Duration.ofSeconds(90);
    /** How long the dense decode (600 frames from a seek) may run. */
    static final Duration DENSE_TIMEOUT = Duration.ofMinutes(9);
    /** The width every frame is scaled to. */
    public static final int OUTPUT_WIDTH = 480;
    /** One coarse frame is taken this often across the window. */
    public static final long COARSE_STEP_MS = CreditsTuning.SAMPLE_MS;
    /** How many frames the active area is measured on. */
    static final int ACTIVE_SAMPLES = 5;
    /** The window is at most this long... */
    static final long WINDOW_MAX_MS = 35 * 60_000L;
    /** ...and at most this percent of the file. */
    static final int WINDOW_PERCENT = 40;
    /** The dense pass starts this long before the candidate. */
    static final long DENSE_LEAD_MS = 10_000;
    /** The dense pass covers this long, from {@link #DENSE_LEAD_MS} before the candidate. */
    static final long DENSE_SPAN_MS = 300_000;
    /** The dense pass takes this many frames a second. */
    public static final int DENSE_FPS = 2;
    /** One dense frame lasts this long. */
    static final long DENSE_FRAME_MS = 1000 / DENSE_FPS;
    /** A seek never goes closer than this to the end of the file, where there may be no frame left to decode. */
    static final long END_MARGIN_MS = 1_000;

    /** The ffmpeg filter every frame goes through: un-squeeze a non-square pixel aspect, then scale to the width. */
    public static final String FILTER = "scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=" + OUTPUT_WIDTH + ":-2";
    private static final String FRAME_FILE = "frame.pgm";
    private static final String DENSE_PATTERN = "d_%05d.pgm";
    private static final String RGB_FRAME_FILE = "frame.ppm";
    private static final String RGB_DENSE_PATTERN = "r_%05d.ppm";
    /** The pixel format of the grey frames the active area and the product's rules read (limited range). */
    public static final String GREY_FORMAT = "gray";
    /** The pixel format of the colour frames (full range). */
    public static final String RGB_FORMAT = "rgb24";

    /** A frame and where in the file it was taken. */
    public record TimedFrame(long offsetMs, GreyFrame frame) {
    }

    /** A colour frame and where in the file it was taken. */
    public record TimedRgb(long offsetMs, RgbFrame frame) {
    }

    /** A read that could not be completed: ffmpeg is missing, failed, timed out or wrote something unreadable. */
    public static class ReadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with a plain message. */
        public ReadException(String message) {
            super(message);
        }

        /** Creates the exception with a plain message and its cause. */
        public ReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Where the dense pass starts and how long it lasts, both clamped to the file. */
    record DenseRange(long startMs, long lengthMs) {
    }

    private final CommandRunner runner;
    private final String ffmpeg;
    private final int niceness;
    private final Path workDir;

    /**
     * Creates the reader; the binary is {@code cinefilm.tools.ffmpeg}, the niceness {@code cinefilm.transcode.nice}
     * (0 or less runs without {@code nice}), and the jobs' directories are made under {@code cinefilm.cache.dir}.
     */
    public CreditsFrameReader(CommandRunner runner, @Value("${cinefilm.tools.ffmpeg:ffmpeg}") String ffmpeg,
                              @Value("${cinefilm.transcode.nice:10}") int niceness,
                              @Value("${cinefilm.cache.dir}") String cacheDir) {
        this.runner = runner;
        this.ffmpeg = ffmpeg;
        this.niceness = niceness;
        this.workDir = Path.of(cacheDir).toAbsolutePath().normalize().resolve("credits");
    }

    // ---- Pure arithmetic and commands ----------------------------------------------------------------------------

    /** The length of the window: the last 35 minutes of the file, or 40% of it when that is less. */
    public static long windowMs(long durationMs) {
        return Math.min(WINDOW_MAX_MS, durationMs * WINDOW_PERCENT / 100);
    }

    /** The offsets of the five frames the active area is measured on: 30%, 37.5%, 45%, 52.5% and 60% of the file. */
    public static List<Long> activeOffsets(long durationMs) {
        List<Long> offsets = new ArrayList<>();
        for (int i = 0; i < ACTIVE_SAMPLES; i++) {
            offsets.add(durationMs * (300 + 75L * i) / 1000);
        }
        return offsets;
    }

    /**
     * The offsets of the coarse frames: from the start of the window, one every {@link CreditsTuning#SAMPLE_MS}, none
     * closer than {@value #END_MARGIN_MS} ms to the end.
     */
    public static List<Long> coarseOffsets(long durationMs) {
        List<Long> offsets = new ArrayList<>();
        for (long at = durationMs - windowMs(durationMs); at <= durationMs - END_MARGIN_MS;
             at += COARSE_STEP_MS) {
            offsets.add(at);
        }
        return offsets;
    }

    /** The dense pass: 5 minutes from 10 seconds before the candidate, clamped to the file at both ends. */
    static DenseRange denseRange(long candidateMs, long durationMs) {
        long start = Math.max(0, candidateMs - DENSE_LEAD_MS);
        long end = Math.min(durationMs, candidateMs - DENSE_LEAD_MS + DENSE_SPAN_MS);
        return new DenseRange(start, Math.max(0, end - start));
    }

    /** A time as {@code seconds.millis}, which is what {@code -ss} takes. */
    static String seekTime(long ms) {
        return String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000);
    }

    /**
     * The command that writes the one frame at a seek time.
     *
     * @param niceness the niceness to run under; 0 or less runs without {@code nice}
     * @param binary the ffmpeg executable
     * @param source the media file (absolute)
     * @param seekMs where in the file to take the frame
     * @param output the PGM file to write
     * @return the argument list
     */
    static List<String> frameCommand(int niceness, String binary, Path source, long seekMs, Path output) {
        return frameCommand(niceness, binary, source, seekMs, output, GREY_FORMAT);
    }

    /** As {@link #frameCommand(int, String, Path, long, Path)} with the pixel format ({@code gray}, {@code rgb24}). */
    static List<String> frameCommand(int niceness, String binary, Path source, long seekMs, Path output,
                                     String pixelFormat) {
        List<String> command = prefix(niceness, binary);
        command.addAll(List.of("-ss", seekTime(seekMs), "-i", source.toAbsolutePath().toString(), "-map", "0:v:0",
            "-frames:v", "1", "-an", "-sn", "-dn", "-vf", FILTER + ",format=" + pixelFormat, "-f", "image2", "-y",
            output.toString()));
        return List.copyOf(command);
    }

    /**
     * The command that decodes a stretch of the file at two frames a second into numbered PGM files.
     *
     * @param niceness the niceness to run under; 0 or less runs without {@code nice}
     * @param binary the ffmpeg executable
     * @param source the media file (absolute)
     * @param range where to start and how long to decode
     * @param pattern the output file pattern ({@code d_%05d.pgm} under the job's directory)
     * @return the argument list
     */
    static List<String> denseCommand(int niceness, String binary, Path source, DenseRange range, Path pattern) {
        return denseCommand(niceness, binary, source, range, pattern, GREY_FORMAT);
    }

    /** As {@link #denseCommand(int, String, Path, DenseRange, Path)} with the pixel format. */
    static List<String> denseCommand(int niceness, String binary, Path source, DenseRange range, Path pattern,
                                     String pixelFormat) {
        long frames = (range.lengthMs() + DENSE_FRAME_MS - 1) / DENSE_FRAME_MS;
        List<String> command = prefix(niceness, binary);
        command.addAll(List.of("-ss", seekTime(range.startMs()), "-i", source.toAbsolutePath().toString(),
            "-map", "0:v:0", "-t", seekTime(range.lengthMs()), "-frames:v", Long.toString(frames), "-an", "-sn", "-dn",
            "-vf", FILTER + ",fps=" + DENSE_FPS + ",format=" + pixelFormat,
            "-start_number", "0", "-f", "image2", "-y",
            pattern.toString()));
        return List.copyOf(command);
    }

    private static List<String> prefix(int niceness, String binary) {
        List<String> command = new ArrayList<>();
        if (niceness > 0) {
            command.addAll(List.of("nice", "-n", String.valueOf(niceness)));
        }
        command.addAll(List.of(binary, "-nostdin", "-hide_banner", "-v", "error", "-protocol_whitelist", "file",
            "-threads", "1"));
        return command;
    }

    // ---- Jobs ----------------------------------------------------------------------------------------------------

    /**
     * Opens a job for one file: makes its temporary directory. Close it (try-with-resources) to delete the directory.
     *
     * @param file the media file (an absolute path under the library root)
     * @param durationMs the file's length on its timeline
     * @param checkpoint run before every ffmpeg call; it throws to stop the job (playback needs the machine)
     * @return the job
     * @throws ReadException when the directory cannot be made
     */
    public Job open(Path file, long durationMs, Runnable checkpoint) {
        return open(file, durationMs, checkpoint, niceness);
    }

    /**
     * As {@link #open(Path, long, Runnable)} with the job's own niceness instead of {@code cinefilm.transcode.nice}.
     *
     * @param file the media file (an absolute path under the library root)
     * @param durationMs the file's length on its timeline
     * @param checkpoint run before every ffmpeg call; it throws to stop the job
     * @param niceness the niceness every command of the job runs under; 0 or less runs without {@code nice}
     * @return the job
     * @throws ReadException when the directory cannot be made
     */
    public Job open(Path file, long durationMs, Runnable checkpoint, int niceness) {
        try {
            Files.createDirectories(workDir);
            return new Job(file, durationMs, checkpoint, niceness, Files.createTempDirectory(workDir, "job-"));
        } catch (IOException e) {
            throw new ReadException("The frame directory could not be made: " + e.getMessage(), e);
        }
    }

    /**
     * Removes the directories a killed process left behind (a job's own directory is always removed when it closes).
     * Call it at start-up, before any job runs.
     */
    public void deleteLeftovers() {
        deleteTree(workDir);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not remove {}: {}", root, e.getMessage());
        }
    }

    /** The frames of one file. Not shared between threads. */
    public final class Job implements AutoCloseable {

        private final Path source;
        private final long durationMs;
        private final Runnable checkpoint;
        private final int niceness;
        private final Path dir;

        private Job(Path source, long durationMs, Runnable checkpoint, int niceness, Path dir) {
            this.source = source;
            this.durationMs = durationMs;
            this.checkpoint = checkpoint;
            this.niceness = niceness;
            this.dir = dir;
        }

        /** The five frames the active area is measured on, taken between 30% and 60% of the file. */
        public List<GreyFrame> activeAreaFrames() {
            List<GreyFrame> frames = new ArrayList<>();
            for (long offset : activeOffsets(durationMs)) {
                frames.add(grab(offset));
            }
            return frames;
        }

        /** One frame every 5 seconds across the window; each frame's offset is its seek time. */
        public List<TimedFrame> coarseFrames() {
            List<TimedFrame> frames = new ArrayList<>();
            for (long offset : coarseOffsets(durationMs)) {
                frames.add(new TimedFrame(offset, grab(offset)));
            }
            return frames;
        }

        /**
         * Two frames a second for 5 minutes from 10 seconds before the candidate, clamped to the file. A frame's
         * offset is the seek time plus its index times 500 ms, never a count of decoded frames.
         */
        public List<DenseFrame> denseFrames(long candidateMs) {
            DenseRange range = denseRange(candidateMs, durationMs);
            checkpoint.run();
            run(denseCommand(niceness, ffmpeg, source, range, dir.resolve(DENSE_PATTERN)), DENSE_TIMEOUT,
                "the frames around " + seekTime(candidateMs));
            List<DenseFrame> frames = new ArrayList<>();
            for (Path file = dense(0); Files.exists(file); file = dense(frames.size())) {
                frames.add(new DenseFrame(range.startMs() + frames.size() * DENSE_FRAME_MS, read(file)));
            }
            if (frames.isEmpty()) {
                throw new ReadException("ffmpeg wrote no frames around " + seekTime(candidateMs));
            }
            return frames;
        }

        /**
         * One colour frame at 480 pixels wide, by a single-frame seek. Unlike the grey reads, whose offsets come from
         * this class's own arithmetic, the offset is caller input and is checked.
         *
         * @param offsetMs where in the file to take it, 0 up to but not including the file's length
         * @return the frame
         * @throws IllegalArgumentException when the offset is negative or not before the end of the file, or the job's
         *     duration is not 1 to {@code Long.MAX_VALUE - DENSE_SPAN_MS}
         * @throws ReadException when ffmpeg fails, times out or writes nothing readable
         */
        public RgbFrame rgbFrame(long offsetMs) {
            requireUsableDuration();
            if (offsetMs < 0 || offsetMs >= durationMs) {
                throw new IllegalArgumentException(
                    "The frame offset " + offsetMs + " ms is outside the file (0 to " + (durationMs - 1) + " ms)");
            }
            checkpoint.run();
            Path output = dir.resolve(RGB_FRAME_FILE);
            deleteFile(output);
            run(frameCommand(niceness, ffmpeg, source, offsetMs, output, RGB_FORMAT), SEEK_TIMEOUT,
                "the frame at " + seekTime(offsetMs));
            return readRgb(output);
        }

        /**
         * Colour frames at two a second from one sequential decode, the same command shape as the grey dense pass.
         * The range is clamped to {@code [0, duration]}. A frame's offset is the start plus its index times 500 ms.
         * Any {@code r_*.ppm} left in the job's directory by an earlier failed call is deleted first, so a call returns
         * only its own frames.
         *
         * @param startMs where the decode starts (before 0 is clamped to 0)
         * @param lengthMs how long it lasts, 1 to {@value #DENSE_SPAN_MS}
         * @return the frames, in order; never empty
         * @throws IllegalArgumentException when the length is not 1 to {@value #DENSE_SPAN_MS}, the start is at or
         *     past the end of the file, the range ends before the file starts, or the job's duration is not 1 to
         *     {@code Long.MAX_VALUE - DENSE_SPAN_MS}
         * @throws ReadException when ffmpeg fails, times out or writes no frames
         */
        public List<TimedRgb> rgbDense(long startMs, long lengthMs) {
            requireUsableDuration();
            if (lengthMs < 1 || lengthMs > DENSE_SPAN_MS) {
                throw new IllegalArgumentException(
                    "The dense length " + lengthMs + " ms must be 1 to " + DENSE_SPAN_MS + " ms");
            }
            if (startMs >= durationMs) {
                throw new IllegalArgumentException("The dense range at " + startMs
                    + " ms starts at or past the end of the file (" + durationMs + " ms)");
            }
            // No overflow: the duration is at most Long.MAX_VALUE - DENSE_SPAN_MS (requireUsableDuration), the start
            // is below it and the length is at most DENSE_SPAN_MS, so the sum stays below Long.MAX_VALUE; for a
            // negative start it is a negative number plus a small one.
            long sum = startMs + lengthMs;
            if (sum <= 0) {
                throw new IllegalArgumentException("The dense range from " + startMs + " ms for " + lengthMs
                    + " ms ends before the file starts");
            }
            long start = Math.max(0, startMs);
            long end = Math.min(durationMs, sum);
            DenseRange range = new DenseRange(start, end - start);
            checkpoint.run();
            deleteStaleDense();
            run(denseCommand(niceness, ffmpeg, source, range, dir.resolve(RGB_DENSE_PATTERN), RGB_FORMAT),
                DENSE_TIMEOUT, "the frames around " + seekTime(start));
            List<TimedRgb> frames = new ArrayList<>();
            for (Path file = dir.resolve(RGB_DENSE_PATTERN.formatted(0)); Files.exists(file);
                 file = dir.resolve(RGB_DENSE_PATTERN.formatted(frames.size()))) {
                frames.add(new TimedRgb(start + frames.size() * DENSE_FRAME_MS, readRgb(file)));
            }
            if (frames.isEmpty()) {
                throw new ReadException("ffmpeg wrote no frames around " + seekTime(start));
            }
            return frames;
        }

        /** The colour reads do arithmetic on the duration; the grey reads are unchanged and do not call this. */
        private void requireUsableDuration() {
            if (durationMs <= 0 || durationMs > Long.MAX_VALUE - DENSE_SPAN_MS) {
                throw new IllegalArgumentException(
                    "The file's duration " + durationMs + " ms is not a usable length");
            }
        }

        private void deleteStaleDense() {
            try (DirectoryStream<Path> stale = Files.newDirectoryStream(dir, "r_*.ppm")) {
                for (Path file : stale) {
                    Files.delete(file);
                }
            } catch (IOException e) {
                throw new ReadException("Old frames could not be removed: " + e.getMessage(), e);
            }
        }

        private void deleteFile(Path file) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new ReadException("An old frame could not be removed: " + e.getMessage(), e);
            }
        }

        /** The dense file of an index, as the pattern numbers them. */
        private Path dense(int index) {
            return dir.resolve(DENSE_PATTERN.formatted(index));
        }

        /** One frame. Every seek writes the same file name and {@link #read} removes it, so none is ever stale. */
        private GreyFrame grab(long offsetMs) {
            checkpoint.run();
            Path output = dir.resolve(FRAME_FILE);
            run(frameCommand(niceness, ffmpeg, source, offsetMs, output), SEEK_TIMEOUT,
                "the frame at " + seekTime(offsetMs));
            return read(output);
        }

        private void run(List<String> command, Duration timeout, String what) {
            CommandRunner.Result result;
            try {
                result = runner.run(command, timeout);
            } catch (IOException e) {
                throw new ReadException("ffmpeg could not be run: " + e.getMessage(), e);
            }
            if (!result.succeeded()) {
                throw new ReadException(result.timedOut() ? "ffmpeg timed out reading " + what
                    : "ffmpeg exited with status " + result.exitCode() + " reading " + what);
            }
        }

        private GreyFrame read(Path pgm) {
            try {
                return PgmReader.read(take(pgm));
            } catch (IllegalArgumentException e) {
                throw new ReadException("ffmpeg wrote a frame that is not a greyscale picture: " + e.getMessage(), e);
            }
        }

        private RgbFrame readRgb(Path ppm) {
            try {
                return PpmReader.read(take(ppm));
            } catch (IllegalArgumentException e) {
                throw new ReadException("ffmpeg wrote a frame that is not a colour picture: " + e.getMessage(), e);
            }
        }

        /** The file's bytes; the file is deleted so that it is never read twice. */
        private byte[] take(Path file) {
            try {
                byte[] data = Files.readAllBytes(file);
                Files.delete(file);
                return data;
            } catch (IOException e) {
                throw new ReadException("ffmpeg wrote no readable frame: " + e.getMessage(), e);
            }
        }

        /** Deletes the job's directory and everything in it. */
        @Override
        public void close() {
            deleteTree(dir);
        }
    }
}
