package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {

    private static final Rung ORIGINAL = new Rung("original", 1080, 8_000_000, true);
    private static final Rung R720 = new Rung("720p", 720, 2_500_000, false);
    private static final Rung R480 = new Rung("480p", 480, 1_000_000, false);
    private static final Rung R360 = new Rung("360p", 360, 600_000, false);
    private static final Rung R240 = new Rung("240p", 240, 250_000, false);
    private static final CutList CUTS = CutList.parse("0,4000,8008", 12_000);
    private static final CutList FOUR = CutList.parse("0,4000,8008,12000", 16_000);

    private static TranscodePlan plan(List<Rung> rungs, CutList cuts, int start, EncoderMode mode, Double fps) {
        return new TranscodePlan(Path.of("/films/Heat (1995).mp4"), Path.of("/cache/hls/abc"), rungs, 1, cuts, start,
            mode, 2, fps);
    }

    @SafeVarargs
    private static List<String> join(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static final List<String> HEAD = List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning",
        "-nostats", "-progress", "pipe:1", "-protocol_whitelist", "file");

    private static List<String> audio(String bitrate) {
        return List.of("-c:a", "aac", "-b:a", bitrate, "-ac", "2");
    }

    private static List<String> segments(String times, int start, String dir) {
        return List.of("-f", "segment", "-segment_format", "mpegts", "-segment_times", times,
            "-segment_start_number", Integer.toString(start), "-reset_timestamps", "0", "-output_ts_offset", "10",
            "-muxdelay", "0", "-muxpreload", "0", "/cache/hls/abc/" + dir + "/seg-%d.ts");
    }

    private static List<String> copyOutput(String times, int start) {
        return join(List.of("-map", "0:v:0", "-map", "0:a:1", "-c:v", "copy", "-bsf:v",
            "h264_mp4toannexb,dump_extra=freq=keyframe"), audio("128000"), segments(times, start, "original"));
    }

    private static List<String> vaapiOutput(String label, String rate, String keyframes, String audioRate,
                                            String times, int start, String dir) {
        List<String> video = new ArrayList<>(List.of("-map", label, "-map", "0:a:1", "-c:v", "h264_vaapi", "-b:v",
            rate, "-g", "9999"));
        if (keyframes != null) {
            video.addAll(List.of("-force_key_frames", keyframes));
        }
        return join(video, audio(audioRate), segments(times, start, dir));
    }

    @Test
    void aVaapiLadderWithACopyRungMakesEveryRungFromOneReadWithOneSegmentOutputEach() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(ORIGINAL, R720, R480), CUTS, 0,
            EncoderMode.VAAPI, 25.0));

        assertThat(command).isEqualTo(join(HEAD,
            List.of("-copyts", "-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128",
                "-hwaccel_output_format", "vaapi", "-threads", "2", "-i", "/films/Heat (1995).mp4",
                "-filter_complex", "[0:v]split=2[s0][s1];[s0]scale_vaapi=w=-2:h=720[v0];"
                    + "[s1]scale_vaapi=w=-2:h=480[v1]"),
            copyOutput("3.980000,7.988000", 0),
            vaapiOutput("[v0]", "2500000", "3.980000,7.988000", "128000", "3.980000,7.988000", 0, "720p"),
            vaapiOutput("[v1]", "1000000", "3.980000,7.988000", "128000", "3.980000,7.988000", 0, "480p")));
    }

    @Test
    void theFloorRungIsASplitOutputAt250kVideoAnd64kAudio() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(ORIGINAL, R720, R480, R360, R240),
            CUTS, 0, EncoderMode.VAAPI, 25.0));

        assertThat(command).isEqualTo(join(HEAD,
            List.of("-copyts", "-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128",
                "-hwaccel_output_format", "vaapi", "-threads", "2", "-i", "/films/Heat (1995).mp4",
                "-filter_complex", "[0:v]split=4[s0][s1][s2][s3];[s0]scale_vaapi=w=-2:h=720[v0];"
                    + "[s1]scale_vaapi=w=-2:h=480[v1];[s2]scale_vaapi=w=-2:h=360[v2];"
                    + "[s3]scale_vaapi=w=-2:h=240[v3]"),
            copyOutput("3.980000,7.988000", 0),
            vaapiOutput("[v0]", "2500000", "3.980000,7.988000", "128000", "3.980000,7.988000", 0, "720p"),
            vaapiOutput("[v1]", "1000000", "3.980000,7.988000", "128000", "3.980000,7.988000", 0, "480p"),
            vaapiOutput("[v2]", "600000", "3.980000,7.988000", "96000", "3.980000,7.988000", 0, "360p"),
            vaapiOutput("[v3]", "250000", "3.980000,7.988000", "64000", "3.980000,7.988000", 0, "240p")));
    }

    @Test
    void aSingleTranscodedRungScalesWithoutASplit() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(R720), CUTS, 0, EncoderMode.VAAPI,
            25.0));

        assertThat(command).containsSequence("-filter_complex", "[0:v]scale_vaapi=w=-2:h=720[v0]")
            .containsSequence("-map", "[v0]", "-map", "0:a:1", "-c:v", "h264_vaapi");
        assertThat(String.join(" ", command)).doesNotContain("split");
    }

    @Test
    void aCopyOnlyPlanHasNoHardwareDecodeNoFilterGraphAndOneOutput() {
        List<String> command = FfmpegCommandBuilder.build("/usr/bin/ffmpeg", plan(List.of(ORIGINAL), CUTS, 0,
            EncoderMode.UNAVAILABLE, null));

        assertThat(command).isEqualTo(join(
            List.of("/usr/bin/ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning", "-nostats", "-progress",
                "pipe:1", "-protocol_whitelist", "file", "-copyts", "-threads", "2", "-i", "/films/Heat (1995).mp4"),
            copyOutput("3.980000,7.988000", 0)));
    }

    @Test
    void theSoftwareEncoderScalesOnTheCpuWithLibx264() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(R480, R360), CUTS, 0,
            EncoderMode.SOFTWARE, 25.0));

        assertThat(command).isEqualTo(join(HEAD,
            List.of("-copyts", "-threads", "2", "-i", "/films/Heat (1995).mp4", "-filter_complex",
                "[0:v]split=2[s0][s1];[s0]scale=w=-2:h=480,format=yuv420p[v0];[s1]scale=w=-2:h=360,format=yuv420p[v1]",
                "-map", "[v0]", "-map", "0:a:1", "-c:v", "libx264", "-preset", "veryfast",
                "-threads", "2", "-b:v", "1000000", "-g",
                "9999", "-force_key_frames", "3.980000,7.988000"),
            audio("128000"), segments("3.980000,7.988000", 0, "480p"),
            List.of("-map", "[v1]", "-map", "0:a:1", "-c:v", "libx264", "-preset", "veryfast",
                "-threads", "2", "-b:v", "600000",
                "-g", "9999", "-force_key_frames", "3.980000,7.988000"),
            audio("96000"), segments("3.980000,7.988000", 0, "360p")));
    }

    private static TranscodePlan full(List<Rung> rungs, EncoderMode mode, int threads, boolean hardwareDecode,
                                      String device) {
        return new TranscodePlan(Path.of("/films/Heat (1995).mp4"), Path.of("/cache/hls/abc"), rungs, 1, CUTS, 0,
            mode, threads, 25.0, hardwareDecode, device);
    }

    @Test
    void everyLibx264OutputCarriesItsOwnThreadBoundAfterTheInputAndBeforeItsFile() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", full(List.of(ORIGINAL, R480, R360),
            EncoderMode.SOFTWARE, 3, true, "/dev/dri/renderD128"));

        assertThat(command).isEqualTo(join(HEAD,
            List.of("-copyts", "-threads", "3", "-i", "/films/Heat (1995).mp4", "-filter_complex",
                "[0:v]split=2[s0][s1];[s0]scale=w=-2:h=480,format=yuv420p[v0];[s1]scale=w=-2:h=360,format=yuv420p[v1]"),
            copyOutput("3.980000,7.988000", 0),
            List.of("-map", "[v0]", "-map", "0:a:1", "-c:v", "libx264", "-preset", "veryfast", "-threads", "3",
                "-b:v", "1000000", "-g", "9999", "-force_key_frames", "3.980000,7.988000"),
            audio("128000"), segments("3.980000,7.988000", 0, "480p"),
            List.of("-map", "[v1]", "-map", "0:a:1", "-c:v", "libx264", "-preset", "veryfast", "-threads", "3",
                "-b:v", "600000", "-g", "9999", "-force_key_frames", "3.980000,7.988000"),
            audio("96000"), segments("3.980000,7.988000", 0, "360p")));
    }

    @Test
    void theHardwareEncoderOutputsHaveNoOutputThreadBound() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", full(List.of(R720), EncoderMode.VAAPI, 3, true,
            "/dev/dri/renderD128"));

        assertThat(command).containsOnlyOnce("-threads");
    }

    @Test
    void aNonDefaultRenderNodeIsUsedForHardwareDecode() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", full(List.of(ORIGINAL, R720), EncoderMode.VAAPI,
            2, true, "/dev/dri/renderD129"));

        assertThat(command).containsSequence("-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD129",
            "-hwaccel_output_format", "vaapi", "-threads", "2", "-i");
        assertThat(command).doesNotContain("/dev/dri/renderD128").doesNotContain("-vaapi_device");
    }

    @Test
    void aSourceTheGpuCannotDecodeIsDecodedInSoftwareAndUploadedForTheVaapiEncode() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", full(List.of(R720, R480), EncoderMode.VAAPI, 2,
            false, "/dev/dri/renderD129"));

        assertThat(command).isEqualTo(join(HEAD,
            List.of("-copyts", "-vaapi_device", "/dev/dri/renderD129", "-threads", "2", "-i",
                "/films/Heat (1995).mp4", "-filter_complex",
                "[0:v]split=2[s0][s1];[s0]scale=w=-2:h=720,format=nv12,hwupload[v0];"
                    + "[s1]scale=w=-2:h=480,format=nv12,hwupload[v1]"),
            vaapiOutput("[v0]", "2500000", "3.980000,7.988000", "128000", "3.980000,7.988000", 0, "720p"),
            vaapiOutput("[v1]", "1000000", "3.980000,7.988000", "128000", "3.980000,7.988000", 0, "480p")));
        assertThat(command).doesNotContain("-hwaccel").doesNotContain("-hwaccel_output_format")
            .doesNotContain("scale_vaapi");
    }

    @Test
    void aSingleSoftwareDecodedVaapiRungHasNoSplit() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", full(List.of(R720), EncoderMode.VAAPI, 2, false,
            "/dev/dri/renderD128"));

        assertThat(command).containsSequence("-filter_complex", "[0:v]scale=w=-2:h=720,format=nv12,hwupload[v0]");
    }

    @Test
    void theDecodeChoiceAndDeviceDoNotTouchSoftwareModeOrCopyOnlyPlans() {
        assertThat(FfmpegCommandBuilder.build("ffmpeg", full(List.of(R480), EncoderMode.SOFTWARE, 2, false,
            "/dev/dri/renderD129"))).doesNotContain("-vaapi_device", "-hwaccel", "hwupload")
            .containsSequence("-filter_complex", "[0:v]scale=w=-2:h=480,format=yuv420p[v0]");
        assertThat(FfmpegCommandBuilder.build("ffmpeg", full(List.of(ORIGINAL), EncoderMode.VAAPI, 2, false,
            "/dev/dri/renderD129"))).doesNotContain("-vaapi_device", "-hwaccel", "-filter_complex");
    }

    @Test
    void aBlankDeviceIsRejected() {
        List<Rung> copy = List.of(ORIGINAL);
        assertThatThrownBy(() -> full(copy, EncoderMode.VAAPI, 1, true, " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("device must not be blank");
        assertThatThrownBy(() -> full(copy, EncoderMode.VAAPI, 1, true, null))
            .hasMessage("device must not be blank");
    }

    @Test
    void restartingAtACutSeeksJustPastItAndMakesTimesAbsoluteForEncodersAndRelativeForSplits() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(ORIGINAL, R360), FOUR, 1,
            EncoderMode.SOFTWARE, 25.0));

        assertThat(command).isEqualTo(join(HEAD,
            List.of("-copyts", "-threads", "2", "-noaccurate_seek", "-ss", "4.001", "-i", "/films/Heat (1995).mp4",
                "-filter_complex", "[0:v]scale=w=-2:h=360,format=yuv420p[v0]"),
            copyOutput("3.987000,7.979000", 1),
            List.of("-map", "[v0]", "-map", "0:a:1", "-c:v", "libx264", "-preset", "veryfast",
                "-threads", "2", "-b:v", "600000",
                "-g", "9999", "-force_key_frames", "7.988000,11.980000"),
            audio("96000"), segments("3.987000,7.979000", 1, "360p")));
    }

    @Test
    void theLastSegmentHasNoCutAfterItSoNoKeyframeIsForcedAndOneSegmentIsMade() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(ORIGINAL, R720), CUTS, 2,
            EncoderMode.VAAPI, 25.0));

        assertThat(command).containsSequence("-noaccurate_seek", "-ss", "8.009", "-i")
            .doesNotContain("-force_key_frames");
        assertThat(command).containsSequence("-segment_times", "100000.000000", "-segment_start_number", "2");
    }

    @Test
    void aFilmOfOneSegmentIsOneSegment() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(ORIGINAL),
            CutList.parse("0", 3000), 0, EncoderMode.VAAPI, null));

        assertThat(command).containsSequence("-segment_times", "100000.000000", "-segment_start_number", "0");
        assertThat(command).doesNotContain("-noaccurate_seek");
    }

    @Test
    void halfAFrameIsHalfOverTheFrameRateRoundedToTheMicrosecondAndTwentyMillisecondsWhenUnknown() {
        CutList cuts = CutList.parse("0,4000", 8000);

        assertThat(FfmpegCommandBuilder.cutTimes(plan(List.of(ORIGINAL), cuts, 0, EncoderMode.VAAPI, 24.0), 0))
            .isEqualTo("3.979167");
        assertThat(FfmpegCommandBuilder.cutTimes(plan(List.of(ORIGINAL), cuts, 0, EncoderMode.VAAPI, 23.976), 0))
            .isEqualTo("3.979146");
        assertThat(FfmpegCommandBuilder.cutTimes(plan(List.of(ORIGINAL), cuts, 0, EncoderMode.VAAPI, 50.0), 0))
            .isEqualTo("3.990000");
        assertThat(FfmpegCommandBuilder.cutTimes(plan(List.of(ORIGINAL), cuts, 0, EncoderMode.VAAPI, null), 0))
            .isEqualTo("3.980000");
    }

    @Test
    void timesOverTenThousandSecondsKeepSixDecimals() {
        CutList cuts = CutList.parse("0,4000,3600000,7200001", 7_300_000);

        assertThat(FfmpegCommandBuilder.cutTimes(plan(List.of(ORIGINAL), cuts, 0, EncoderMode.VAAPI, 25.0), 0))
            .isEqualTo("3.980000,3599.980000,7199.981000");
    }

    @Test
    void theChosenAudioStreamIsMappedByItsPositionAmongAudioStreams() {
        TranscodePlan third = new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), List.of(ORIGINAL), 2, CUTS, 0,
            EncoderMode.VAAPI, 1, null);

        assertThat(FfmpegCommandBuilder.build("ffmpeg", third)).containsSequence("-map", "0:a:2", "-c:v", "copy");
    }

    @Test
    void onlyTheFileProtocolIsAllowedAndEachRungWritesToItsOwnDirectory() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", plan(List.of(ORIGINAL, R720), CUTS, 0,
            EncoderMode.VAAPI, 25.0));

        assertThat(command).containsSequence("-protocol_whitelist", "file").contains("-nostdin");
        assertThat(command).contains("/cache/hls/abc/original/seg-%d.ts", "/cache/hls/abc/720p/seg-%d.ts");
        assertThat(command).doesNotContain("hls").doesNotContain("-var_stream_map");
    }

    @Test
    void thePlanRejectsWhatCouldBeMisreadByFfmpegOrCannotRun() {
        List<Rung> copy = List.of(ORIGINAL);
        assertThatThrownBy(() -> new TranscodePlan(Path.of("f.mp4"), Path.of("/o"), copy, 0, CUTS, 0,
            EncoderMode.VAAPI, 1, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("source and output directory must be absolute");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("o"), copy, 0, CUTS, 0,
            EncoderMode.VAAPI, 1, null)).hasMessage("source and output directory must be absolute");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o/%d"), copy, 0, CUTS, 0,
            EncoderMode.VAAPI, 1, null)).hasMessage("output directory must not contain %");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), List.of(), 0, CUTS, 0,
            EncoderMode.VAAPI, 1, null)).hasMessage("at least one rung");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), copy, -1, CUTS, 0,
            EncoderMode.VAAPI, 1, null)).hasMessage("audioIndex must be 0 or more and threads at least 1");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), copy, 0, CUTS, 0,
            EncoderMode.VAAPI, 0, null)).hasMessage("audioIndex must be 0 or more and threads at least 1");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), copy, 0, CUTS, -1,
            EncoderMode.VAAPI, 1, null)).hasMessage("startSegment is outside the cut list");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), copy, 0, CUTS, 3,
            EncoderMode.VAAPI, 1, null)).hasMessage("startSegment is outside the cut list");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), List.of(R720), 0, CUTS, 0,
            EncoderMode.UNAVAILABLE, 1, null)).hasMessage("no encoder for transcoded rungs");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), copy, 0, CUTS, 0,
            EncoderMode.VAAPI, 1, 0.0)).hasMessage("fps must be positive when known");
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), copy, 0, CUTS, 0,
            EncoderMode.VAAPI, 1, Double.NaN)).hasMessage("fps must be positive when known");
    }

    @Test
    void theAccessorsReportTheStartCutTheTranscodedRungsAndTheRungDirectories() {
        TranscodePlan last = plan(List.of(ORIGINAL, R720), CUTS, 2, EncoderMode.VAAPI, 25.0);

        assertThat(last.startMs()).isEqualTo(8008);
        assertThat(last.transcodedRungs()).containsExactly(R720);
        assertThat(last.rungDir(R720)).isEqualTo(Path.of("/cache/hls/abc/720p"));
    }
}
