package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.ActiveArea;
import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.CreditsFrameReader.TimedRgb;
import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the frames of one file for the credits dataset: the coarse frames across the window and the dense frames
 * around the target, each as a lossless grey PNG and a colour JPEG made by {@link FramePrep}. Not a Spring bean: it is
 * given an open reader job and a directory.
 *
 * <p>Each coarse frame is written as soon as it is read and none is kept. The dense frames come from one sequential
 * decode, so the list the reader returns is held while they are encoded: at most 120 frames of about 518 KB each
 * (480 by 360 by 3 bytes, with the wider ones larger), about 62 MB, against the production heap of 512 MB. The
 * checkpoint runs between dense writes, because no ffmpeg call (where the reader's own checkpoint runs) happens then.
 *
 * <p>The JVM's PNG and JPEG encoding runs at the server's normal priority, not at the niceness of ffmpeg.
 */
public final class DatasetFrames {

    /** The dense frames start this long before the target. */
    public static final long DENSE_LEAD_MS = 30_000;
    /** The dense frames cover this long, from {@link #DENSE_LEAD_MS} before the target. */
    public static final long DENSE_LENGTH_MS = 60_000;

    private static final String COARSE = "coarse";
    private static final String DENSE = "dense";

    private DatasetFrames() {
    }

    /**
     * What was written, for {@code complete.json}.
     *
     * @param area the picture area, in the frames' own coordinates
     * @param frameWidth the width of a frame as ffmpeg wrote it
     * @param frameHeight the height of a frame as ffmpeg wrote it
     * @param activeAreaOffsets where the frames the area was measured on were taken
     * @param coarse the offsets of the coarse frames
     * @param dense the offsets of the dense frames
     */
    public record Written(ActiveArea area, int frameWidth, int frameHeight, List<Long> activeAreaOffsets,
                          List<Long> coarse, List<Long> dense) {
    }

    /**
     * Reads and writes every frame of one file into {@code coarse/} and {@code dense/} under the directory. It does not
     * write {@code complete.json}.
     *
     * @param job the open reader job of the file
     * @param target the file and its target
     * @param dir the file's directory (made if missing)
     * @param checkpoint run between dense writes; it throws to stop
     * @return what was written
     * @throws IOException when a frame cannot be written
     * @throws CreditsFrameReader.ReadException when ffmpeg fails (the whole file is abandoned)
     */
    public static Written write(CreditsFrameReader.Job job, DatasetTarget target, Path dir, Runnable checkpoint)
        throws IOException {
        List<GreyFrame> samples = job.activeAreaFrames();
        ActiveArea area = ActiveArea.of(samples);
        Path coarseDir = Files.createDirectories(dir.resolve(COARSE));
        Path denseDir = Files.createDirectories(dir.resolve(DENSE));
        List<Long> coarse = CreditsFrameReader.coarseOffsets(target.durationMs());
        for (long offset : coarse) {
            store(job.rgbFrame(offset), offset, area, coarseDir);
        }
        List<Long> dense = new ArrayList<>();
        for (TimedRgb timed : job.rgbDense(target.targetStartMs() - DENSE_LEAD_MS, DENSE_LENGTH_MS)) {
            checkpoint.run();
            store(timed.frame(), timed.offsetMs(), area, denseDir);
            dense.add(timed.offsetMs());
        }
        GreyFrame first = samples.getFirst();
        return new Written(area, first.width(), first.height(), CreditsFrameReader.activeOffsets(target.durationMs()),
            List.copyOf(coarse), List.copyOf(dense));
    }

    private static void store(RgbFrame frame, long offsetMs, ActiveArea area, Path dir) throws IOException {
        Files.write(dir.resolve(offsetMs + ".png"), FramePrep.png(FramePrep.grey(frame)));
        Files.write(dir.resolve(offsetMs + ".jpg"), FramePrep.colourJpeg(frame, area));
    }
}
