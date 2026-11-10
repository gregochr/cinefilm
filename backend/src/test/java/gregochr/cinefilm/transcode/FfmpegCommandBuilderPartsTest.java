package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One ffmpeg run per file of a split film: local times for the cuts, the film's timeline for the output (timestamp
 * offset, segment numbers), silence padding on every part but the last, and never a {@code -t} or {@code -to}.
 */
class FfmpegCommandBuilderPartsTest {

    private static final Rung ORIGINAL = new Rung("original", 1080, 8_000_000, true);
    private static final Rung R720 = new Rung("720p", 720, 2_500_000, false);
    private static final CutList PART_2 = CutList.parse("0,4004,8008", 12_000);

    /** Part 2 of a film whose part 1 is 6 353 420 ms long and has 1 589 segments (numbers 0 to 1588). */
    private static TranscodePlan part2(List<Rung> rungs, int startSegment, boolean padAudio) {
        return new TranscodePlan(Path.of("/films/Towers pt2.m4v"), Path.of("/cache/hls/abc"), rungs, 1, PART_2,
            startSegment, EncoderMode.VAAPI, 2, 25.0, true, "/dev/dri/renderD128", 6_353_420, 1_589, padAudio);
    }

    @Test
    void aLaterPartIsOffsetByTenSecondsPlusItsStartAndNumberedFromItsFirstFilmWideSegment() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", part2(List.of(ORIGINAL), 0, false));

        assertThat(command).containsSequence("-segment_start_number", "1589", "-reset_timestamps", "0",
            "-output_ts_offset", "6363.420", "-muxdelay", "0", "-muxpreload", "0");
    }

    @Test
    void theCutsAndForcedKeyframesStayOnThePartsOwnTimeline() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", part2(List.of(R720), 0, false));

        assertThat(command).containsSequence("-force_key_frames", "3.984000,7.988000")
            .containsSequence("-segment_times", "3.984000,7.988000");
        assertThat(command).doesNotContain("-ss");
    }

    @Test
    void aRestartInsideALaterPartSeeksLocallyAndNumbersFromTheFilmWideSegment() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", part2(List.of(ORIGINAL), 1, false));

        assertThat(command).containsSequence("-noaccurate_seek", "-ss", "4.005")
            .containsSequence("-segment_times", "3.983000")
            .containsSequence("-segment_start_number", "1590", "-reset_timestamps", "0", "-output_ts_offset",
                "6363.420");
    }

    @Test
    void everyPartButTheLastPadsItsAudioToTheEndOfThePictureOnEveryOutput() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", part2(List.of(ORIGINAL, R720), 0, true));

        assertThat(command).containsSequence("-c:a", "aac", "-b:a", "128000", "-ac", "2", "-af", "apad",
            "-shortest", "-f", "segment");
        assertThat(command.stream().filter("apad"::equals)).hasSize(2);
        assertThat(command.stream().filter("-shortest"::equals)).hasSize(2);
    }

    @Test
    void theLastPartAndAFilmInOnePieceKeepTheirAudioAsItIs() {
        List<String> last = FfmpegCommandBuilder.build("ffmpeg", part2(List.of(ORIGINAL, R720), 0, false));
        List<String> single = FfmpegCommandBuilder.build("ffmpeg", new TranscodePlan(Path.of("/films/Heat.mp4"),
            Path.of("/cache/hls/abc"), List.of(ORIGINAL), 1, PART_2, 0, EncoderMode.VAAPI, 2, 25.0));

        assertThat(last).doesNotContain("apad", "-shortest", "-af");
        assertThat(single).doesNotContain("apad", "-shortest", "-af");
    }

    @Test
    void aFilmInOnePieceIsStillOffsetByTenSecondsAndNumberedFromTheStartSegment() {
        List<String> command = FfmpegCommandBuilder.build("ffmpeg", new TranscodePlan(Path.of("/films/Heat.mp4"),
            Path.of("/cache/hls/abc"), List.of(ORIGINAL), 1, PART_2, 2, EncoderMode.VAAPI, 2, 25.0));

        assertThat(command).containsSequence("-segment_start_number", "2", "-reset_timestamps", "0",
            "-output_ts_offset", "10");
    }

    @Test
    void aRunIsNeverBoundedWithADurationOrAnEndTimeBecauseUnderCopytsTheyCompareWithTheInputsTimestamps() {
        for (boolean pad : List.of(true, false)) {
            List<String> command = FfmpegCommandBuilder.build("ffmpeg", part2(List.of(ORIGINAL, R720), 1, pad));

            assertThat(command).doesNotContain("-t", "-to");
        }
    }

    @Test
    void theTimestampOffsetAddsThePartsStartToTenSeconds() {
        assertThat(FfmpegCommandBuilder.timestampOffset(part2(List.of(ORIGINAL), 0, false))).isEqualTo("6363.420");
        assertThat(FfmpegCommandBuilder.timestampOffset(new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"),
            List.of(ORIGINAL), 0, PART_2, 0, EncoderMode.VAAPI, 1, null))).isEqualTo("10");
    }

    @Test
    void thePlanNumbersItsFirstSegmentFilmWideAndRefusesNegativePartPositions() {
        assertThat(part2(List.of(ORIGINAL), 2, false).startNumber()).isEqualTo(1_591);
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), List.of(ORIGINAL), 0, PART_2, 0,
            EncoderMode.VAAPI, 1, null, true, "/dev/dri/renderD128", -1, 0, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TranscodePlan(Path.of("/f.mp4"), Path.of("/o"), List.of(ORIGINAL), 0, PART_2, 0,
            EncoderMode.VAAPI, 1, null, true, "/dev/dri/renderD128", 0, -1, false))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
