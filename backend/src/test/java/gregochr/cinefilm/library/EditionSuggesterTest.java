package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.EditionSuggester.Group;
import gregochr.cinefilm.library.EditionSuggester.Slot;
import gregochr.cinefilm.library.EditionSuggester.SuggestFile;
import gregochr.cinefilm.library.EditionSuggester.Suggestion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link EditionSuggester}: the rules in the plan, with the reasons the panel shows. */
class EditionSuggesterTest {

    private static SuggestFile file(long id, String path, Long durationMs, long editionId, String editionName,
                                    int part) {
        return new SuggestFile(id, path, durationMs, editionId, editionName, part);
    }

    private static SuggestFile alone(long id, String path, long minutes) {
        return file(id, path, minutes * MIN, id, "Original", 1);
    }

    private static List<String> names(Suggestion s) {
        return s.editions().stream().map(Group::name).toList();
    }

    @Test
    void noFilesSuggestNothing() {
        Suggestion s = EditionSuggester.suggest("Heat", false, null, List.of());

        assertThat(s).isEqualTo(new Suggestion("", List.of(), List.of(), null));
    }

    @Test
    void tagsAndPartSuffixesComeFirst() {
        String towers = "The Lord of the Rings - The Two Towers";
        List<SuggestFile> files = List.of(
            alone(1, towers + " {edition-Extended Edition} - pt1.m4v", 114),
            alone(2, towers + " {edition-Extended Edition} - pt2.m4v", 121),
            alone(3, towers + " {edition-Theatrical Cut}.m4v", 179));

        Suggestion s = EditionSuggester.suggest("The Lord of the Rings: The Two Towers", false, null, files);

        assertThat(s.reason()).isEqualTo("From the edition tags and part numbers");
        assertThat(s.editions()).containsExactly(
            new Group(1L, "Extended Edition", List.of(new Slot(1L, 1), new Slot(2L, 2))),
            new Group(3L, "Theatrical Cut", List.of(new Slot(3L, null))));
        assertThat(s.notThisFilm()).isEmpty();
        assertThat(s.defaultName()).isEqualTo("Theatrical Cut");
    }

    @Test
    void untaggedFilesMoreThanFiveMinutesApartAreTheatricalAndExtended() {
        List<SuggestFile> files = List.of(alone(1, "Gladiator (2000).m4v", 171), alone(2, "Gladiator.m4v", 155));

        Suggestion s = EditionSuggester.suggest("Gladiator", false, null, files);

        assertThat(s.reason()).isEqualTo("Runtimes 2h 35m and 2h 51m — likely Theatrical and Extended");
        assertThat(s.editions()).containsExactly(
            new Group(2L, "Theatrical", List.of(new Slot(2L, null))),
            new Group(1L, "Extended", List.of(new Slot(1L, null))));
        assertThat(s.defaultName()).isEqualTo("Theatrical");
    }

    @Test
    void untaggedFilesWithinAMinuteAreCopiesOfOneCut() {
        List<SuggestFile> files = List.of(alone(1, "Heat.m4v", 170), alone(2, "Heat (1995).m4v", 170));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(s.reason()).isEqualTo("Runtimes match (2h 50m) — likely copies of one cut. Kept apart so you can "
            + "compare; rename one, or mark it Not this film.");
        assertThat(s.editions()).containsExactly(
            new Group(1L, "Original", List.of(new Slot(1L, null))),
            new Group(2L, "Original 2", List.of(new Slot(2L, null))));
        assertThat(s.defaultName()).isEqualTo("Original");
    }

    @Test
    void theFiveMinuteBoundary() {
        SuggestFile a = file(1, "Heat.m4v", 100 * MIN, 1, "Original", 1);
        SuggestFile exactly = file(2, "Heat (1995).m4v", 105 * MIN, 2, "Original", 1);
        SuggestFile over = file(2, "Heat (1995).m4v", 105 * MIN + 1, 2, "Original", 1);

        assertThat(EditionSuggester.suggest("Heat", false, null, List.of(a, exactly)).reason())
            .isEqualTo("Runtimes are close (1h 40m and 1h 45m) — likely copies of one cut. Kept apart so you can "
                + "compare; rename one, or mark it Not this film.");
        assertThat(names(EditionSuggester.suggest("Heat", false, null, List.of(a, over))))
            .containsExactly("Theatrical", "Extended");
    }

    @Test
    void theOneMinuteBoundary() {
        SuggestFile a = file(1, "Heat.m4v", 100 * MIN, 1, "Original", 1);
        SuggestFile within = file(2, "Heat (1995).m4v", 101 * MIN, 2, "Original", 1);
        SuggestFile over = file(2, "Heat (1995).m4v", 101 * MIN + 1, 2, "Original", 1);

        assertThat(EditionSuggester.suggest("Heat", false, null, List.of(a, within)).reason())
            .startsWith("Runtimes match (1h 40m)");
        assertThat(EditionSuggester.suggest("Heat", false, null, List.of(a, over)).reason())
            .startsWith("Runtimes are close (1h 40m and 1h 41m)");
    }

