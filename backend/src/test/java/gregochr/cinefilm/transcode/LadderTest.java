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

    @Test
    void aFullHdFileWithACopyRungHasOriginal720p480pAnd360p() {
        List<Rung> rungs = Ladder.rungs(1080, 9_000_000, true, EncoderMode.VAAPI);

        assertThat(rungs).containsExactly(new Rung("original", 1080, 9_000_000, true),
            new Rung("720p", 720, 2_500_000, false), new Rung("480p", 480, 1_000_000, false),
            new Rung("360p", 360, 600_000, false));
    }

    @Test
    void withoutACopyRungTheTopRungIsTranscodedAtTheSourceHeightNamedByHeight() {
        List<Rung> rungs = Ladder.rungs(820, 9_000_000, false, EncoderMode.VAAPI);

        assertThat(rungs).containsExactly(new Rung("1080p", 820, 5_000_000, false),
            new Rung("720p", 720, 2_500_000, false), new Rung("480p", 480, 1_000_000, false),
            new Rung("360p", 360, 600_000, false));
    }

    @Test
    void aTallSourceIsCappedAt1080() {
        assertThat(heights(2160, false, EncoderMode.VAAPI)).containsExactly(1080, 720, 480, 360);
        assertThat(heights(2160, true, EncoderMode.VAAPI)).containsExactly(2160, 720, 480, 360);
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
        assertThat(ids(721, true, EncoderMode.VAAPI)).containsExactly("original", "720p", "480p", "360p");
        assertThat(ids(720, true, EncoderMode.VAAPI)).containsExactly("original", "480p", "360p");
        assertThat(ids(481, true, EncoderMode.VAAPI)).containsExactly("original", "480p", "360p");
        assertThat(ids(480, true, EncoderMode.VAAPI)).containsExactly("original", "360p");
        assertThat(ids(361, true, EncoderMode.VAAPI)).containsExactly("original", "360p");
    }

    @Test
    void aSourceOf360LinesOrFewerGetsOnlyItsTopAnd240pIfAbove240() {
        assertThat(ids(360, true, EncoderMode.VAAPI)).containsExactly("original", "240p");
        assertThat(ids(300, true, EncoderMode.VAAPI)).containsExactly("original", "240p");
        assertThat(ids(241, true, EncoderMode.VAAPI)).containsExactly("original", "240p");
        assertThat(ids(240, true, EncoderMode.VAAPI)).containsExactly("original");
        assertThat(ids(200, true, EncoderMode.VAAPI)).containsExactly("original");
        assertThat(ids(360, false, EncoderMode.VAAPI)).containsExactly("360p", "240p");
        assertThat(ids(240, false, EncoderMode.VAAPI)).containsExactly("240p");
    }

    @Test
    void a240pRungNeverAppearsForASourceAbove360() {
        assertThat(ids(361, true, EncoderMode.VAAPI)).doesNotContain("240p");
        assertThat(ids(1080, true, EncoderMode.VAAPI)).doesNotContain("240p");
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
    void softwareKeepsOnly480p360pAnd240pTranscodedRungsAndTheCopyRungUnchanged() {
        assertThat(ids(1080, true, EncoderMode.SOFTWARE)).containsExactly("original", "480p", "360p");
        assertThat(ids(1080, false, EncoderMode.SOFTWARE)).containsExactly("480p", "360p");
        assertThat(ids(800, false, EncoderMode.SOFTWARE)).containsExactly("480p", "360p");
        assertThat(ids(480, false, EncoderMode.SOFTWARE)).containsExactly("480p", "360p");
        assertThat(ids(300, false, EncoderMode.SOFTWARE)).containsExactly("360p", "240p");
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
    void audioIs128kExceptOn360pAnd240pWhichGet96k() {
        assertThat(new Rung("original", 1080, 1, true).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("720p", 720, 1, false).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("480p", 480, 1, false).audioBitrate()).isEqualTo(128_000);
        assertThat(new Rung("360p", 360, 1, false).audioBitrate()).isEqualTo(96_000);
        assertThat(new Rung("240p", 240, 1, false).audioBitrate()).isEqualTo(96_000);
    }

    @Test
    void bandwidthIsTheTranscodedTargetTimes1Point2ForPeakAndTheCopyAverageTimes1Point5() {
        Rung transcoded = new Rung("720p", 720, 2_500_000, false);
        Rung copy = new Rung("original", 1080, 8_000_000, true);

        assertThat(transcoded.peakBandwidth()).isEqualTo(3_000_000 + 128_000);
        assertThat(transcoded.averageBandwidth()).isEqualTo(2_500_000 + 128_000);
        assertThat(copy.peakBandwidth()).isEqualTo(12_000_000 + 128_000);
        assertThat(copy.averageBandwidth()).isEqualTo(8_000_000 + 128_000);
        assertThat(new Rung("360p", 360, 600_000, false).peakBandwidth()).isEqualTo(720_000 + 96_000);
    }

    @Test
    void forSourceIsNotCalledForTheCutListWhenTheFormatRulesOutCopying() {
        List<Rung> rungs = Ladder.forSource(1080, 9_000_000, "HEVC", "yuv420p", () -> {
            throw new AssertionError("the cut list must not be read");
        }, EncoderMode.VAAPI);

        assertThat(rungs).extracting(Rung::id).containsExactly("1080p", "720p", "480p", "360p");
    }
}
