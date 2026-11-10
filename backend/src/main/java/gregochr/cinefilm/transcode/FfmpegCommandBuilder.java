package gregochr.cinefilm.transcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The exact ffmpeg argument list for one session: one process reads the file once and writes one output per rung,
 * each through its own {@code segment} muxer (settled by the spike; the {@code hls} muxer's own time grid diverged
 * from the copy rung's). Pure. <b>Every flag choice lives in this class</b>, in the named constants and small
 * helpers below. Arguments are always an array, never a shell string; the {@code nice} wrapper is the process
 * starter's business.
 *
 * <p>Output layout: {@code <outputDir>/<rung id>/seg-<n>.ts}; the directories must exist before ffmpeg starts
 * (see {@link FfmpegTranscoder}). Segment boundaries: every cut after the start segment, less half a frame
 * ({@code h = 0.5 / fps}, 0.02 s when the frame rate is unknown), is given to the transcoded rungs as a forced
 * keyframe time (absolute film time) and to every rung's segment muxer as a split time (relative to the run's
 * start time {@code S}), so a split lands just before the keyframe that opens the next segment. Segment
 * timestamps are the film time plus {@link #TS_OFFSET_SECONDS}.
 *
 * <p>A film split across several files is encoded one file at a time. Under {@code -copyts} the forced keyframe times
 * and the segment times stay on the file's own timeline (the plan's cut list is the file's), and only the output is
 * moved onto the film's timeline: the timestamp offset adds the part's start, and the segment numbers continue from
 * the film-wide number. Every part but the last pads its audio with silence ({@link #PAD_AUDIO_FLAGS}) so the audio
 * ends where the picture ends. A run is never bounded with {@code -t} or {@code -to}: under {@code -copyts} they
 * compare against the input's timestamps and make an empty output; each part runs to its natural end.
 */
public final class FfmpegCommandBuilder {

    // ---- Global and input flags ------------------------------------------------------------------------------

    /** No stdin, quiet, key=value progress on stdout. */
    static final List<String> GLOBAL_FLAGS = List.of("-nostdin", "-hide_banner", "-loglevel", "warning",
        "-nostats", "-progress", "pipe:1");

    /** Only the file protocol may be opened (SECURITY.md rule 4). */
    static final List<String> PROTOCOL_FLAGS = List.of("-protocol_whitelist", "file");

    /** Keep the source's timestamps on the film's timeline. */
    static final String COPY_TIMESTAMPS = "-copyts";

    /** Decode on the GPU and keep the frames there for {@code scale_vaapi}; the render node follows the flag. */
    static final List<String> VAAPI_DECODE_FLAGS = List.of("-hwaccel", "vaapi", "-hwaccel_device");

    /** The tail of {@link #VAAPI_DECODE_FLAGS}. */
    static final List<String> VAAPI_DECODE_OUTPUT_FLAGS = List.of("-hwaccel_output_format", "vaapi");

    /** Names the render node for {@code hwupload} and the VAAPI encoder when the source is decoded in software. */
    static final String VAAPI_DEVICE_FLAG = "-vaapi_device";

    /** Software-decoded frames are converted to 8-bit NV12 and uploaded to the GPU after scaling. */
    static final String UPLOAD_FILTERS = ",format=nv12,hwupload";

    /** Seek flag for a restart at a cut: land on the nearest keyframe before the time, no decode-and-discard. */
    static final String NO_ACCURATE_SEEK = "-noaccurate_seek";

    // ---- Per-rung flags --------------------------------------------------------------------------------------

    /** Copy rung: the bitstream filter writes the parameter sets in front of every keyframe, for MPEG-TS. */
    static final List<String> COPY_VIDEO_FLAGS = List.of("-c:v", "copy", "-bsf:v",
        "h264_mp4toannexb,dump_extra=freq=keyframe");

    /** Hardware H.264 encoder. */
    static final String VAAPI_ENCODER = "h264_vaapi";

    /** Software H.264 encoder and its preset. */
    static final List<String> SOFTWARE_ENCODER_FLAGS = List.of("libx264", "-preset", "veryfast");

    /** A GOP longer than any gap between cuts, so keyframes come only where they are forced. */
    static final String GOP_SIZE = "9999";

    // ---- Segment muxer flags ---------------------------------------------------------------------------------

    /** Segment muxer options before the split times. */
    static final List<String> SEGMENT_FLAGS = List.of("-f", "segment", "-segment_format", "mpegts");

    /** The segment timestamps are the film time plus this many seconds. */
    public static final int TS_OFFSET_SECONDS = 10;

    /** Segment muxer options after the start number, before the timestamp offset. */
    static final List<String> SEGMENT_TIMING_FLAGS = List.of("-reset_timestamps", "0", "-output_ts_offset");

    /** Segment muxer options after the timestamp offset. */
    static final List<String> MUX_DELAY_FLAGS = List.of("-muxdelay", "0", "-muxpreload", "0");

    /**
     * Pads the audio with silence for ever and ends the output with the picture: the audio then runs to the end of
     * the video even when the file's audio stops earlier (a part's AAC track can end a second before its picture).
     * Used on every part but the last.
     */
    static final List<String> PAD_AUDIO_FLAGS = List.of("-af", "apad");

    /** Ends an output when its shortest stream ends: with {@link #PAD_AUDIO_FLAGS} that is the video. */
    static final String SHORTEST = "-shortest";

    /** The segment file name pattern inside a rung directory. */
    static final String SEGMENT_PATTERN = "seg-%d.ts";

    /** Used for {@code -segment_times} when no cut follows the start segment, so the muxer makes one segment. */
    static final String NO_MORE_CUTS = "100000.000000";

    /** Half a frame when the frame rate is unknown, in microseconds. */
    static final long UNKNOWN_FPS_HALF_FRAME_US = 20_000;

    private FfmpegCommandBuilder() {
    }

    /**
     * Builds the command.
     *
     * @param ffmpeg the ffmpeg executable ({@code cinefilm.tools.ffmpeg})
     * @param plan what to make
     * @return the argument list, program first
     */
    public static List<String> build(String ffmpeg, TranscodePlan plan) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.addAll(GLOBAL_FLAGS);
        command.addAll(PROTOCOL_FLAGS);
        command.addAll(inputFlags(plan));
        command.add("-i");
        command.add(plan.source().toString());
        command.addAll(filterFlags(plan));
        int transcodedIndex = 0;
        for (Rung rung : plan.rungs()) {
            command.addAll(outputFlags(plan, rung, transcodedIndex));
            if (!rung.copy()) {
                transcodedIndex++;
            }
        }
        return List.copyOf(command);
    }

    /** Timestamps kept, hardware decode (only when something is encoded on VAAPI), thread bound, restart seek. */
    static List<String> inputFlags(TranscodePlan plan) {
        List<String> flags = new ArrayList<>();
        flags.add(COPY_TIMESTAMPS);
        if (plan.mode() == EncoderMode.VAAPI && !plan.transcodedRungs().isEmpty()) {
            if (plan.hardwareDecode()) {
                flags.addAll(VAAPI_DECODE_FLAGS);
                flags.add(plan.device());
                flags.addAll(VAAPI_DECODE_OUTPUT_FLAGS);
            } else {
                flags.add(VAAPI_DEVICE_FLAG);
                flags.add(plan.device());
            }
        }
        flags.add("-threads");
        flags.add(Integer.toString(plan.threads()));
        if (plan.startSegment() > 0) {
            flags.add(NO_ACCURATE_SEEK);
            flags.add("-ss");
            flags.add(Seconds.format(seekMs(plan)));
        }
        return flags;
    }

    /** The run's start time {@code S} in ms: just after the start cut, so the seek lands on that keyframe. */
    static long seekMs(TranscodePlan plan) {
        return plan.startSegment() > 0 ? plan.startMs() + 1 : 0;
    }

    /** {@code -filter_complex}: split the decoded video and scale it once per transcoded rung. */
    static List<String> filterFlags(TranscodePlan plan) {
        List<Rung> transcoded = plan.transcodedRungs();
        if (transcoded.isEmpty()) {
            return List.of();
        }
        StringBuilder graph = new StringBuilder("[0:v]");
        if (transcoded.size() == 1) {
            graph.append(scale(plan, transcoded.get(0))).append("[v0]");
        } else {
            graph.append("split=").append(transcoded.size());
            for (int i = 0; i < transcoded.size(); i++) {
                graph.append("[s").append(i).append(']');
            }
            for (int i = 0; i < transcoded.size(); i++) {
                graph.append(";[s").append(i).append(']').append(scale(plan, transcoded.get(i)))
                    .append("[v").append(i).append(']');
            }
        }
        return List.of("-filter_complex", graph.toString());
    }

    /** Scales on the GPU for hardware decode, otherwise on the CPU (then uploaded when VAAPI encodes). */
    private static String scale(TranscodePlan plan, Rung rung) {
        String size = "=w=-2:h=" + rung.height();
        if (plan.mode() != EncoderMode.VAAPI) {
            // 8-bit 4:2:0 so a 10-bit source cannot come out as High 10, which browsers will not decode.
            return "scale" + size + ",format=yuv420p";
        }
        return plan.hardwareDecode() ? "scale_vaapi" + size : "scale" + size + UPLOAD_FILTERS;
    }

    /** One output: the rung's video and audio, its codecs, and its segment muxer writing into its directory. */
    static List<String> outputFlags(TranscodePlan plan, Rung rung, int transcodedIndex) {
        List<String> flags = new ArrayList<>();
        if (rung.copy()) {
            flags.addAll(List.of("-map", "0:v:0", "-map", "0:a:" + plan.audioIndex()));
            flags.addAll(COPY_VIDEO_FLAGS);
        } else {
            flags.addAll(List.of("-map", "[v" + transcodedIndex + "]", "-map", "0:a:" + plan.audioIndex(), "-c:v"));
            if (plan.mode() == EncoderMode.VAAPI) {
                flags.add(VAAPI_ENCODER);
            } else {
                flags.addAll(SOFTWARE_ENCODER_FLAGS);
                flags.addAll(List.of("-threads", Integer.toString(plan.threads())));
            }
            flags.addAll(List.of("-b:v", Long.toString(rung.videoBitrate()), "-g", GOP_SIZE));
            String forced = cutTimes(plan, 0);
            if (!forced.isEmpty()) {
                flags.addAll(List.of("-force_key_frames", forced));
            }
        }
        flags.addAll(List.of("-c:a", "aac", "-b:a", Long.toString(rung.audioBitrate()), "-ac", "2"));
        if (plan.padAudio()) {
            flags.addAll(PAD_AUDIO_FLAGS);
            flags.add(SHORTEST);
        }
        flags.addAll(SEGMENT_FLAGS);
        String split = cutTimes(plan, seekMs(plan));
        flags.addAll(List.of("-segment_times", split.isEmpty() ? NO_MORE_CUTS : split,
            "-segment_start_number", Integer.toString(plan.startNumber())));
        flags.addAll(SEGMENT_TIMING_FLAGS);
        flags.add(timestampOffset(plan));
        flags.addAll(MUX_DELAY_FLAGS);
        flags.add(plan.rungDir(rung).resolve(SEGMENT_PATTERN).toString());
        return flags;
    }

    /**
     * The timestamp offset: {@link #TS_OFFSET_SECONDS} plus the part's start on the film's timeline, so a later part's
     * timestamps continue where the previous part's end ({@code "10"} for a film in one piece).
     */
    static String timestampOffset(TranscodePlan plan) {
        return plan.partStartMs() == 0 ? Integer.toString(TS_OFFSET_SECONDS)
            : Seconds.format(TS_OFFSET_SECONDS * 1000L + plan.partStartMs());
    }

    /**
     * The cuts after the start segment, each less half a frame and less {@code originMs}, in seconds with six
     * decimals, comma-separated (empty when no cut follows). {@code originMs} 0 gives film time.
     */
    static String cutTimes(TranscodePlan plan, long originMs) {
        long halfFrameUs = plan.fps() == null ? UNKNOWN_FPS_HALF_FRAME_US : Math.round(500_000.0 / plan.fps());
        StringBuilder times = new StringBuilder();
        for (int n = plan.startSegment() + 1; n < plan.cuts().segmentCount(); n++) {
            if (times.length() > 0) {
                times.append(',');
            }
            long us = (plan.cuts().startMs(n) - originMs) * 1000 - halfFrameUs;
            times.append(String.format(Locale.ROOT, "%d.%06d", us / 1_000_000, us % 1_000_000));
        }
        return times.toString();
    }
}
