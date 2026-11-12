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

    /** A single-file result, ranked below {@code id} 1 on downloads, with a release name and a file name. */
    private static OpenSubtitlesResult named(long id, String release, String fileName, int downloads) {
        return new OpenSubtitlesResult(id, id + 1000, release, 23.976, downloads, true, false, null, null, 1,
            fileName);
    }

    private static List<Long> ids(List<OpenSubtitlesResult> results) {
        return results.stream().map(OpenSubtitlesResult::id).toList();
    }

    @Test
    void rankGivesTheFilmLevelOrderInFull() {
        assertThat(ids(OpenSubtitlesRanking.rank(List.of(result(3, 25.0, false, false, 1),
            result(1, 23.976, false, false, 1), split(2, 2))))).containsExactly(1L, 3L, 2L);
    }

    @Test
    void anEditionThatSaysExtendedRanksExtendedThenUntaggedThenTheatrical() {
        OpenSubtitlesResult theatrical = named(1, "Rings.2003.Theatrical.BluRay", null, 9_000);
        OpenSubtitlesResult plain = named(2, "Rings.2003.BluRay", null, 500);
        OpenSubtitlesResult extended = named(3, "Rings.2003.Extended.BluRay", null, 10);

        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(theatrical, plain, extended), "Extended Edition")))
            .containsExactly(3L, 2L, 1L);
    }

    @Test
    void anEditionThatSaysTheatricalRanksTheatricalThenUntaggedThenExtended() {
        OpenSubtitlesResult theatrical = named(1, "Rings.2003.Theatrical.BluRay", null, 10);
        OpenSubtitlesResult plain = named(2, "Rings.2003.BluRay", null, 500);
        OpenSubtitlesResult extended = named(3, "Rings.2003.Extended.BluRay", null, 9_000);

        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(extended, plain, theatrical), "Theatrical Cut")))
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    void theFileNameCountsAsMuchAsTheReleaseName() {
        OpenSubtitlesResult plain = named(1, "Rings.2003.BluRay", "rings.srt", 9_000);
        OpenSubtitlesResult byFile = named(2, "Rings.2003.BluRay", "Rings.Extended.srt", 10);

        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(plain, byFile), "Extended"))).containsExactly(2L, 1L);
    }

    @Test
    void anEditionWithNoTagKeepsTheFilmLevelOrder() {
        OpenSubtitlesResult theatrical = named(1, "Rings.2003.Theatrical.BluRay", null, 10);
        OpenSubtitlesResult extended = named(2, "Rings.2003.Extended.BluRay", null, 9_000);
        OpenSubtitlesResult plain = named(3, "Rings.2003.BluRay", null, 500);

        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(theatrical, extended, plain), "Original")))
            .isEqualTo(ids(OpenSubtitlesRanking.rank(List.of(theatrical, extended, plain))));
        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(theatrical, extended, plain), null)))
            .containsExactly(2L, 3L, 1L);
    }

    @Test
    void aResultWithBothTagsFitsAnExtendedEditionNoBetterThanOneWithJustExtended() {
        OpenSubtitlesResult both = named(1, "Rings.Extended.Theatrical.2003", null, 9_000);
        OpenSubtitlesResult extended = named(2, "Rings.Extended.2003", null, 10);

        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(both, extended), "Extended Edition")))
            .containsExactly(2L, 1L);
    }

    @Test
    void aSingleFileResultStillBeatsAMultiFileOneOfTheRightCut() {
        OpenSubtitlesResult single = named(1, "Rings.2003.Theatrical.BluRay", null, 10);
        OpenSubtitlesResult splitExtended = new OpenSubtitlesResult(2, 1002, "Rings.Extended", 23.976, 9_000, true,
            false, null, null, 2, null);

        assertThat(ids(OpenSubtitlesRanking.rankFor(List.of(splitExtended, single), "Extended Edition")))
            .containsExactly(2L, 1L);
    }

    @Test
    void candidatesAreTheTopThreeSingleFileResultsInRankedOrder() {
        List<OpenSubtitlesResult> results = List.of(named(5, "r5", null, 10), named(1, "r1", null, 500),
            split(9, 2), named(3, "r3", null, 100), named(2, "r2", null, 300), named(4, "r4", null, 50));

        assertThat(ids(OpenSubtitlesRanking.candidates(results))).containsExactly(1L, 2L, 3L);
    }

    @Test
    void candidatesAlsoKeepTheBestResultOfEachCutTagEvenWhenItIsNotInTheTopThree() {
        List<OpenSubtitlesResult> results = List.of(named(1, "r1", null, 900), named(2, "r2", null, 800),
            named(3, "r3", null, 700), named(4, "r4.Extended", null, 600), named(5, "r5.Extended", null, 500),
            named(6, "r6.Theatrical", null, 400), named(7, "r7.Directors.Cut.Unrated", null, 300));

        assertThat(ids(OpenSubtitlesRanking.candidates(results))).containsExactly(1L, 2L, 3L, 4L, 6L, 7L);
    }

    @Test
    void candidatesNeverExceedEightAndAreEmptyWhenNothingIsSingleFile() {
        List<OpenSubtitlesResult> tagged = new java.util.ArrayList<>();
        String[] words = {"Extended", "Directors.Cut", "Special.Edition", "Unrated", "Theatrical"};
        for (int i = 0; i < 12; i++) {
            tagged.add(named(i + 1, i < 7 ? "plain" + i : words[i - 7], null, 1_000 - i));
        }

        assertThat(OpenSubtitlesRanking.candidates(tagged)).hasSize(8);
        assertThat(OpenSubtitlesRanking.MAX_CANDIDATES).isEqualTo(8);
        assertThat(OpenSubtitlesRanking.TOP_CANDIDATES).isEqualTo(3);
        assertThat(OpenSubtitlesRanking.candidates(List.of(split(1, 2), split(2, 3)))).isEmpty();
        assertThat(OpenSubtitlesRanking.candidates(List.of())).isEmpty();
    }
}
