package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Which result is best: frame rate, trusted, not hearing-impaired, downloads, then the lowest id. */
class OpenSubtitlesRankingTest {

    private static OpenSubtitlesResult result(long id, Double fps, boolean trusted, boolean hearingImpaired,
                                              int downloads) {
        return new OpenSubtitlesResult(id, id + 1000, "release " + id, fps, downloads, trusted, hearingImpaired, null,
            null);
    }

    private static long bestOf(OpenSubtitlesResult... results) {
        return OpenSubtitlesRanking.best(List.of(results)).orElseThrow().id();
    }

    @Test
    void noResultsHaveNoBest() {
        assertThat(OpenSubtitlesRanking.best(List.of())).isEmpty();
    }

    @Test
    void aSingleResultIsTheBest() {
        assertThat(bestOf(result(7, 25.0, false, true, 0))).isEqualTo(7);
    }

    @Test
    void theFrameRateClosestTo23976Wins() {
        OpenSubtitlesResult exact = result(3, 23.976, false, true, 0);
        OpenSubtitlesResult slightlyOff = result(2, 24.0, true, false, 9_999);
        OpenSubtitlesResult far = result(1, 25.0, true, false, 9_999);

        assertThat(bestOf(far, slightlyOff, exact)).isEqualTo(3);
        assertThat(bestOf(far, slightlyOff)).isEqualTo(2);
    }

    @Test
    void aFrameRateBelowTheTargetCountsByItsDistanceToo() {
        OpenSubtitlesResult below = result(2, 23.9, false, false, 0);
        OpenSubtitlesResult above = result(1, 24.1, false, false, 0);

        assertThat(bestOf(above, below)).isEqualTo(2);
    }

    @Test
    void noFrameRateRanksBelowAnyStatedOne() {
        OpenSubtitlesResult unknown = result(1, null, true, false, 9_999);
        OpenSubtitlesResult stated = result(2, 30.0, false, true, 0);

        assertThat(bestOf(unknown, stated)).isEqualTo(2);
    }

    @Test
    void whenTheFrameRateIsTheSameATrustedUploaderWins() {
        OpenSubtitlesResult trusted = result(5, 23.976, true, true, 0);
        OpenSubtitlesResult other = result(1, 23.976, false, false, 9_999);

        assertThat(bestOf(other, trusted)).isEqualTo(5);
    }

    @Test
    void whenTrustIsTheSameANotHearingImpairedVersionWins() {
        OpenSubtitlesResult plain = result(5, 23.976, true, false, 0);
        OpenSubtitlesResult hearingImpaired = result(1, 23.976, true, true, 9_999);

        assertThat(bestOf(hearingImpaired, plain)).isEqualTo(5);
    }

    @Test
    void whenThoseAreTheSameTheMostDownloadedWins() {
        OpenSubtitlesResult popular = result(5, 23.976, true, false, 300);
        OpenSubtitlesResult lessPopular = result(1, 23.976, true, false, 299);

        assertThat(bestOf(lessPopular, popular)).isEqualTo(5);
    }

    @Test
    void whenEverythingIsTheSameTheLowestIdWinsWhateverTheOrder() {
        OpenSubtitlesResult low = result(10, 23.976, true, false, 300);
        OpenSubtitlesResult middle = result(20, 23.976, true, false, 300);
        OpenSubtitlesResult high = result(30, 23.976, true, false, 300);

        assertThat(bestOf(low, middle, high)).isEqualTo(10);
        assertThat(bestOf(high, middle, low)).isEqualTo(10);
        assertThat(bestOf(middle, high, low)).isEqualTo(10);
    }

    @Test
    void twoResultsWithNoFrameRateAreRankedByTheRestOfTheOrder() {
        OpenSubtitlesResult trusted = result(9, null, true, false, 0);
        OpenSubtitlesResult other = result(1, null, false, false, 0);

        assertThat(bestOf(other, trusted)).isEqualTo(9);
    }

    private static OpenSubtitlesResult split(long id, int files) {
        return new OpenSubtitlesResult(id, id + 1000, "release " + id, 23.976, 99_999, true, false, null, null, files);
    }

    @Test
    void aSingleFileResultBeatsAMultiFileOneWhateverElseIsBetter() {
        OpenSubtitlesResult single = result(9, 29.97, false, true, 0);

        assertThat(bestOf(split(1, 2), single, split(2, 3))).isEqualTo(9);
        assertThat(bestOf(single, split(1, 2))).isEqualTo(9);
    }

    @Test
    void whenOnlyMultiFileResultsExistTheBestOfThemIsStillReturnedSoTheCallerCanSkipTheFilm() {
        OpenSubtitlesResult best = OpenSubtitlesRanking.best(List.of(split(5, 2), split(3, 2))).orElseThrow();

        assertThat(best.id()).isEqualTo(3);
        assertThat(best.isMultiFile()).isTrue();
    }
}