    @Test
    void severalFilesInOneClusterGetTheirOwnNumberedNames() {
        List<SuggestFile> files = List.of(alone(1, "Heat.m4v", 100), alone(2, "Heat (1995).m4v", 100),
            alone(3, "Heat - HD.m4v", 160), alone(4, "Heat [1995].m4v", 160));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(names(s)).containsExactly("Theatrical", "Theatrical 2", "Extended", "Extended 2");
    }

    @Test
    void threeCutsAreKeptApartWithoutNames() {
        List<SuggestFile> files = List.of(alone(1, "Heat.m4v", 100), alone(2, "Heat (1995).m4v", 130),
            alone(3, "Heat - HD.m4v", 160));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(names(s)).containsExactly("Original", "Original 2", "Original 3");
        assertThat(s.reason()).isEqualTo("Runtimes 1h 40m, 2h 10m, 2h 40m — kept apart so you can compare; rename "
            + "them, or mark any that aren't this film.");
    }

    @Test
    void runtimesUnderAnHourReadInMinutes() {
        List<SuggestFile> files = List.of(alone(1, "Heat.m4v", 45), alone(2, "Heat (1995).m4v", 45));

        assertThat(EditionSuggester.suggest("Heat", false, null, files).reason()).startsWith("Runtimes match (45m)");
    }

    @Test
    void aFileWithoutARuntimeIsKeptApartWhateverTheOthersSay() {
        SuggestFile unknown = file(3, "Heat - HD.m4v", null, 3, "Original", 1);
        SuggestFile zero = file(4, "Heat [1995].m4v", 0L, 4, "Original", 1);
        List<SuggestFile> files = List.of(alone(1, "Heat.m4v", 100), alone(2, "Heat (1995).m4v", 160), unknown, zero);

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(names(s)).containsExactly("Theatrical", "Extended", "Original", "Original 2");
    }

    @Test
    void withOnlyOneKnownRuntimeNothingCanBeSaidAboutTheCuts() {
        List<SuggestFile> files = List.of(alone(1, "Heat.m4v", 100),
            file(2, "Heat (1995).m4v", null, 2, "Original", 1));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(names(s)).containsExactly("Original", "Original 2");
        assertThat(s.reason()).isEqualTo("Runtimes aren't known for every file — kept apart so you can compare; "
            + "rename one, or mark it Not this film.");
    }

    @Test
    void aSingleFileHasNothingToExplain() {
        Suggestion s = EditionSuggester.suggest("Heat", false, null, List.of(alone(1, "Heat.m4v", 170)));

        assertThat(s.reason()).isEmpty();
        assertThat(s.editions()).containsExactly(new Group(1L, "Original", List.of(new Slot(1L, null))));
        assertThat(s.defaultName()).isEqualTo("Original");
    }

    @Test
    void aFileOfAnotherFilmIsSuggestedAsNotThisFilm() {
        List<SuggestFile> files = List.of(alone(1, "Rocky IV.m4v", 91), alone(2, "Rocky V.m4v", 104));

        Suggestion s = EditionSuggester.suggest("Rocky IV", false, null, files);

        assertThat(s.editions()).containsExactly(new Group(1L, "Original", List.of(new Slot(1L, null))));
        assertThat(s.notThisFilm()).containsExactly(2L);
        assertThat(s.reason()).isEqualTo("Runtimes 1h 31m and 1h 44m — these may be different films");
    }

    @Test
    void theDifferentFilmsReasonOrdersTheRuntimesAndToleratesUnknownOnes() {
        List<SuggestFile> longerFirst = List.of(alone(1, "Rocky IV.m4v", 104), alone(2, "Rocky V.m4v", 91));
        List<SuggestFile> unknown = List.of(alone(1, "Rocky IV.m4v", 104),
            file(2, "Rocky V.m4v", null, 2, "Original", 1));
        List<SuggestFile> unknownKept = List.of(file(1, "Rocky IV.m4v", 0L, 1, "Original", 1),
            alone(2, "Rocky V.m4v", 91));

        assertThat(EditionSuggester.suggest("Rocky IV", false, null, longerFirst).reason())
            .isEqualTo("Runtimes 1h 31m and 1h 44m — these may be different films");
        assertThat(EditionSuggester.suggest("Rocky IV", false, null, unknown).reason())
            .isEqualTo("These files may be different films");
        assertThat(EditionSuggester.suggest("Rocky IV", false, null, unknownKept).reason())
            .isEqualTo("These files may be different films");
    }

