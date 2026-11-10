package gregochr.cinefilm.library;

import static gregochr.cinefilm.library.FileFacts.fact;
import static gregochr.cinefilm.library.FileFacts.withDuration;
import static gregochr.cinefilm.library.FileFacts.withExtraFiles;
import static gregochr.cinefilm.library.FileFacts.withPlayability;
import static gregochr.cinefilm.library.FileFacts.withPresence;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the scan's auto-resolve joins, and what it leaves alone. */
class EditionGrouperTest {

    private static final String TOWERS = "LOTR/The Two Towers (2002) {edition-Extended}";

    // ---- part joins

    @Test
    void partsOneAndTwoInSeparateEditionsAreJoinedInOrderEvenWhenListedBackwards() {
        List<FileFact> facts = List.of(fact(2, 20, 200, TOWERS + " - pt2.m4v.m4v"),
            fact(1, 10, 100, TOWERS + " - pt1.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).containsExactly(List.of(100L, 200L));
    }

    @Test
    void threePartsAreJoinedInPartOrder() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - cd1.m4v"), fact(2, 20, 200, "Epic - cd3.m4v"),
            fact(3, 30, 300, "Epic - cd2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).containsExactly(List.of(100L, 300L, 200L));
    }

    @Test
    void twoPartsOfOneEditionAreAlreadyJoinedAndNothingIsPlanned() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"), fact(1, 10, 101, "Epic - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void aDuplicatePartNumberIsNotJoined() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"), fact(2, 20, 200, "Epic - pt1.m4v"),
            fact(3, 30, 300, "Epic - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void aGapInThePartNumbersIsNotJoined() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"), fact(2, 20, 200, "Epic - pt3.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void aLonePartTwoIsNotJoinedToAnUntaggedFileOfTheSameKey() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic.m4v"), fact(2, 20, 200, "Epic - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void anUntaggedFileInTheGroupStopsThePartsBeingJoined() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"), fact(2, 20, 200, "Epic - pt2.m4v"),
            fact(3, 30, 300, "Epic.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void partsOfDifferentEditionTagsAreNotJoinedTogether() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic {edition-Extended} - pt1.m4v"),
            fact(2, 20, 200, "Epic {edition-Theatrical} - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void partsInDifferentFoldersAreNotJoinedTogether() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "A/Epic - pt1.m4v"), fact(2, 20, 200, "B/Epic - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void aPartWithoutADurationHoldsTheJoinBackUntilItHasOne() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"),
            withDuration(fact(2, 20, 200, "Epic - pt2.m4v"), null));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
        assertThat(EditionGrouper.partJoins(List.of(facts.get(0),
            withDuration(facts.get(1), 0L)))).isEmpty();
    }

    @Test
    void aMissingPartHoldsTheJoinBack() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"),
            withPresence(fact(2, 20, 200, "Epic - pt2.m4v"), Presence.MISSING));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void anAbsorbedEditionHoldingOtherFilesIsNotJoinedButTheSurvivorMay() {
        List<FileFact> absorbedHolds = List.of(fact(1, 10, 100, "Epic - pt1.m4v"),
            withExtraFiles(fact(2, 20, 200, "Epic - pt2.m4v")));
        List<FileFact> survivorHolds = List.of(withExtraFiles(fact(1, 10, 100, "Epic - pt1.m4v")),
            fact(2, 20, 200, "Epic - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(absorbedHolds)).isEmpty();
        assertThat(EditionGrouper.partJoins(survivorHolds)).containsExactly(List.of(100L, 200L));
    }

    @Test
    void aPartThatCannotBePlayedHoldsTheJoinBack() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"),
            withPlayability(fact(2, 20, 200, "Epic - pt2.m4v"), Playability.UNREADABLE));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void aPlexOrOwnerEditionIsNeverRegrouped() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"),
            fact(2, "Film 2", MatchStatus.MATCHED, 20, 200, "Epic - pt2.m4v", GroupingSource.PLEX));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void anEditionWithAFileOutsideTheGroupIsNotSweptIntoTheJoin() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Epic - pt1.m4v"), fact(1, 10, 101, "Other.m4v"),
            fact(2, 20, 200, "Epic - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).isEmpty();
    }

