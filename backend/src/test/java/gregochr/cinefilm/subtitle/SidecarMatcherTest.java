package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SidecarMatcher.FileRef;
import gregochr.cinefilm.subtitle.SidecarMatcher.Match;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which film file a forced subtitle belongs to, from the two names alone (the real names from the library). */
class SidecarMatcherTest {

    private static final String TOWERS = "The Lord of the Rings - The Two Towers";
    private static final String EXTENDED = TOWERS + " {edition-Extended Edition}";
    private static final String THEATRICAL = TOWERS + " {edition-Theatrical Cut}";

    private static final List<FileRef> LIBRARY = List.of(
        new FileRef(1L, "Taken.m4v"),
        new FileRef(2L, "Heat.m4v"),
        new FileRef(3L, "No Time To Die.m4v"),
        new FileRef(4L, THEATRICAL + ".m4v"),
        new FileRef(6L, EXTENDED + " - pt2.m4v"),
        new FileRef(5L, EXTENDED + " - pt1.m4v"),
        new FileRef(7L, "Kill Bill/Kill Bill - pt1.m4v"),
        new FileRef(8L, "Kill Bill/Kill Bill - pt2.m4v"),
        new FileRef(9L, "Rocky IV.m4v"),
        new FileRef(10L, "Taken (2008)/Taken 2.m4v"));

    private static Optional<Match> match(String sidecar) {
        return new SidecarMatcher(LIBRARY).match(sidecar);
    }

    @Test
    void aSidecarNamedLikeTheFilmMatchesThatFile() {
        assertThat(match("Taken.en.forced.srt")).contains(new Match(1L, false));
        assertThat(match("Heat.en.forced.srt")).contains(new Match(2L, false));
    }

    @Test
    void theForcedDotEnSuffixMatchesToo() {
        assertThat(match("Taken.forced.en.srt")).contains(new Match(1L, false));
    }

    @Test
    void theTitleIsComparedIgnoringCase() {
        assertThat(match("No Time to Die.en.forced.srt")).contains(new Match(3L, false));
        assertThat(match("NO TIME TO DIE.EN.FORCED.SRT")).contains(new Match(3L, false));
    }

    @Test
    void aWholeEditionSidecarForASplitEditionIsStoredAgainstPartOneWhicheverIdComesFirst() {
        assertThat(match(EXTENDED + ".en.forced.srt")).contains(new Match(5L, true));
    }

    @Test
    void aSidecarWithoutTheEditionTagDoesNotMatchAFilmWhoseEditionsAreAllTagged() {
        assertThat(match(TOWERS + ".en.forced.srt")).isEmpty();
        assertThat(match("The Lord of the Rings - The Fellowship of the Ring.en.forced.srt")).isEmpty();
    }

    @Test
    void aTheatricalCutSidecarWithItsTagMatchesTheSingleFileEdition() {
        assertThat(match(THEATRICAL + ".en.forced.srt")).contains(new Match(4L, false));
    }

    @Test
    void aPartTaggedSidecarMatchesThatPartsFileOnly() {
        assertThat(match("Kill Bill/Kill Bill - pt1.en.forced.srt")).contains(new Match(7L, false));
        assertThat(match("Kill Bill/Kill Bill - pt2.en.forced.srt")).contains(new Match(8L, false));
        assertThat(match("Kill Bill/Kill Bill - pt3.en.forced.srt")).isEmpty();
    }

    @Test
    void aPartTaggedSidecarForAFileWithNoPartTagIsUnmatched() {
        assertThat(match("Taken - pt1.en.forced.srt")).isEmpty();
    }

    @Test
    void aSplitFilmWithoutAPartTagGetsAWholeEditionSidecarAgainstPartOne() {
        assertThat(match("Kill Bill/Kill Bill.en.forced.srt")).contains(new Match(7L, true));
    }

    @Test
    void aSidecarInAnotherFolderDoesNotMatch() {
        assertThat(match("Other/Taken.en.forced.srt")).isEmpty();
        assertThat(match("Taken (2008)/Taken.en.forced.srt")).isEmpty();
        assertThat(match("Taken (2008)/Taken 2.en.forced.srt")).contains(new Match(10L, false));
    }

    @Test
    void aSidecarForAFilmThatIsNotInTheLibraryIsUnmatched() {
        assertThat(match("Alien.en.forced.srt")).isEmpty();
    }

    @Test
    void twoUntaggedFilesWithTheSameNameAreAmbiguous() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(1L, "Heat.m4v"), new FileRef(2L, "Heat.mkv")));

        assertThat(matcher.match("Heat.en.forced.srt")).isEmpty();
    }

    @Test
    void anUntaggedFileBesideTaggedPartsIsAmbiguous() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(1L, "Heat.m4v"),
            new FileRef(2L, "Heat - pt1.m4v")));

        assertThat(matcher.match("Heat.en.forced.srt")).isEmpty();
    }

    @Test
    void twoFilesCarryingTheSamePartTagAreAmbiguous() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(1L, "Heat - pt1.m4v"),
            new FileRef(2L, "Heat - pt1.mkv")));

        assertThat(matcher.match("Heat - pt1.en.forced.srt")).isEmpty();
    }

    @Test
    void aSinglePartTaggedFileStillTakesAWholeEditionSidecar() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(1L, "Heat - pt1.m4v")));

        assertThat(matcher.match("Heat.en.forced.srt")).contains(new Match(1L, true));
    }

    @Test
    void equalPartTagsBreakTheTieByTheLowerFileId() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(9L, "Heat - pt1.mkv"),
            new FileRef(2L, "Heat - pt1.m4v")));

        assertThat(matcher.match("Heat.en.forced.srt")).contains(new Match(2L, true));
    }
}
