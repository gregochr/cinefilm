package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The import's pure decisions: guids, path pairing and artwork path validation. */
class PlexRulesTest {

    private static final String PREFIX = "/Volumes/Media Library/Movies/";

    @Test
    void theTmdbGuidGivesTheId() {
        assertThat(PlexRules.tmdbId(List.of("imdb://tt0113277", "tmdb://949", "tvdb://12"))).isEqualTo(949L);
    }

    @Test
    void theFirstTmdbGuidWinsWhenThereAreTwo() {
        assertThat(PlexRules.tmdbId(List.of("tmdb://5", "tmdb://6"))).isEqualTo(5L);
    }

    @Test
    void noTmdbGuidIsNull() {
        assertThat(PlexRules.tmdbId(List.of("imdb://tt1", "plex://movie/5d776"))).isNull();
        assertThat(PlexRules.tmdbId(List.of())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tmdb://", "tmdb://abc", "tmdb://0", "tmdb://-5", "tmdb://12x", " tmdb://12",
        "tmdb://1234567890123456789", "xtmdb://12", "TMDB://12"})
    void aMalformedZeroOrOversizedTmdbGuidIsIgnored(String guid) {
        assertThat(PlexRules.tmdbId(List.of(guid))).isNull();
    }

    @Test
    void aMalformedGuidDoesNotHideALaterGoodOne() {
        assertThat(PlexRules.tmdbId(List.of("tmdb://0", "tmdb://77"))).isEqualTo(77L);
    }

    @Test
    void theLargestEighteenDigitIdIsAccepted() {
        assertThat(PlexRules.tmdbId(List.of("tmdb://999999999999999999"))).isEqualTo(999_999_999_999_999_999L);
    }

    @Test
    void thePrefixGetsExactlyOneTrailingSlashAndIsNfc() {
        assertThat(PlexRules.prefix("/Volumes/Movies")).isEqualTo("/Volumes/Movies/");
        assertThat(PlexRules.prefix("/Volumes/Movies/")).isEqualTo("/Volumes/Movies/");
        assertThat(PlexRules.prefix("  /Volumes/Movies  ")).isEqualTo("/Volumes/Movies/");
        assertThat(PlexRules.prefix(Normalizer.normalize("/Café/", Normalizer.Form.NFD)))
            .isEqualTo("/Café/");
    }

    @Test
    void aBlankOrMissingPrefixStaysBlankSoNothingPairs() {
        assertThat(PlexRules.prefix("")).isEmpty();
        assertThat(PlexRules.prefix("   ")).isEmpty();
        assertThat(PlexRules.prefix(null)).isEmpty();
        assertThat(PlexRules.relativePath("", "/anything/Heat.m4v")).isEmpty();
    }

    @Test
    void aFileUnderThePrefixMapsToItsRelativePath() {
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Heat (1995).m4v")).contains("Heat (1995).m4v");
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Sub/Heat.m4v")).contains("Sub/Heat.m4v");
    }

    @Test
    void aFileOutsideThePrefixIsIgnored() {
        assertThat(PlexRules.relativePath(PREFIX, "/Volumes/Media Library/Other/Heat.m4v")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, "/Volumes/Media Library/Movies")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, "Heat.m4v")).isEmpty();
    }

    @Test
    void thePrefixItselfIsNotAFile() {
        assertThat(PlexRules.relativePath(PREFIX, PREFIX)).isEmpty();
    }

    @Test
    void aFileUnderPlexVersionsIsIgnoredAtAnyDepth() {
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Plex Versions/Optimized/Heat.m4v")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Sub/Plex Versions/Heat.m4v")).isEmpty();
    }

    @Test
    void aFolderThatMerelyContainsTheWordsIsNotPlexVersions() {
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Plex Versions Extra/Heat.m4v"))
            .contains("Plex Versions Extra/Heat.m4v");
    }

    @Test
    void dotDotDotAndEmptySegmentsAreRefused() {
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "../Heat.m4v")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Sub/../Heat.m4v")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "./Heat.m4v")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Sub//Heat.m4v")).isEmpty();
        assertThat(PlexRules.relativePath(PREFIX, PREFIX + "Sub/")).isEmpty();
    }

    @Test
    void decomposedAndComposedNamesPairBecauseBothAreComparedInNfc() {
        String composed = "Amélie (2001).m4v";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo(composed);

        Optional<String> fromPlexNfd = PlexRules.relativePath(PREFIX, PREFIX + decomposed);
        Optional<String> fromPlexNfc = PlexRules.relativePath(PREFIX, PREFIX + composed);

        assertThat(fromPlexNfd).contains(composed);
        assertThat(fromPlexNfc).contains(composed);
        assertThat(PlexRules.nfc(decomposed)).isEqualTo(PlexRules.nfc(composed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/library/metadata/123/thumb/1699999999", "/library/metadata/1/art/2",
        "/library/metadata/987654/thumb/0"})
    void aPlainLibraryArtworkPathIsValid(String path) {
        assertThat(PlexRules.isArtPath(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://evil.example/library/metadata/1/thumb/2", "https://x/y.jpg",
        "/library/metadata/1/thumb/2/../../..", "/library/metadata/1/../2/thumb/3",
        "/library/metadata/1/thumb/2?X-Plex-Token=abc", "/library/metadata/1/thumb/2#x",
        "//evil.example/library/metadata/1/thumb/2", "/library//metadata/1/thumb/2",
        "/library/metadata/1/thumb/%32", "/library/metadata/%31/thumb/2", "/library/metadata/1/thumb%2f2",
        "/library/metadata/1/poster/2", "/library/metadata//thumb/2", "/library/metadata/1/thumb/",
        "/library/metadata/x/thumb/2", "/library/metadata/1/thumb/2/", " /library/metadata/1/thumb/2",
        "/library/metadata/1/thumb/2 ", "/library/metadata/1/thumb/2\n", "upload://posters/abc", "", " "})
    void anythingElseIsNotAnArtworkPath(String path) {
        assertThat(PlexRules.isArtPath(path)).isFalse();
    }

    @Test
    void aMissingArtworkPathIsNotValid() {
        assertThat(PlexRules.isArtPath(null)).isFalse();
    }

    @Test
    void arabicIndicDigitsAreNotDigits() {
        assertThat(PlexRules.isArtPath("/library/metadata/١٢/thumb/1")).isFalse();
    }
}
