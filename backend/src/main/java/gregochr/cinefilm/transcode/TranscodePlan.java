package gregochr.cinefilm.transcode;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything one ffmpeg run needs: a single read of the source making every rung into {@code outputDir/<rung id>/}.
 *
 * @param source the media file, an absolute path from {@code LibraryRoot} (never taken from a request)
 * @param outputDir the session directory, absolute, without {@code %} (ffmpeg expands it in file name patterns)
 * @param rungs the rungs to make, best first; at least one
 * @param audioIndex the position of the chosen audio stream among the file's audio streams ({@code -map 0:a:N}),
 *     not its container stream index
 * @param cuts the film's segment timeline
 * @param startSegment the segment to start at (0 for the film's start); the encoder seeks to its cut
 * @param mode what to encode with; {@code UNAVAILABLE} allows copy rungs only
 * @param threads ffmpeg's thread bound
 * @param fps the film's average frame rate (sets how far before a cut keyframes are forced), or null when unknown
 * @param hardwareDecode whether the source can be decoded on the GPU (8-bit 4:2:0 H.264): VAAPI encodes then keep
 *     the frames on the GPU; otherwise the source is decoded in software and uploaded for the encoder
 * @param device the VAAPI render node ({@code cinefilm.transcode.device}), used only in {@code VAAPI} mode
 */
public record TranscodePlan(Path source, Path outputDir, List<Rung> rungs, int audioIndex, CutList cuts,
                            int startSegment, EncoderMode mode, int threads, Double fps, boolean hardwareDecode,
                            String device) {

    /** The default VAAPI render node. */
    public static final String DEFAULT_DEVICE = "/dev/dri/renderD128";

    /** A plan whose source is decoded on the GPU, on the default render node. */
    public TranscodePlan(Path source, Path outputDir, List<Rung> rungs, int audioIndex, CutList cuts,
                         int startSegment, EncoderMode mode, int threads, Double fps) {
        this(source, outputDir, rungs, audioIndex, cuts, startSegment, mode, threads, fps, true, DEFAULT_DEVICE);
    }

    /** Checks the plan; a bad one is a programming error, not a request error. */
    public TranscodePlan {
        if (!source.isAbsolute() || !outputDir.isAbsolute()) {
            throw new IllegalArgumentException("source and output directory must be absolute");
        }
        if (outputDir.toString().contains("%")) {
            throw new IllegalArgumentException("output directory must not contain %");
        }
        if (rungs.isEmpty()) {
            throw new IllegalArgumentException("at least one rung");
        }
        if (audioIndex < 0 || threads < 1) {
            throw new IllegalArgumentException("audioIndex must be 0 or more and threads at least 1");
        }
        if (startSegment < 0 || startSegment >= cuts.segmentCount()) {
            throw new IllegalArgumentException("startSegment is outside the cut list");
        }
        if (mode == EncoderMode.UNAVAILABLE && rungs.stream().anyMatch(rung -> !rung.copy())) {
            throw new IllegalArgumentException("no encoder for transcoded rungs");
        }
        if (fps != null && !(fps > 0)) {
            throw new IllegalArgumentException("fps must be positive when known");
        }
        if (device == null || device.isBlank()) {
            throw new IllegalArgumentException("device must not be blank");
        }
        rungs = List.copyOf(rungs);
    }

    /** The rungs that are encoded rather than copied, in ladder order. */
    public List<Rung> transcodedRungs() {
        return rungs.stream().filter(rung -> !rung.copy()).toList();
    }

    /** The directory a rung's segments are written to: {@code outputDir/<rung id>}. */
    public Path rungDir(Rung rung) {
        return outputDir.resolve(rung.id());
    }

    /** The cut the encoder starts at, in milliseconds. */
    public long startMs() {
        return cuts.startMs(startSegment);
    }
}
