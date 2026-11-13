package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SidecarMatcher.FileRef;
import gregochr.cinefilm.subtitle.SidecarMatcher.Match;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Which film file a forced subtitle belongs to: exact name only, everything else is attached in Admin. */
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
        new FileRef(10L, "Taken (2008)/Taken 2.mkv"),
        new FileRef(11L, "Heat (1995)/Heat.mp4"));

    private static Optional<Match> match(String sidecar) {
        return new SidecarMatcher(LIBRARY).match(sidecar);
    }

    @Test
    void aSidecarNamedExactlyLikeTheFilmMatchesThatFile() {
        assertThat(match("Taken.en.forced.srt")).contains(new Match(1L, SubtitleKind.FORCED));
        assertThat(match("Heat.forced.en.srt")).contains(new Match(2L, SubtitleKind.FORCED));
        assertThat(match("Rocky IV.en.forced.srt")).contains(new Match(9L, SubtitleKind.FORCED));
    }

    @Test
    void theExtensionOfTheFilmCanBeAnyVideoExtension() {
        assertThat(match("Taken (2008)/Taken 2.en.forced.srt")).contains(new Match(10L, SubtitleKind.FORCED));
        assertThat(match("Heat (1995)/Heat.en.forced.srt")).contains(new Match(11L, SubtitleKind.FORCED));
    }

    @Test
    void theNameIsComparedIgnoringCase() {
        assertThat(match("No Time to Die.en.forced.srt")).contains(new Match(3L, SubtitleKind.FORCED));
        assertThat(match("NO TIME TO DIE.EN.FORCED.SRT")).contains(new Match(3L, SubtitleKind.FORCED));
    }

    @Test
    void aPartTaggedSidecarMatchesThatPartsFileAndNeverAnotherPart() {
        assertThat(match("Kill Bill/Kill Bill - pt1.en.forced.srt")).contains(new Match(7L, SubtitleKind.FORCED));
        assertThat(match("Kill Bill/Kill Bill - pt2.en.forced.srt")).contains(new Match(8L, SubtitleKind.FORCED));
        assertThat(match("Kill Bill/Kill Bill - pt3.en.forced.srt")).isEmpty();
    }

    @Test
    void theEditionTagMustBeInTheSidecarsNameToo() {
        assertThat(match(THEATRICAL + ".en.forced.srt")).contains(new Match(4L, SubtitleKind.FORCED));
        assertThat(match(EXTENDED + " - pt1.en.forced.srt")).contains(new Match(5L, SubtitleKind.FORCED));
    }

    @Test
    void theSevenLordOfTheRingsFilesAreLeftUnmatchedForAdmin() {
        List<String> unmatched = new ArrayList<>();
        for (String film : List.of("The Fellowship of the Ring", TOWERS, "The Return of the King")) {
            unmatched.add(film + ".en.forced.srt");
        }
        unmatched.add("The Lord of the Rings - The Fellowship of the Ring {edition-Theatrical Cut}.en.forced.srt");
        unmatched.add(EXTENDED + ".en.forced.srt");
        unmatched.add("The Lord of the Rings - The Fellowship {edition-Extended Edition}.en.forced.srt");
        unmatched.add("The Lord of the Rings - The Return of the King {edition-Extended Edition}.en.forced.srt");

        assertThat(unmatched).hasSize(7).allSatisfy(name -> assertThat(match(name)).isEmpty());
    }

    @Test
    void aWholeEditionNameForASplitEditionIsNotMatchedAndNothingIsEverWhole() {
        assertThat(match("Kill Bill/Kill Bill.en.forced.srt")).isEmpty();
        assertThat(match(EXTENDED + ".en.forced.srt")).isEmpty();
    }

    @Test
    void aNearMissIsNotMatched() {
        assertThat(match("Taken 2.en.forced.srt")).isEmpty();
        assertThat(match("Taken - pt1.en.forced.srt")).isEmpty();
        assertThat(match("Tak.en.forced.srt")).isEmpty();
        assertThat(match("Heat (1995).en.forced.srt")).isEmpty();
        assertThat(match("Alien.en.forced.srt")).isEmpty();
    }

    @Test
    void aSidecarInAnotherFolderIsNotMatched() {
        assertThat(match("Other/Taken.en.forced.srt")).isEmpty();
        assertThat(match("Taken (2008)/Taken.en.forced.srt")).isEmpty();
    }

    @Test
    void severalCaseInsensitiveMatchesAreAmbiguousSoNoneIsChosen() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(1L, "Heat.m4v"), new FileRef(2L, "Heat.mkv")));
        SidecarMatcher cases = new SidecarMatcher(List.of(new FileRef(1L, "Heat.m4v"), new FileRef(2L, "HEAT.M4V")));

        assertThat(matcher.match("Heat.en.forced.srt")).isEmpty();
        assertThat(cases.match("heat.en.forced.srt")).isEmpty();
    }

    @Test
    void aFullEnglishSubtitleMatchesTheSameFileByNameAndSaysItIsFull() {
        assertThat(match("Taken.en.srt")).contains(new Match(1L, SubtitleKind.FULL));
        assertThat(match("Heat.eng.sdh.srt")).contains(new Match(2L, SubtitleKind.FULL));
        assertThat(match("Kill Bill/Kill Bill - pt1.sdh.en.srt")).contains(new Match(7L, SubtitleKind.FULL));
        assertThat(match("Heat (1995)/Heat.EN.SRT")).contains(new Match(11L, SubtitleKind.FULL));
    }

    @Test
    void aFullSubtitleIsMatchedExactlyAsStrictlyAsAForcedOne() {
        assertThat(match("Taken 2.en.srt")).isEmpty();
        assertThat(match("Other/Taken.en.srt")).isEmpty();
        assertThat(match("Kill Bill/Kill Bill.en.srt")).isEmpty();
        assertThat(match("Taken.fr.srt")).isEmpty();
        assertThat(match("Taken.srt")).isEmpty();
    }

    @Test
    void aFilmNameWithDotsMatchesItsFullSubtitle() {
        SidecarMatcher matcher = new SidecarMatcher(List.of(new FileRef(1L, "Mr. Smith.m4v")));

        assertThat(matcher.match("Mr. Smith.en.srt")).contains(new Match(1L, SubtitleKind.FULL));
    }
}
