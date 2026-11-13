package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The order for full subtitles: as the forced order, but hearing-impaired files rank below every other one. */
class OpenSubtitlesRankingFullTest {

    private static OpenSubtitlesResult result(long id, String release, Double fps, boolean trusted,
                                              boolean hearingImpaired, int downloads) {
        return new OpenSubtitlesResult(id, id + 1000, release, fps, downloads, trusted, hearingImpaired, null, null);
    }

    private static List<Long> ids(List<OpenSubtitlesResult> results) {
        return results.stream().map(OpenSubtitlesResult::id).toList();
    }

    @Test
    void aHearingImpairedFileRanksBelowEveryOtherWhateverItsFrameRateTrustOrDownloads() {
        OpenSubtitlesResult strongHi = result(1, "a", 23.976, true, true, 9_999);
        OpenSubtitlesResult weak = result(2, "b", 30.0, false, false, 0);
        OpenSubtitlesResult noFps = result(3, "c", null, false, false, 0);

        assertThat(ids(OpenSubtitlesRanking.fullCandidates(List.of(strongHi, weak, noFps))))
            .containsExactly(2L, 3L, 1L);
    }

    @Test
    void theForcedOrderIsUnchanged() {
        OpenSubtitlesResult strongHi = result(1, "a", 23.976, true, true, 9_999);
        OpenSubtitlesResult weak = result(2, "b", 30.0, false, false, 0);

        assertThat(ids(OpenSubtitlesRanking.candidates(List.of(weak, strongHi)))).containsExactly(1L, 2L);
    }

    @Test
    void amongFilesThatAreNotHearingImpairedTheUsualOrderApplies() {
        OpenSubtitlesResult trusted = result(1, "a", 23.976, true, false, 1);
        OpenSubtitlesResult popular = result(2, "b", 23.976, false, false, 500);
        OpenSubtitlesResult nearer = result(3, "c", 24.0, true, false, 900);

        assertThat(ids(OpenSubtitlesRanking.fullCandidates(List.of(nearer, popular, trusted))))
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    void multiDiscResultsAreNeverCandidates() {
        OpenSubtitlesResult split = new OpenSubtitlesResult(1, 1001, "a", 23.976, 0, true, false, null, null, 2);

        assertThat(OpenSubtitlesRanking.fullCandidates(List.of(split))).isEmpty();
    }

    @Test
    void theBestOfEachCutTagIsKeptBeyondTheTopThreeWithHearingImpairedFilesLast() {
        OpenSubtitlesResult a = result(1, "Film.2008", 23.976, true, false, 100);
        OpenSubtitlesResult b = result(2, "Film.2008.v2", 23.976, false, false, 90);
        OpenSubtitlesResult c = result(3, "Film.2008.v3", 23.976, false, false, 80);
        OpenSubtitlesResult extendedHi = result(4, "Film.Extended.2008", 23.976, false, true, 70);
        OpenSubtitlesResult extended = result(5, "Film.Extended.2008", 25.0, false, false, 1);

        assertThat(ids(OpenSubtitlesRanking.fullCandidates(List.of(a, b, c, extendedHi, extended))))
            .containsExactly(1L, 2L, 3L, 5L);
    }

    @Test
    void forAnEditionTheCutFitComesFirstThenHearingImpairedLast() {
        OpenSubtitlesResult plain = result(1, "Film.2008", 23.976, true, false, 100);
        OpenSubtitlesResult extendedHi = result(2, "Film.Extended.2008", 23.976, true, true, 100);
        OpenSubtitlesResult extended = result(3, "Film.Extended.2008", 25.0, false, false, 1);

        assertThat(ids(OpenSubtitlesRanking.rankForFull(List.of(plain, extendedHi, extended), "Extended Edition")))
            .containsExactly(3L, 2L, 1L);
        assertThat(ids(OpenSubtitlesRanking.rankForFull(List.of(plain, extendedHi, extended), null)))
            .containsExactly(1L, 3L, 2L);
    }
}
