package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.opensubtitles.OsFixtures.TAKEN;
import static gregochr.cinefilm.opensubtitles.OsFixtures.item;
import static gregochr.cinefilm.opensubtitles.OsFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Reading a reply to the search for full English subtitles: the opposite flag filter, hearing-impaired files kept. */
class OpenSubtitlesParserFullTest {

    private static OsFixtures.Item full(long id) {
        return item(id).set("foreign_parts_only", "false");
    }

    private static OpenSubtitlesSearch parse(Object... items) {
        return OpenSubtitlesParser.parseFull(page(items), TAKEN);
    }

    @Test
    void aFullResultIsKeptAndAForeignPartsOnlyOneIsDropped() {
        OpenSubtitlesSearch search = parse(full(1), item(2));

        assertThat(search.count()).isEqualTo(1);
        assertThat(search.best().id()).isEqualTo(1);
        assertThat(search.candidates()).extracting(OpenSubtitlesResult::id).containsExactly(1L);
    }

    @Test
    void aMissingOrNonBooleanFlagFitsNeitherSearch() {
        assertThat(parse(item(1).remove("foreign_parts_only")).count()).isZero();
        assertThat(parse(item(1).set("foreign_parts_only", "\"false\"")).count()).isZero();
        assertThat(parse(item(1).set("foreign_parts_only", "0")).count()).isZero();
    }

    @Test
    void theForcedParseStillKeepsOnlyForeignPartsOnlyResults() {
        OpenSubtitlesSearch search = OpenSubtitlesParser.parse(page(full(1), item(2)), TAKEN);

        assertThat(search.count()).isEqualTo(1);
        assertThat(search.best().id()).isEqualTo(2);
    }

    @Test
    void hearingImpairedFilesAreKeptAndMarkedButRankedLast() {
        OpenSubtitlesSearch search = parse(full(1).set("hearing_impaired", "true").set("from_trusted", "true")
            .set("download_count", "9999"), full(2));

        assertThat(search.count()).isEqualTo(2);
        assertThat(search.candidates()).extracting(OpenSubtitlesResult::id).containsExactly(2L, 1L);
        assertThat(search.candidates().get(1).hearingImpaired()).isTrue();
        assertThat(search.candidates().get(0).hearingImpaired()).isFalse();
        assertThat(search.best().id()).isEqualTo(2);
    }

    @Test
    void aHearingImpairedFileIsTheOnlyChoiceWhenNothingElseExists() {
        OpenSubtitlesSearch search = parse(full(1).set("hearing_impaired", "true"));

        assertThat(search.best().id()).isEqualTo(1);
    }

    @Test
    void machineAndAiTranslationsAndOtherFilmsAreStillDropped() {
        assertThat(parse(full(1).set("ai_translated", "true")).count()).isZero();
        assertThat(parse(full(1).set("machine_translated", "true")).count()).isZero();
        assertThat(parse(full(1).imdb("1")).count()).isZero();
    }

    @Test
    void aSubtitleSplitOverSeveralDiscsCountsButIsNeverACandidate() {
        OpenSubtitlesSearch search = parse(full(1).set("nb_cd", "2"), full(2));

        assertThat(search.count()).isEqualTo(2);
        assertThat(search.candidates()).extracting(OpenSubtitlesResult::id).containsExactly(2L);
    }

    @Test
    void onlyMultiDiscResultsLeaveNoCandidateAndNoBest() {
        OpenSubtitlesSearch search = parse(full(1).set("nb_cd", "2"));

        assertThat(search.count()).isEqualTo(1);
        assertThat(search.candidates()).isEmpty();
        assertThat(search.best()).isNull();
    }

    @Test
    void anUnreadableReplyIsAnOutageNeverNoResults() {
        assertThatThrownBy(() -> OpenSubtitlesParser.parseFull("not json", TAKEN))
            .isInstanceOf(OpenSubtitlesUnavailableException.class);
        assertThatThrownBy(() -> OpenSubtitlesParser.parseFull("{\"x\":1}", TAKEN))
            .isInstanceOf(OpenSubtitlesUnavailableException.class);
    }
}
