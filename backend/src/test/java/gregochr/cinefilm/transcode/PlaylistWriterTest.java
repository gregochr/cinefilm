package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaylistWriterTest {

    private static final List<Rung> RUNGS = List.of(new Rung("original", 1080, 8_000_000, true),
        new Rung("720p", 720, 2_500_000, false), new Rung("360p", 360, 600_000, false));

    @Test
    void aMediaPlaylistIsAVodPlaylistWithExactDurationsAndAnEndlist() {
        CutList cuts = CutList.parse("0,4004,8100", 10_000);

        assertThat(PlaylistWriter.media(cuts)).isEqualTo("""
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXTINF:4.004,
            seg-0.ts
            #EXTINF:4.096,
            seg-1.ts
            #EXTINF:1.900,
            seg-2.ts
            #EXT-X-ENDLIST
            """);
    }

    @Test
    void theTargetDurationIsTheLongestSegmentRoundedUp() {
        assertThat(PlaylistWriter.media(CutList.parse("0,4000", 12_000))).contains("#EXT-X-TARGETDURATION:8\n");
        assertThat(PlaylistWriter.media(CutList.parse("0,4000", 12_001))).contains("#EXT-X-TARGETDURATION:9\n");
        assertThat(PlaylistWriter.media(CutList.parse("0", 4_000))).contains("#EXT-X-TARGETDURATION:4\n");
        assertThat(PlaylistWriter.media(CutList.parse("0", 4_001))).contains("#EXT-X-TARGETDURATION:5\n");
    }

    @Test
    void aSingleSegmentFilmHasOneEntry() {
        assertThat(PlaylistWriter.media(CutList.parse("0", 900)))
            .contains("#EXTINF:0.900,\nseg-0.ts\n#EXT-X-ENDLIST\n");
    }

    @Test
    void segmentNamesAreSegDashNumberDotTs() {
        assertThat(PlaylistWriter.segmentName(123)).isEqualTo("seg-123.ts");
    }

    @Test
    void theMasterListsTheStartRungFirstThenTheOthersInLadderOrder() {
        String master = PlaylistWriter.master(RUNGS, RUNGS.get(1), null, 1920, 1080);

        assertThat(master).isEqualTo(String.join("\n",
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-STREAM-INF:BANDWIDTH=3128000,AVERAGE-BANDWIDTH=2628000,RESOLUTION=1280x720,"
                + "CODECS=\"avc1.64001f,mp4a.40.2\"",
            "720p/index.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=12128000,AVERAGE-BANDWIDTH=8128000,RESOLUTION=1920x1080,"
                + "CODECS=\"avc1.640028,mp4a.40.2\"",
            "original/index.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=816000,AVERAGE-BANDWIDTH=696000,RESOLUTION=640x360,"
                + "CODECS=\"avc1.64001e,mp4a.40.2\"",
            "360p/index.m3u8", ""));
    }

    @Test
    void aPinnedQualityListsOnlyThatRung() {
        String master = PlaylistWriter.master(RUNGS, RUNGS.get(0), "360p", 1920, 1080);

        assertThat(master).contains("360p/index.m3u8").doesNotContain("original/").doesNotContain("720p/");
        assertThat(master.split("#EXT-X-STREAM-INF", -1)).hasSize(2);
    }

    @Test
    void aPinnedQualityThatIsNotARungIsRejected() {
        assertThatThrownBy(() -> PlaylistWriter.master(RUNGS, RUNGS.get(0), "480p", 1920, 1080))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("No rung 480p");
    }

    @Test
    void theWidthKeepsTheAspectRatioAndIsEven() {
        assertThat(PlaylistWriter.width(720, 1920, 820)).isEqualTo(1686);
        assertThat(PlaylistWriter.width(480, 1920, 1080)).isEqualTo(852);
        assertThat(PlaylistWriter.width(360, 720, 576)).isEqualTo(450);
        assertThat(PlaylistWriter.width(1080, 1920, 1080)).isEqualTo(1920);
    }

    @Test
    void theCodecLevelFollowsTheHeightAtEachBoundary() {
        assertThat(PlaylistWriter.codecs(new Rung("a", 721, 1, false))).isEqualTo("avc1.640028,mp4a.40.2");
        assertThat(PlaylistWriter.codecs(new Rung("a", 720, 1, false))).isEqualTo("avc1.64001f,mp4a.40.2");
        assertThat(PlaylistWriter.codecs(new Rung("a", 481, 1, false))).isEqualTo("avc1.64001f,mp4a.40.2");
        assertThat(PlaylistWriter.codecs(new Rung("a", 480, 1, false))).isEqualTo("avc1.64001e,mp4a.40.2");
    }
}
