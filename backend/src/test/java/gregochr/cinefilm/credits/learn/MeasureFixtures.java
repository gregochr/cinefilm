package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Builds small synthetic datasets for the measurement tool's tests: 8 by 4 frames as ffmpeg wrote them, so 4 by 2 grey
 * frames and 4 by 4 colour frames, every frame one flat grey level (coarse frames {@link #COARSE_LEVEL}, dense frames
 * {@link #DENSE_LEVEL}), written with {@link FramePrep} and {@link DatasetComplete#write}.
 */
final class MeasureFixtures {

    static final int COARSE_LEVEL = 20;
    static final int DENSE_LEVEL = 220;
    static final long TARGET_MS = 100_000;
    static final DatasetComplete.Settings SETTINGS = new DatasetComplete.Settings("filter", "rgb24", "gray", 8, 4, 4,
        0.95f, 5_000, 2, 30_000, 60_000, 19);

    private MeasureFixtures() {
    }

    /** A flat grey frame of the stored size. */
    static GreyFrame grey(int level) {
        return grey(4, 2, level);
    }

    static GreyFrame grey(int width, int height, int level) {
        byte[] pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) level);
        return new GreyFrame(width, height, pixels);
    }

    /** A flat colour frame of the given side, the same level in every channel. */
    static RgbFrame colour(int side, int level) {
        byte[] pixels = new byte[side * side * 3];
        Arrays.fill(pixels, (byte) level);
        return new RgbFrame(side, side, pixels);
    }

    /** What {@code complete.json} says for a film. */
    static DatasetComplete complete(long mediaFileId, String ffmpeg, String gitCommit, String writtenAt,
                                    List<Long> coarse, List<Long> dense) {
        return new DatasetComplete(DatasetComplete.FORMAT, mediaFileId, "1000:1", 600_000L, TARGET_MS,
            new DatasetComplete.Window(360_000L, 600_000L), new DatasetComplete.Frame(8, 4),
            new DatasetComplete.Area(0, 0, 8, 4), List.of(1L), coarse, dense, ffmpeg, "25.0.1", gitCommit, SETTINGS,
            writtenAt);
    }

    /** Writes a film's frames and its {@code complete.json}; returns the film's directory. */
    static Path film(Path frames, String dirName, DatasetComplete complete) throws IOException {
        Path dir = Files.createDirectories(frames.resolve(dirName));
        write(dir, "coarse", complete.coarse(), COARSE_LEVEL);
        write(dir, "dense", complete.dense(), DENSE_LEVEL);
        DatasetComplete.write(dir, complete);
        return dir;
    }

    private static void write(Path dir, String kind, List<Long> offsets, int level) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(kind));
        for (long offset : offsets) {
            Files.write(folder.resolve(offset + ".png"), FramePrep.png(grey(level)));
            Files.write(folder.resolve(offset + ".jpg"), FramePrep.jpeg(colour(4, level)));
        }
    }

    /** Sets the modification time of every frame file of a film. */
    static void touch(Path dir, Instant time) throws IOException {
        for (String kind : List.of("coarse", "dense")) {
            try (var files = Files.list(dir.resolve(kind))) {
                for (Path file : files.toList()) {
                    Files.setLastModifiedTime(file, FileTime.from(time));
                }
            }
        }
    }
}
