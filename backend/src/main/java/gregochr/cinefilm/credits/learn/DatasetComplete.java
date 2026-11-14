package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code complete.json} of one file's frames, written last: its presence with the file's current fingerprint
 * is what says the directory holds every frame, so a killed or stopped run never leaves a directory that looks
 * finished. It holds numbers and tool versions only: no title and no path.
 *
 * @param format the layout of this file ({@value #FORMAT})
 * @param mediaFileId the media file
 * @param fingerprint the file's size and modification time when read
 * @param durationMs the file's length on its timeline
 * @param targetStartMs where Plex says the credits start
 * @param window the part of the file the coarse frames cover
 * @param frame the size of the frames as ffmpeg wrote them
 * @param activeArea the picture area, in that frame's coordinates
 * @param activeAreaOffsets where the five frames the area was measured on were taken
 * @param coarse the offsets of the coarse frames
 * @param dense the offsets of the dense frames
 * @param ffmpegVersion the first line of {@code ffmpeg -version}
 * @param javaVersion the runtime that encoded the pictures
 * @param gitCommit the build's commit
 * @param settings every setting the frames depend on
 * @param writtenAt when this file was written (ISO instant)
 */
public record DatasetComplete(int format, long mediaFileId, String fingerprint, long durationMs, long targetStartMs,
                              Window window, Frame frame, Area activeArea, List<Long> activeAreaOffsets,
                              List<Long> coarse, List<Long> dense, String ffmpegVersion, String javaVersion,
                              String gitCommit, Settings settings, String writtenAt) {

    /** The layout version this code writes and accepts. */
    public static final int FORMAT = 1;
    /** The file's name inside a file's directory. */
    public static final String FILE_NAME = "complete.json";

    private static final String TEMP_NAME = FILE_NAME + ".tmp";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The part of the file the coarse frames cover. */
    public record Window(long startMs, long endMs) {
    }

    /** A frame's size in pixels. */
    public record Frame(int width, int height) {
    }

    /** The picture rectangle inside a frame. */
    public record Area(int left, int top, int width, int height) {
    }

    /**
     * Everything the stored frames depend on, taken from the code that makes them.
     *
     * @param filter the ffmpeg filter every frame goes through
     * @param framePixelFormat the pixel format of the colour frames
     * @param areaPixelFormat the pixel format of the active area frames
     * @param outputWidth the width of a frame as ffmpeg writes it
     * @param greyWidth the width of a stored grey frame
     * @param colourSize the side of a stored colour frame
     * @param jpegQuality the quality of a stored colour frame
     * @param coarseStepMs the time between coarse frames
     * @param denseFps the dense frames a second
     * @param denseLeadMs how long before the target the dense frames start
     * @param denseLengthMs how long the dense frames last
     * @param niceness the niceness ffmpeg ran under
     */
    public record Settings(String filter, String framePixelFormat, String areaPixelFormat, int outputWidth,
                           int greyWidth, int colourSize, float jpegQuality, long coarseStepMs, int denseFps,
                           long denseLeadMs, long denseLengthMs, int niceness) {

        /**
         * The settings of the code as it is now.
         *
         * @param niceness the niceness the job runs ffmpeg under
         * @return the settings
         */
        public static Settings current(int niceness) {
            return new Settings(CreditsFrameReader.FILTER, CreditsFrameReader.RGB_FORMAT,
                CreditsFrameReader.GREY_FORMAT, CreditsFrameReader.OUTPUT_WIDTH, CreditsFrameReader.OUTPUT_WIDTH / 2,
                FramePrep.COLOUR_SIZE, FramePrep.JPEG_QUALITY, CreditsFrameReader.COARSE_STEP_MS,
                CreditsFrameReader.DENSE_FPS, DatasetFrames.DENSE_LEAD_MS, DatasetFrames.DENSE_LENGTH_MS, niceness);
        }
    }

    /**
     * The fingerprint in a directory's {@code complete.json}, when that file parses and has the current format.
     *
     * @param dir the file's directory
     * @return the fingerprint; empty when the file is missing, unparseable or of another format
     */
    public static Optional<String> fingerprint(Path dir) {
        try {
            JsonNode node = MAPPER.readTree(Files.readAllBytes(dir.resolve(FILE_NAME)));
            return node.path("format").asInt(-1) == FORMAT ? Optional.of(node.path("fingerprint").asString(""))
                : Optional.empty();
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        }
    }

    /**
     * A directory's {@code complete.json} as a whole, for the measurement tool (which reads frames it did not write
     * and so has no fingerprint to compare). Only a file that parses, has the current format and holds every part the
     * tool reads (window, frame, picture area, settings, and both offset lists without a null in them) is returned.
     *
     * @param dir the file's directory
     * @return the file; empty when it is missing, unparseable, of another format or lacks a part
     */
    public static Optional<DatasetComplete> read(Path dir) {
        try {
            DatasetComplete complete = MAPPER.readValue(Files.readAllBytes(dir.resolve(FILE_NAME)),
                DatasetComplete.class);
            return complete != null && complete.format() == FORMAT && complete.window() != null
                && complete.frame() != null && complete.activeArea() != null && complete.settings() != null
                && hasOffsets(complete.coarse()) && hasOffsets(complete.dense()) ? Optional.of(complete)
                : Optional.empty();
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        }
    }

    private static boolean hasOffsets(List<Long> offsets) {
        return offsets != null && offsets.stream().noneMatch(Objects::isNull);
    }

    /**
     * Whether a file's directory holds a finished, current read: a {@code complete.json} that parses, has the current
     * format and holds this fingerprint. Anything else (missing, unparseable, an old format, a stale fingerprint) is
     * not complete.
     *
     * @param dir the file's directory
     * @param fingerprint the file's current fingerprint
     * @return true when the directory is complete and current
     */
    public static boolean isCurrent(Path dir, String fingerprint) {
        return fingerprint(dir).filter(fingerprint::equals).isPresent();
    }

    /**
     * Writes the file as the last step of a directory: every frame file and directory is flushed to disk, then the
     * JSON goes to a temporary file in the same directory, which is flushed and moved over the final name in one atomic
     * step.
     *
     * @param dir the file's directory, with its frames already written
     * @param complete what to write
     * @throws IOException when a file cannot be flushed, written or moved
     */
    public static void write(Path dir, DatasetComplete complete) throws IOException {
        write(dir, complete, DatasetComplete::fsync, DatasetComplete::move);
    }

    /**
     * Writes the file as {@link #write(Path, DatasetComplete)} does, but lets the caller make the final move (for
     * instance under a lock, with a check that the run is still current). If the mover throws, the temporary file is
     * deleted and there is no final file.
     *
     * @param dir the file's directory, with its frames already written
     * @param complete what to write
     * @param mover moves the temporary file over the final name; it may throw to refuse
     * @throws IOException when a file cannot be flushed, written or moved
     */
    public static void write(Path dir, DatasetComplete complete, Mover mover) throws IOException {
        write(dir, complete, DatasetComplete::fsync, mover);
    }

    /** Moves the finished temporary file over the final name. */
    @FunctionalInterface
    public interface Mover {
        /**
         * Moves the file.
         *
         * @param temp the flushed temporary file
         * @param target the final name
         * @throws IOException when it cannot be moved
         */
        void move(Path temp, Path target) throws IOException;
    }

    private static void move(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Flushes one file or directory to disk; separate so that a test can record what is flushed, and when. */
    @FunctionalInterface
    interface Flusher {
        /**
         * Flushes the file or directory.
         *
         * @param path what to flush
         * @throws IOException when it cannot be flushed
         */
        void flush(Path path) throws IOException;
    }

    static void write(Path dir, DatasetComplete complete, Flusher flusher, Mover mover) throws IOException {
        try (Stream<Path> tree = Files.walk(dir)) {
            for (Path path : tree.toList()) {
                flusher.flush(path);
            }
        }
        Path temp = dir.resolve(TEMP_NAME);
        Files.write(temp, MAPPER.writeValueAsBytes(complete));
        flusher.flush(temp);
        try {
            mover.move(temp, dir.resolve(FILE_NAME));
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException deleteFailure) {
                e.addSuppressed(deleteFailure);
            }
            throw e;
        }
        flusher.flush(dir);
    }

    private static void fsync(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
