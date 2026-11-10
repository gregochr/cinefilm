package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The media playlist of a split film: one timeline, a discontinuity before every later part. */
class PlaylistWriterPartsTest {

    private static final CutList PART_1 = CutList.parse("0,4000", 8_000);
    private static final CutList PART_2 = CutList.parse("0,4004", 8_004);
    private static final CutList PART_3 = CutList.parse("0", 3_000);

    @Test
    void aDiscontinuityPrecedesTheFirstSegmentOfEveryLaterPartAndNothingElse() {
        assertThat(PlaylistWriter.media(CutList.join(List.of(PART_1, PART_2, PART_3)))).isEqualTo("""
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXTINF:4.000,
            seg-0.ts
            #EXTINF:4.000,
            seg-1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4.004,
            seg-2.ts
            #EXTINF:4.000,
            seg-3.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:3.000,
            seg-4.ts
            #EXT-X-ENDLIST
            """);
    }

    @Test
    void theDiscontinuitiesAreTheSameInEveryRungBecauseEveryRungListsTheSameCutList() {
        CutList film = CutList.join(List.of(PART_1, PART_2));

        String first = PlaylistWriter.media(film);

        assertThat(first.lines().filter("#EXT-X-DISCONTINUITY"::equals)).hasSize(1);
        assertThat(PlaylistWriter.media(film)).isEqualTo(first);
        assertThat(first).contains("seg-1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4.004,\nseg-2.ts\n");
    }

    @Test
    void aFilmInOnePieceHasNoDiscontinuityEvenWhenJoinedFromOnePart() {
        assertThat(PlaylistWriter.media(PART_1)).doesNotContain("DISCONTINUITY");
        assertThat(PlaylistWriter.media(CutList.join(List.of(PART_1)))).doesNotContain("DISCONTINUITY");
    }
}