    @Test
    void twoIndependentGroupsAreJoinedSeparately() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "A - pt1.m4v"), fact(2, 20, 200, "A - pt2.m4v"),
            fact(3, 30, 300, "B - pt1.m4v"), fact(4, 40, 400, "B - pt2.m4v"));

        assertThat(EditionGrouper.partJoins(facts)).containsExactly(List.of(100L, 200L), List.of(300L, 400L));
    }

    // ---- film merges

    @Test
    void twoCutsOfOneFilmInOneFolderBecomeOneFilm() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Gladiator (2000) {edition-Extended Cut}.m4v"),
            fact(2, 20, 200, "Gladiator (2000) {edition-Theatrical Cut}.m4v"));

        assertThat(EditionGrouper.filmMerges(facts)).containsExactly(List.of(1L, 2L));
    }

    @Test
    void aTaggedAndAnUntaggedFileOfOneKeyAreOneFilm() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Gladiator (2000).m4v"),
            fact(2, 20, 200, "Gladiator (2000) {edition-Extended Cut}.m4v"));

        assertThat(EditionGrouper.filmMerges(facts)).containsExactly(List.of(1L, 2L));
    }

    @Test
    void aMatchedFilmSurvivesEvenWhenItIsTheNewer() {
        List<FileFact> facts = List.of(
            fact(1, "Gladiator", MatchStatus.PENDING, 10, 100, "Gladiator {edition-Extended Cut}.m4v",
                GroupingSource.AUTO),
            fact(2, "Gladiator", MatchStatus.NEEDS_MATCH, 20, 200, "Gladiator {edition-Final Cut}.m4v",
                GroupingSource.AUTO),
            fact(3, "Gladiator", MatchStatus.MATCHED, 30, 300, "Gladiator {edition-Theatrical Cut}.m4v",
                GroupingSource.AUTO));

        assertThat(EditionGrouper.filmMerges(facts)).containsExactly(List.of(3L, 1L, 2L));
    }

    @Test
    void withNoMatchedFilmTheOldestSurvives() {
        List<FileFact> facts = List.of(
            fact(5, "Gladiator", MatchStatus.PENDING, 10, 100, "Gladiator {edition-Extended Cut}.m4v",
                GroupingSource.AUTO),
            fact(4, "Gladiator", MatchStatus.PENDING, 20, 200, "Gladiator {edition-Final Cut}.m4v",
                GroupingSource.AUTO));

        assertThat(EditionGrouper.filmMerges(facts)).containsExactly(List.of(4L, 5L));
    }

    @Test
    void aFilmThatAlsoHoldsAnotherTitleIsNeverMergedWholesale() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Alien {edition-A}.m4v"),
            fact(2, 20, 200, "Alien {edition-B}.m4v"), fact(2, 21, 201, "Prometheus.m4v"));

        assertThat(EditionGrouper.filmMerges(facts)).isEmpty();
    }

    @Test
    void sameTagInTwoFilmsIsNotMergedBecauseThereAreNoDifferentEditions() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Gladiator {edition-Extended Cut}.m4v"),
            fact(2, 20, 200, "Gladiator {edition-Extended Cut}.m4v"));

        assertThat(EditionGrouper.filmMerges(facts)).isEmpty();
    }

    @Test
    void differentYearsOrFoldersAreDifferentFilms() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Dune (1984).m4v"),
            fact(2, 20, 200, "Dune (2021) {edition-Extended}.m4v"), fact(3, 30, 300, "A/Dune {edition-Final}.m4v"),
            fact(4, 40, 400, "B/Dune.m4v"));

        assertThat(EditionGrouper.filmMerges(facts)).isEmpty();
    }

    @Test
    void anEditionSomeoneSettledIsLeftAlone() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Gladiator {edition-Extended Cut}.m4v"),
            fact(2, "Film 2", MatchStatus.MATCHED, 20, 200, "Gladiator {edition-Final Cut}.m4v",
                GroupingSource.OWNER));

        assertThat(EditionGrouper.filmMerges(facts)).isEmpty();
    }

    @Test
    void oneFilmWithSeveralEditionsNeedsNoMerge() {
        List<FileFact> facts = List.of(fact(1, 10, 100, "Gladiator {edition-A}.m4v"),
            fact(1, 20, 200, "Gladiator {edition-B}.m4v"));

        assertThat(EditionGrouper.filmMerges(facts)).isEmpty();
    }
}
