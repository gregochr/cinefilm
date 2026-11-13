package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SidecarNames.Named;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SidecarNamesTest {

    private static Named forced(String stem) {
        return new Named(SubtitleKind.FORCED, stem);
    }

    private static Named full(String stem) {
        return new Named(SubtitleKind.FULL, stem);
    }

    @Test
    void bothForcedSuffixesInAnyCaseNameAForcedSubtitleWithTheSameStem() {
        assertThat(SidecarNames.classify("Taken.en.forced.srt")).contains(forced("Taken"));
        assertThat(SidecarNames.classify("Taken.forced.en.srt")).contains(forced("Taken"));
        assertThat(SidecarNames.classify("TAKEN.EN.FORCED.SRT")).contains(forced("TAKEN"));
        assertThat(SidecarNames.classify("Taken.Forced.EN.Srt")).contains(forced("Taken"));
        assertThat(SidecarNames.classify("X.forced.eng.srt")).contains(forced("X"));
        assertThat(SidecarNames.classify("X.eng.forced.srt")).contains(forced("X"));
    }

    @Test
    void englishWithNoForcedTokenIsAFullSubtitle() {
        assertThat(SidecarNames.classify("X.en.srt")).contains(full("X"));
        assertThat(SidecarNames.classify("X.eng.srt")).contains(full("X"));
        assertThat(SidecarNames.classify("X.EN.SRT")).contains(full("X"));
    }

    @Test
    void oneHearingImpairedMarkerBeforeOrAfterTheLanguageStillMakesAFullSubtitle() {
        assertThat(SidecarNames.classify("X.en.sdh.srt")).contains(full("X"));
        assertThat(SidecarNames.classify("X.sdh.en.srt")).contains(full("X"));
        assertThat(SidecarNames.classify("X.en.hi.srt")).contains(full("X"));
        assertThat(SidecarNames.classify("X.cc.eng.srt")).contains(full("X"));
    }

    @Test
    void anotherLanguageNoLanguageOrAMarkerAloneIsNotASubtitleWeUse() {
        assertThat(SidecarNames.classify("X.fr.srt")).isEmpty();
        assertThat(SidecarNames.classify("X.srt")).isEmpty();
        assertThat(SidecarNames.classify("X.forced.srt")).isEmpty();
        assertThat(SidecarNames.classify("X.sdh.srt")).isEmpty();
        assertThat(SidecarNames.classify("X.fr.en.srt")).contains(full("X.fr"));
        assertThat(SidecarNames.classify("X.en.sdh.hi.srt")).isEmpty();
        assertThat(SidecarNames.classify("X.en.eng.srt")).contains(full("X.en"));
    }

    @Test
    void otherFileNamesAreNotSubtitles() {
        assertThat(SidecarNames.classify("Taken.en.forced.srt.bak")).isEmpty();
        assertThat(SidecarNames.classify("Taken.en.sub")).isEmpty();
        assertThat(SidecarNames.classify("Taken.m4v")).isEmpty();
    }

    @Test
    void aBareSuffixWithNoStemIsNotASubtitle() {
        assertThat(SidecarNames.classify(".en.srt")).isEmpty();
        assertThat(SidecarNames.classify(".en.forced.srt")).isEmpty();
        assertThat(SidecarNames.classify("en.srt")).isEmpty();
        assertThat(SidecarNames.classify("x.en.forced.srt")).contains(forced("x"));
    }

    @Test
    void aFilmNameThatLooksLikeATokenKeepsItInTheStem() {
        assertThat(SidecarNames.classify("Hi.en.srt")).contains(full("Hi"));
        assertThat(SidecarNames.classify("X.en.en.srt")).contains(full("X.en"));
    }

    @Test
    void aFilmNameWithDotsKeepsAllOfItAsTheStem() {
        assertThat(SidecarNames.classify("Mr. Smith.en.srt")).contains(full("Mr. Smith"));
        assertThat(SidecarNames.classify("Mr. Smith.en.forced.srt")).contains(forced("Mr. Smith"));
        assertThat(SidecarNames.classify("S.W.A.T..eng.sdh.srt")).contains(full("S.W.A.T."));
    }

    @Test
    void theStemKeepsFoldersAndCase() {
        assertThat(SidecarNames.classify("Taken (2008)/Taken.en.forced.srt")).contains(forced("Taken (2008)/Taken"));
        assertThat(SidecarNames.classify("A/B.forced.en.srt")).contains(forced("A/B"));
        assertThat(SidecarNames.classify("A/No Time to Die.EN.FORCED.SRT")).contains(forced("A/No Time to Die"));
        assertThat(SidecarNames.classify("A.en/B.en.srt")).contains(full("A.en/B"));
    }

    @Test
    void aRepeatedForcedTokenIsReadFromTheShorterTailAndLeavesTheFirstInTheStem() {
        assertThat(SidecarNames.classify("X.forced.forced.en.srt")).contains(forced("X.forced"));
        assertThat(SidecarNames.classify("X.en.forced.forced.srt")).isEmpty();
    }

    @Test
    void aNameWithoutAnySuffixIsEmpty() {
        assertThat(SidecarNames.classify("srt")).isEqualTo(Optional.empty());
    }
}
