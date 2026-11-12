package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LadderTest {

    private static List<String> ids(int height, boolean copyOk, EncoderMode mode) {
        return Ladder.rungs(height, 9_000_000, copyOk, mode).stream().map(Rung::id).toList();
    }

    private static List<Integer> heights(int height, boolean copyOk, EncoderMode mode) {
        return Ladder.rungs(height, 9_000_000, copyOk, mode).stream().map(Rung::height).toList();
    }

    private static final Rung R720 = new Rung("720p", 720, 2_500_000, false);
    private static final Rung R480 = new Rung("480p", 480, 1_000_000, false);
    private static final Rung R360 = new Rung("360p", 360, 600_000, false);
    private static final Rung FLOOR = new Rung("240p", 240, 250_000, false);

    private static Rung original(int height) {
        return new Rung("original", height, 9_000_000, true);
    }

    private static List<Rung> rungs(int height, boolean copyOk, EncoderMode mode) {
        return Ladder.rungs(height, 9_000_000, copyOk, mode);
    }

    @Test
    void aFullHdFileWithACopyRungHasOriginal720p480p360pAndTheFloor() {
        assertThat(rungs(1080, true, EncoderMode.VAAPI)).containsExactly(original(1080), R720, R480, R360, FLOOR);
    }

    @Test
    void withoutACopyRungTheTopRungIsTranscodedAtTheSourceHeightNamedByHeight() {
        assertThat(rungs(820, false, EncoderMode.VAAPI)).containsExactly(new Rung("1080p", 820, 5_000_000, false),
            R720, R480, R360, FLOOR);
        assertThat(rungs(1080, false, EncoderMode.VAAPI)).containsExactly(new Rung("1080p", 1080, 5_000_000, false),
            R720, R480, R360, FLOOR);
    }

    @Test
    void theExactLadderForEachClassOfSourceWithACopyRungInVaapiMode() {
        assertThat(rungs(800, true, EncoderMode.VAAPI)).containsExactly(original(800), R720, R480, R360, FLOOR);
        assertThat(rungs(720, true, EncoderMode.VAAPI)).containsExactly(original(720), R480, R360, FLOOR);
        assertThat(rungs(576, true, EncoderMode.VAAPI)).containsExactly(original(576), R480, R360, FLOOR);
        assertThat(rungs(480, true, EncoderMode.VAAPI)).containsExactly(original(480), R360, FLOOR);
        assertThat(rungs(400, true, EncoderMode.VAAPI)).containsExactly(original(400), R360, FLOOR);
        assertThat(rungs(360, true, EncoderMode.VAAPI)).containsExactly(original(360), FLOOR);
        assertThat(rungs(300, true, EncoderMode.VAAPI)).containsExactly(original(300), FLOOR);
        assertThat(rungs(242, true, EncoderMode.VAAPI)).containsExactly(original(242), FLOOR);
        assertThat(rungs(241, true, EncoderMode.VAAPI)).containsExactly(original(241), FLOOR);
        assertThat(rungs(240, true, EncoderMode.VAAPI)).containsExactly(original(240));
        assertThat(rungs(200, true, EncoderMode.VAAPI)).containsExactly(original(200));
    }

    @Test
    void theExactLadderForEachClassOfSourceWithoutACopyRungInVaapiMode() {
        assertThat(rungs(800, false, EncoderMode.VAAPI)).containsExactly(new Rung("1080p", 800, 5_000_000, false),
            R720, R480, R360, FLOOR);
        assertThat(rungs(720, false, EncoderMode.VAAPI)).containsExactly(R720, R480, R360, FLOOR);
        assertThat(rungs(576, false, EncoderMode.VAAPI)).containsExactly(new Rung("720p", 576, 2_500_000, false),
            R480, R360, FLOOR);
        assertThat(rungs(480, false, EncoderMode.VAAPI)).containsExactly(R480, R360, FLOOR);
        assertThat(rungs(400, false, EncoderMode.VAAPI)).containsExactly(new Rung("480p", 400, 1_000_000, false),
            R360, FLOOR);
        assertThat(rungs(360, false, EncoderMode.VAAPI)).containsExactly(R360, FLOOR);
        assertThat(rungs(300, false, EncoderMode.VAAPI)).containsExactly(new Rung("360p", 300, 600_000, false),
            FLOOR);
        assertThat(rungs(242, false, EncoderMode.VAAPI)).containsExactly(new Rung("360p", 242, 600_000, false),
            FLOOR);
        assertThat(rungs(241, false, EncoderMode.VAAPI)).containsExactly(new Rung("240p", 240, 400_000, false));
        assertThat(rungs(240, false, EncoderMode.VAAPI)).containsExactly(new Rung("240p", 240, 400_000, false));
        assertThat(rungs(200, false, EncoderMode.VAAPI)).containsExactly(new Rung("240p", 200, 400_000, false));
    }

    @Test
    void theExactLadderForEachClassOfSourceInSoftwareMode() {
        assertThat(rungs(1080, true, EncoderMode.SOFTWARE)).containsExactly(original(1080), R480, R360, FLOOR);
        assertThat(rungs(1080, false, EncoderMode.SOFTWARE)).containsExactly(R480, R360, FLOOR);
        assertThat(rungs(800, false, EncoderMode.SOFTWARE)).containsExactly(R480, R360, FLOOR);
        assertThat(rungs(720, true, EncoderMode.SOFTWARE)).containsExactly(original(720), R480, R360, FLOOR);
        assertThat(rungs(576, false, EncoderMode.SOFTWARE)).containsExactly(R480, R360, FLOOR);
        assertThat(rungs(480, false, EncoderMode.SOFTWARE)).containsExactly(R480, R360, FLOOR);
        assertThat(rungs(480, true, EncoderMode.SOFTWARE)).containsExactly(original(480), R360, FLOOR);
        assertThat(rungs(400, false, EncoderMode.SOFTWARE)).containsExactly(new Rung("480p", 400, 1_000_000, false),
            R360, FLOOR);
        assertThat(rungs(360, false, EncoderMode.SOFTWARE)).containsExactly(R360, FLOOR);
        assertThat(rungs(360, true, EncoderMode.SOFTWARE)).containsExactly(original(360), FLOOR);
        assertThat(rungs(300, false, EncoderMode.SOFTWARE)).containsExactly(new Rung("360p", 300, 600_000, false),
            FLOOR);
        assertThat(rungs(241, false, EncoderMode.SOFTWARE)).containsExactly(new Rung("240p", 240, 400_000, false));
        assertThat(rungs(240, false, EncoderMode.SOFTWARE)).containsExactly(new Rung("240p", 240, 400_000, false));
        assertThat(rungs(240, true, EncoderMode.SOFTWARE)).containsExactly(original(240));
        assertThat(rungs(200, false, EncoderMode.SOFTWARE)).containsExactly(new Rung("240p", 200, 400_000, false));
    }

    @Test
    void aTallSourceIsCappedAt1080() {
        assertThat(heights(2160, false, EncoderMode.VAAPI)).containsExactly(1080, 720, 480, 360, 240);
        assertThat(heights(2160, true, EncoderMode.VAAPI)).containsExactly(2160, 720, 480, 360, 240);
    }

    @Test
    void anOddSourceHeightIsRoundedDownToEvenForTheTranscodedTop() {
        assertThat(heights(817, false, EncoderMode.VAAPI).get(0)).isEqualTo(816);
        assertThat(heights(817, true, EncoderMode.VAAPI).get(0)).isEqualTo(817);
    }

    @Test
    void theTopRungNameFollowsItsHeightAtEveryThreshold() {
        assertThat(ids(722, false, EncoderMode.VAAPI).get(0)).isEqualTo("1080p");
        assertThat(ids(720, false, EncoderMode.VAAPI).get(0)).isEqualTo("720p");
        assertThat(ids(482, false, EncoderMode.VAAPI).get(0)).isEqualTo("720p");
        assertThat(ids(480, false, EncoderMode.VAAPI).get(0)).isEqualTo("480p");
        assertThat(ids(362, false, EncoderMode.VAAPI).get(0)).isEqualTo("480p");
        assertThat(ids(360, false, EncoderMode.VAAPI).get(0)).isEqualTo("360p");
        assertThat(ids(242, false, EncoderMode.VAAPI).get(0)).isEqualTo("360p");
        assertThat(ids(240, false, EncoderMode.VAAPI).get(0)).isEqualTo("240p");
    }

    @Test
    void theTopRungBitrateFollowsItsName() {
        assertThat(Ladder.rungs(1080, 0, false, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(5_000_000);
        assertThat(Ladder.rungs(720, 0, false, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(2_500_000);
        assertThat(Ladder.rungs(480, 0, false, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(1_000_000);
        assertThat(Ladder.rungs(360, 0, false, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(600_000);
        assertThat(Ladder.rungs(240, 0, false, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(400_000);
    }

    @Test
    void aTranscodedRungExistsOnlyStrictlyBelowTheSourceHeight() {
        assertThat(ids(721, true, EncoderMode.VAAPI)).containsExactly("original", "720p", "480p", "360p", "240p");
        assertThat(ids(720, true, EncoderMode.VAAPI)).containsExactly("original", "480p", "360p", "240p");
        assertThat(ids(481, true, EncoderMode.VAAPI)).containsExactly("original", "480p", "360p", "240p");
        assertThat(ids(480, true, EncoderMode.VAAPI)).containsExactly("original", "360p", "240p");
        assertThat(ids(361, true, EncoderMode.VAAPI)).containsExactly("original", "360p", "240p");
        assertThat(ids(360, true, EncoderMode.VAAPI)).containsExactly("original", "240p");
        assertThat(ids(241, true, EncoderMode.VAAPI)).containsExactly("original", "240p");
        assertThat(ids(240, true, EncoderMode.VAAPI)).containsExactly("original");
    }

    @Test
    void theFloorIsPresentForEverySourceAbove240LinesAndAbsentAtOrBelow() {
        for (EncoderMode mode : new EncoderMode[] {EncoderMode.VAAPI, EncoderMode.SOFTWARE}) {
            for (boolean copyOk : new boolean[] {true, false}) {
                for (int height = 2; height <= 2200; height++) {
                    // a non-copied 241-line source is transcoded at 240 lines: its single 240p rung is the 400k top
                    boolean expected = copyOk ? height > 240 : height > 241;
                    assertThat(rungs(height, copyOk, mode).contains(FLOOR)).as("%s %s %d", mode, copyOk, height)
                        .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void theFloorIsAlwaysTheLastRungWhenPresent() {
        for (int height = 241; height <= 2200; height++) {
            List<Rung> rungs = rungs(height, true, EncoderMode.VAAPI);
            assertThat(rungs.get(rungs.size() - 1)).as("%d", height).isEqualTo(FLOOR);
        }
    }

    @Test
    void heightsAreUniqueForEverySourceHeightAndMode() {
        for (EncoderMode mode : EncoderMode.values()) {
            for (boolean copyOk : new boolean[] {true, false}) {
                for (int height = 2; height <= 2200; height++) {
                    List<Integer> heights = heights(height, copyOk, mode);
                    assertThat(heights).as("%s %s %d", mode, copyOk, height).doesNotHaveDuplicates()
                        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
                }
            }
        }
    }

    @Test
    void withoutAnEncoderOnlyTheCopyRungRemains() {
        assertThat(ids(1080, true, EncoderMode.UNAVAILABLE)).containsExactly("original");
        assertThat(ids(1080, false, EncoderMode.UNAVAILABLE)).isEmpty();
    }

    @Test
    void theCopyRungCarriesTheSourceBitrateOrADefaultWhenUnknown() {
        assertThat(Ladder.rungs(1080, 7_000_000, true, EncoderMode.VAAPI).get(0).videoBitrate())
            .isEqualTo(7_000_000);
        assertThat(Ladder.rungs(1080, 0, true, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(5_000_000);
        assertThat(Ladder.rungs(1080, 1, true, EncoderMode.VAAPI).get(0).videoBitrate()).isEqualTo(1);
    }

    @Test
    void aHeightBelowTwoIsRejected() {
        assertThatThrownBy(() -> Ladder.rungs(1, 0, true, EncoderMode.VAAPI))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("source height must be at least 2");
        assertThat(Ladder.rungs(2, 0, true, EncoderMode.VAAPI)).hasSize(1);
    }

    @Test
    void qualityIdsAreTheSevenTheApiAccepts() {
        assertThat(Ladder.QUALITY_IDS).containsExactly("auto", "original", "1080p", "720p", "480p", "360p", "240p");
    }

    @Test
    void audioIs64kOnTheFloor96kOn360pAndTheSingle240pAnd128kOnTheRest() {
        assertThat(new Rung("original", 1080, 9_000_000, true).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("original", 240, 1, true).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("original", 240, 250_000, true).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("720p", 720, 2_500_000, false).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("480p", 480, 1_000_000, false).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("360p", 360, 600_000, false).audioBitrate()).isEqualTo(96_000);
        assertThat(new Rung("360p", 300, 600_000, false).audioBitrate()).isEqualTo(96_000);
        assertThat(new Rung("240p", 240, 400_000, false).audioBitrate()).isEqualTo(96_000);
        assertThat(new Rung("240p", 240, 250_001, false).audioBitrate()).isEqualTo(96_000);
        assertThat(new Rung("240p", 240, 250_000, false).audioBitrate()).isEqualTo(64_000);
        assertThat(FLOOR.audioBitrate()).isEqualTo(64_000);
    }

    @Test
    void theFloorConstantsAre250kVideoAnd64kAudio() {
        assertThat(Rung.FLOOR_VIDEO_BITRATE).isEqualTo(250_000);
        assertThat(Rung.FLOOR_AUDIO_BITRATE).isEqualTo(64_000);
    }

    @Test
    void bandwidthIsTheTranscodedTargetTimes1Point2ForPeakAndTheCopyAverageTimes1Point5() {
        Rung transcoded = new Rung("720p", 720, 2_500_000, false);
        Rung copy = new Rung("original", 1080, 8_000_000, true);

        assertThat(transcoded.peakBandwidth()).isEqualTo(3_000_000 + 128_000);
        assertThat(transcoded.averageBandwidth()).isEqualTo(2_500_000 + 128_000);
        assertThat(copy.peakBandwidth()).isEqualTo(12_000_000 + 128_000);
        assertThat(copy.averageBandwidth()).isEqualTo(8_000_000 + 128_000);
        assertThat(R360.peakBandwidth()).isEqualTo(720_000 + 96_000);
    }

    @Test
    void theFloorsAverageBandwidthIs314kAndItsPeakIs364k() {
        assertThat(FLOOR.averageBandwidth()).isEqualTo(314_000);
        assertThat(FLOOR.peakBandwidth()).isEqualTo(364_000);
    }

    @Test
    void forSourceIsNotCalledForTheCutListWhenTheFormatRulesOutCopying() {
        List<Rung> rungs = Ladder.forSource(1080, 9_000_000, "HEVC", "yuv420p", () -> {
            throw new AssertionError("the cut list must not be read");
        }, EncoderMode.VAAPI);

        assertThat(rungs).extracting(Rung::id).containsExactly("1080p", "720p", "480p", "360p",
            "240p");
    }

    @Test
    void the240pIdHasTwoBitratesAndNeverBothInOneFilm() {
        for (int height = 2; height <= 2200; height++) {
            for (boolean copyOk : new boolean[] {true, false}) {
                List<Long> bitrates = rungs(height, copyOk, EncoderMode.VAAPI).stream()
                    .filter(rung -> rung.id().equals("240p")).map(Rung::videoBitrate).toList();
                assertThat(bitrates).as("%s %d", copyOk, height).hasSizeLessThanOrEqualTo(1);
            }
        }
    }
}