    @Test
    void matchingRuntimesUnderDifferentNamesSayTheyMatchAndStillExcludeTheFile() {
        List<SuggestFile> files = List.of(
            file(1, "Blade Runner.m4v", 7_056_883L, 1, "Original", 1),
            file(2, "Blader Runner (1982) {edition-The Final Cut}.m4v", 7_056_917L, 2, "Original", 1));

        Suggestion s = EditionSuggester.suggest("Blade Runner", false, null, files);

        assertThat(s.notThisFilm()).containsExactly(2L);
        assertThat(s.reason()).isEqualTo("The names differ but the runtimes match (1h 57m)"
            + " — if it's the same film, move it back to keep it here");
    }

    @Test
    void farApartRuntimesUnderDifferentNamesStillSayDifferentFilms() {
        List<SuggestFile> files = List.of(
            file(1, "Elf.m4v", 5_803_243L, 1, "Original", 1),
            file(2, "Enchanted.m4v", 6_182_280L, 2, "Original", 1));

        Suggestion s = EditionSuggester.suggest("Elf", false, null, files);

        assertThat(s.notThisFilm()).containsExactly(2L);
        assertThat(s.reason()).isEqualTo("Runtimes 1h 36m and 1h 43m — these may be different films");
    }

    @Test
    void theReasonChangesWordingAtTheInclusiveRuntimeBoundaries() {
        long base = 120 * MIN;
        String moveBack = " — if it's the same film, move it back to keep it here";

        assertThat(reasonForExcludedAt(base, base + EditionSuggester.SAME_CUT_MS - 1))
            .isEqualTo("The names differ but the runtimes match (2h 0m)" + moveBack);
        assertThat(reasonForExcludedAt(base, base + EditionSuggester.SAME_CUT_MS))
            .isEqualTo("The names differ but the runtimes match (2h 0m)" + moveBack);
        assertThat(reasonForExcludedAt(base, base + EditionSuggester.SAME_CUT_MS + 1))
            .isEqualTo("The names differ but the runtimes are close (2h 0m and 2h 1m)" + moveBack);
        assertThat(reasonForExcludedAt(base + EditionSuggester.SAME_CUT_MS + 1, base))
            .isEqualTo("The names differ but the runtimes are close (2h 0m and 2h 1m)" + moveBack);
        assertThat(reasonForExcludedAt(base, base + EditionSuggester.DIFFERENT_CUTS_MS - 1))
            .isEqualTo("The names differ but the runtimes are close (2h 0m and 2h 4m)" + moveBack);
        assertThat(reasonForExcludedAt(base, base + EditionSuggester.DIFFERENT_CUTS_MS))
            .isEqualTo("The names differ but the runtimes are close (2h 0m and 2h 5m)" + moveBack);
        assertThat(reasonForExcludedAt(base, base + EditionSuggester.DIFFERENT_CUTS_MS + 1))
            .isEqualTo("Runtimes 2h 0m and 2h 5m — these may be different films");
    }

    private static String reasonForExcludedAt(long keptMs, long excludedMs) {
        List<SuggestFile> files = List.of(file(1, "Film.m4v", keptMs, 1, "Original", 1),
            file(2, "Other.m4v", excludedMs, 2, "Original", 1));
        return EditionSuggester.suggest("Film", false, null, files).reason();
    }

    @Test
    void nothingIsExcludedWhenNoFileCarriesTheFilmsOwnTitle() {
        List<SuggestFile> files = List.of(alone(1, "Seven.m4v", 127), alone(2, "Se7en.m4v", 127));

        Suggestion s = EditionSuggester.suggest("Another Title", false, null, files);

        assertThat(s.notThisFilm()).isEmpty();
        assertThat(names(s)).containsExactly("Original", "Original 2");
    }

    @Test
    void plainEditionWordsNameTheEdition() {
        List<SuggestFile> files = List.of(alone(1, "Alien.m4v", 117), alone(2, "Alien Director's Cut.m4v", 116));

        Suggestion s = EditionSuggester.suggest("Alien", false, null, files);

        assertThat(s.reason()).isEqualTo("From the edition tags and part numbers");
        assertThat(names(s)).containsExactly("Original", "Director's Cut");
    }

    @Test
    void aTagCalledOriginalAndAnUntaggedFileGetDifferentNames() {
        List<SuggestFile> files = List.of(alone(1, "Heat {edition-Original}.m4v", 170), alone(2, "Heat.m4v", 170));

        assertThat(names(EditionSuggester.suggest("Heat", false, null, files)))
            .containsExactly("Original", "Original 2");
    }

    @Test
    void partOnlyFilesShareOneEditionAndAFileWithoutAPartTagStaysUnnumbered() {
        List<SuggestFile> files = List.of(alone(1, "Heat - pt2.m4v", 80), alone(2, "Heat - pt1.m4v", 90),
            alone(3, "Heat - HD.m4v", 170));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(s.editions()).containsExactly(
            new Group(1L, "Original", List.of(new Slot(2L, 1), new Slot(1L, 2))),
            new Group(3L, "Original 2", List.of(new Slot(3L, null))));
    }

    @Test
    void aLonePartFileIsNotNumberedAndFilesWithoutNumbersKeepTheirOrderForTheOwnerToFix() {
        List<SuggestFile> lone = List.of(alone(1, "Heat - pt2.m4v", 90));
        List<SuggestFile> mixed = List.of(alone(1, "Heat {edition-Cut} - pt1.m4v", 90),
            alone(2, "Heat {edition-Cut}.m4v", 80));

        assertThat(EditionSuggester.suggest("Heat", false, null, lone).editions())
            .containsExactly(new Group(1L, "Original", List.of(new Slot(1L, null))));
        assertThat(EditionSuggester.suggest("Heat", false, null, mixed).editions())
            .containsExactly(new Group(1L, "Cut", List.of(new Slot(1L, 1), new Slot(2L, null))));
    }

    @Test
    void anEditionContinuesTheExistingEditionHoldingMostOfItsFilesAndOnlyOneGroupContinuesIt() {
        List<SuggestFile> files = List.of(
            file(1, "Heat {edition-A} - pt1.m4v", 50 * MIN, 7, "Old", 1),
            file(2, "Heat {edition-A} - pt2.m4v", 50 * MIN, 7, "Old", 2),
            file(3, "Heat {edition-B}.m4v", 100 * MIN, 7, "Old", 3),
            file(4, "Heat {edition-C}.m4v", 100 * MIN, 8, "Other", 1),
            file(5, "Heat {edition-D}.m4v", 100 * MIN, 9, "Third", 1));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(s.editions()).extracting(Group::editionId).containsExactly(7L, null, 8L, 9L);
    }

    @Test
    void whenTwoGroupsShareAnEditionEqually_theLowerOneGoesToTheFirstGroup() {
        List<SuggestFile> files = List.of(
            file(1, "Heat {edition-A}.m4v", 100 * MIN, 7, "Old", 1),
            file(2, "Heat {edition-B}.m4v", 100 * MIN, 7, "Old", 2));

        Suggestion s = EditionSuggester.suggest("Heat", false, null, files);

        assertThat(s.editions()).extracting(Group::editionId).containsExactly(7L, null);
    }

    @Test
    void theDefaultKeepsTheCurrentDefaultEditionWhileItIsStillThere() {
        List<SuggestFile> files = List.of(
            file(1, "Heat {edition-Cut A}.m4v", 100 * MIN, 7, "Old", 1),
            file(2, "Heat {edition-Cut B}.m4v", 100 * MIN, 8, "Old", 1));

        assertThat(EditionSuggester.suggest("Heat", false, 8L, files).defaultName()).isEqualTo("Cut B");
        assertThat(EditionSuggester.suggest("Heat", false, 99L, files).defaultName()).isEqualTo("Cut A");
        assertThat(EditionSuggester.suggest("Heat", false, null, files).defaultName()).isEqualTo("Cut A");
    }

    @Test
    void withoutATheatricalOrOriginalTheLongestEditionIsTheDefault() {
        List<SuggestFile> files = List.of(
            file(1, "Heat {edition-Cut A}.m4v", 100 * MIN, 7, "Old", 1),
            file(2, "Heat {edition-Cut B}.m4v", 130 * MIN, 8, "Old", 1),
            file(3, "Heat {edition-Cut C}.m4v", null, 9, "Old", 1));

        assertThat(EditionSuggester.suggest("Heat", false, null, files).defaultName()).isEqualTo("Cut B");
    }

    @Test
    void aFilmTheOwnerSettledIsShownAsArranged() {
        List<SuggestFile> files = List.of(
            file(1, "whatever-b.m4v", 50 * MIN, 8, "Extended", 2),
            file(2, "whatever-a.m4v", 50 * MIN, 8, "Extended", 1),
            file(3, "whatever-c.m4v", 100 * MIN, 7, "Theatrical", 1));

        Suggestion s = EditionSuggester.suggest("Heat", true, 8L, files);

        assertThat(s.reason()).isEqualTo("As you last arranged it");
        assertThat(s.editions()).containsExactly(
            new Group(7L, "Theatrical", List.of(new Slot(3L, null))),
            new Group(8L, "Extended", List.of(new Slot(2L, 1), new Slot(1L, 2))));
        assertThat(s.notThisFilm()).isEmpty();
        assertThat(s.defaultName()).isEqualTo("Extended");
    }
}
