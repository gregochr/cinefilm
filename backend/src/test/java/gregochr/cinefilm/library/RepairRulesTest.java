package gregochr.cinefilm.library;

import static gregochr.cinefilm.library.FileFacts.fact;
import static gregochr.cinefilm.library.FileFacts.withTmdb;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.RepairRules.Decision;
import gregochr.cinefilm.library.RepairRules.Kind;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** When a film whose files have different titles is split, flagged or left alone. */
class RepairRulesTest {

    @Test
    void filesWithOneTitleAreLeftAlone() {
        Decision decision = RepairRules.decide("Alien", null, List.of(fact(1, 10, 100, "Alien.m4v"),
            fact(1, 20, 200, "Alien Director's Cut.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.NONE);
    }

    @Test
    void rockyFourAndRockyFiveSplitOffTheOtherTitle() {
        Decision decision = RepairRules.decide("Rocky IV", 1001L, List.of(fact(1, 10, 100, "Rocky IV (1985).m4v"),
            fact(1, 20, 200, "Rocky V (1990).m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.SPLIT);
        assertThat(decision.others()).singleElement().satisfies(group -> {
            assertThat(group.comparisonTitle()).isEqualTo("rocky 5");
            assertThat(group.title()).isEqualTo("Rocky V");
            assertThat(group.year()).isEqualTo(1990);
            assertThat(group.files()).extracting(FileFact::fileId).containsExactly(200L);
        });
        assertThat(decision.summary()).isNull();
    }

    @Test
    void theFilmsOwnTitleMatchesWhateverNumeralStyleTheFilesUse() {
        Decision decision = RepairRules.decide("Rocky IV", null, List.of(fact(1, 10, 100, "Rocky.4.1985.m4v"),
            fact(1, 20, 200, "Rocky V.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.SPLIT);
    }

    @Test
    void aSequelNumberOrAVolumeOrAPartMarkerIsStrongEvidence() {
        assertThat(RepairRules.decide("Karate Kid", null, List.of(fact(1, 10, 100, "Karate Kid.m4v"),
            fact(1, 20, 200, "Karate Kid 2.m4v"))).kind()).isEqualTo(Kind.SPLIT);
        assertThat(RepairRules.decide("Kill Bill Vol. 1", null, List.of(fact(1, 10, 100, "Kill Bill Vol. 1.m4v"),
            fact(1, 20, 200, "Kill Bill Vol. 2.m4v"))).kind()).isEqualTo(Kind.SPLIT);
        assertThat(RepairRules.decide("Deathly Hallows - Part 1", null,
            List.of(fact(1, 10, 100, "Deathly Hallows - Part 1.m4v"),
                fact(1, 20, 200, "Deathly Hallows - Part 2.m4v"))).kind()).isEqualTo(Kind.SPLIT);
    }

    @Test
    void oneOtherTitleThatIsNotASequelFlagsTheWholeFilmEvenIfTheRestAre() {
        Decision decision = RepairRules.decide("Rocky", null, List.of(fact(1, 10, 100, "Rocky.m4v"),
            fact(1, 20, 200, "Rocky II.m4v"), fact(1, 30, 300, "Rocky III.m4v"),
            fact(1, 40, 400, "Rocky Balboa.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void severalSequelsEachBecomeTheirOwnGroup() {
        Decision decision = RepairRules.decide("Rocky", null, List.of(fact(1, 10, 100, "Rocky.m4v"),
            fact(1, 20, 200, "Rocky II.m4v"), fact(1, 30, 300, "Rocky III.m4v"),
            fact(1, 40, 400, "Rocky III - HD.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.SPLIT);
        assertThat(decision.others()).extracting(RepairRules.TitleGroup::comparisonTitle)
            .containsExactly("rocky 2", "rocky 3");
        assertThat(decision.others().get(1).files()).extracting(FileFact::fileId).containsExactlyInAnyOrder(300L, 400L);
    }

    @Test
    void anotherFilmsOwnTmdbTagIsStrongEvidence() {
        Decision decision = RepairRules.decide("Heat", 949L, List.of(fact(1, 10, 100, "Heat.m4v"),
            fact(1, 20, 200, "Heat Wave {tmdb-1234}.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.SPLIT);
    }

    @Test
    void aTmdbTagEqualToTheFilmsOwnIsNotEvidenceOfAnotherFilm() {
        Decision decision = RepairRules.decide("Heat", 949L, List.of(fact(1, 10, 100, "Heat.m4v"),
            fact(1, 20, 200, "Heat Wave {tmdb-949}.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void theTagIsComparedWithTheOwnGroupsTagWhenTheFilmHasNoTmdbId() {
        List<FileFact> same = List.of(fact(1, 10, 100, "Heat {tmdb-949}.m4v"),
            fact(1, 20, 200, "Heat Wave {tmdb-949}.m4v"));
        List<FileFact> different = List.of(fact(1, 10, 100, "Heat {tmdb-949}.m4v"),
            fact(1, 20, 200, "Heat Wave {tmdb-1234}.m4v"));

        assertThat(RepairRules.decide("Heat", null, same).kind()).isEqualTo(Kind.FLAG);
        assertThat(RepairRules.decide("Heat", null, different).kind()).isEqualTo(Kind.SPLIT);
    }

    @Test
    void aGroupWhoseFilesDisagreeAboutTheirTmdbTagIsNotEvidence() {
        Decision decision = RepairRules.decide("Heat", 949L, List.of(fact(1, 10, 100, "Heat.m4v"),
            fact(1, 20, 200, "Wave {tmdb-1234}.m4v"), fact(1, 30, 300, "Wave {tmdb-5678}.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void whenNoGroupIsTheFilmsOwnTheFilmIsFlaggedNotGuessed() {
        Decision decision = RepairRules.decide("Heat", null, List.of(fact(1, 10, 100, "Collateral.m4v"),
            fact(1, 20, 200, "Alien.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.FLAG);
        assertThat(decision.others()).isEmpty();
        assertThat(decision.summary()).isEqualTo(
            "Files look like different films: \"Alien\", \"Collateral\" in \"Heat\"");
    }

    @Test
    void aTitleThatIsNotASequelOfTheOwnIsFlagged() {
        Decision decision = RepairRules.decide("Alien", null, List.of(fact(1, 10, 100, "Alien.m4v"),
            fact(1, 20, 200, "Aliens.m4v")));

        assertThat(decision.kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void aLongFlagIsCutToTheColumnWidth() {
        String long1 = "A".repeat(300);
        String long2 = "B".repeat(300);

        Decision decision = RepairRules.decide("Heat", null, List.of(fact(1, 10, 100, long1 + ".m4v"),
            fact(1, 20, 200, long2 + ".m4v")));

        assertThat(decision.summary()).hasSize(500);
    }

    @Test
    void theFilmsTmdbIdFromTheRecordBeatsTheOwnGroupsTag() {
        List<FileFact> facts = List.of(withTmdb(fact(1, 10, 100, "Heat {tmdb-1}.m4v"), 949L),
            withTmdb(fact(1, 20, 200, "Heat Wave {tmdb-949}.m4v"), 949L));

        assertThat(RepairRules.decide("Heat", 949L, facts).kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void sequelDiffersNeedsTheSameWordsAndDifferentNumbers() {
        assertThat(RepairRules.sequelDiffers("rocky 4", "rocky 5")).isTrue();
        assertThat(RepairRules.sequelDiffers("rocky", "rocky 2")).isTrue();
        assertThat(RepairRules.sequelDiffers("kill bill vol 1", "kill bill vol 2")).isTrue();
        assertThat(RepairRules.sequelDiffers("rocky 4", "rocky 4")).isFalse();
        assertThat(RepairRules.sequelDiffers("rocky 4", "creed 4")).isFalse();
        assertThat(RepairRules.sequelDiffers("kill bill vol 1", "kill bill 1")).isFalse();
    }

    // ---- TMDB evidence

    private static final Set<String> ENCHANTED = Set.of("enchanted");

    private static List<FileFact> elf() {
        return List.of(fact(1, 10, 100, "Elf.m4v"), fact(1, 20, 200, "Enchanted.m4v"));
    }

    private static SearchHit hit(long id, String title, String originalTitle) {
        return new SearchHit(id, title, originalTitle, 2007, 10.0, null);
    }

    @Test
    void aGroupTmdbShowsIsAnotherFilmIsEvidenceToSplit() {
        Decision decision = RepairRules.decide("Elf", 10719L, elf(), ENCHANTED);

        assertThat(decision.kind()).isEqualTo(Kind.SPLIT);
        assertThat(decision.others()).extracting(RepairRules.TitleGroup::comparisonTitle).containsExactly("enchanted");
        assertThat(RepairRules.decide("Elf", 10719L, elf(), Set.of()).kind()).isEqualTo(Kind.FLAG);
        assertThat(RepairRules.decide("Elf", 10719L, elf()).kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void everyOtherGroupNeedsEvidenceNotJustOne() {
        List<FileFact> files = List.of(fact(1, 10, 100, "Elf.m4v"), fact(1, 20, 200, "Enchanted.m4v"),
            fact(1, 30, 300, "Ghost.m4v"));

        assertThat(RepairRules.decide("Elf", 10719L, files, ENCHANTED).kind()).isEqualTo(Kind.FLAG);
        assertThat(RepairRules.decide("Elf", 10719L, files, Set.of("enchanted", "ghost")).kind())
            .isEqualTo(Kind.SPLIT);
    }

    @Test
    void aSequelGroupAndATmdbGroupTogetherSplit() {
        List<FileFact> files = List.of(fact(1, 10, 100, "Rocky IV.m4v"), fact(1, 20, 200, "Rocky V.m4v"),
            fact(1, 30, 300, "Enchanted.m4v"));

        assertThat(RepairRules.decide("Rocky IV", 1001L, files, ENCHANTED).kind()).isEqualTo(Kind.SPLIT);
        assertThat(RepairRules.decide("Rocky IV", 1001L, files).kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void tmdbEvidenceNeedsAReferenceAndAnOwnGroup() {
        assertThat(RepairRules.decide("Elf", null, elf(), ENCHANTED).kind()).isEqualTo(Kind.FLAG);
        assertThat(RepairRules.decide("Elf", 10719L, List.of(fact(1, 10, 100, "Buddy.m4v"),
            fact(1, 20, 200, "Enchanted.m4v")), ENCHANTED).kind()).isEqualTo(Kind.FLAG);
    }

    @Test
    void lookupsNameTheUntaggedGroupsOfAFlaggedFilm() {
        List<RepairRules.TitleGroup> lookups = RepairRules.lookups("Elf", 10719L, elf());

        assertThat(lookups).singleElement().satisfies(group -> {
            assertThat(group.comparisonTitle()).isEqualTo("enchanted");
            assertThat(group.title()).isEqualTo("Enchanted");
            assertThat(group.files()).extracting(FileFact::fileId).containsExactly(200L);
        });
    }

    @Test
    void lookupsAreEmptyWhenThereIsNothingToJudgeOrNothingToAsk() {
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Elf.m4v"),
            fact(1, 20, 200, "Elf (HD).m4v")))).isEmpty();
        assertThat(RepairRules.lookups("Rocky IV", 1001L, List.of(fact(1, 10, 100, "Rocky IV.m4v"),
            fact(1, 20, 200, "Rocky V.m4v")))).isEmpty();
        assertThat(RepairRules.lookups("Elf", null, elf())).isEmpty();
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Buddy.m4v"),
            fact(1, 20, 200, "Enchanted.m4v")))).isEmpty();
    }

    @Test
    void lookupsTakeTheReferenceFromTheOwnGroupsTagWhenTheFilmHasNoId() {
        List<FileFact> files = List.of(fact(1, 10, 100, "Elf {tmdb-10719}.m4v"), fact(1, 20, 200, "Enchanted.m4v"));

        assertThat(RepairRules.lookups("Elf", null, files)).extracting(RepairRules.TitleGroup::comparisonTitle)
            .containsExactly("enchanted");
        assertThat(RepairRules.reference("Elf", null, files)).isEqualTo(10719L);
        assertThat(RepairRules.reference("Elf", 5L, files)).isEqualTo(5L);
        assertThat(RepairRules.reference("Elf", null, elf())).isNull();
    }

    @Test
    void aTaggedGroupIsNeverLookedUp() {
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Elf.m4v"),
            fact(1, 20, 200, "Enchanted {tmdb-10719}.m4v")))).isEmpty();
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Elf.m4v"),
            fact(1, 20, 200, "Enchanted {imdb-tt0411008}.m4v")))).isEmpty();
    }

    @Test
    void aGroupWithAnEditionTagOrAPlainEditionWordIsNeverLookedUp() {
        assertThat(RepairRules.lookups("Blade Runner", 78L, List.of(fact(1, 10, 100, "Blade Runner.m4v"),
            fact(1, 20, 200, "Blader Runner (1982) {edition-The Final Cut}.m4v")))).isEmpty();
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Elf.m4v"),
            fact(1, 20, 200, "Enchanted Director's Cut.m4v")))).isEmpty();
    }

    @Test
    void aFilmWithAnIneligibleGroupBesideAnEligibleOneIsNotLookedUpAtAll() {
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Elf.m4v"),
            fact(1, 20, 200, "Buddy {edition-X}.m4v"), fact(1, 30, 300, "Enchanted.m4v")))).isEmpty();
        assertThat(RepairRules.lookups("Elf", 10719L, List.of(fact(1, 10, 100, "Elf.m4v"),
            fact(1, 20, 200, "Enchanted.m4v"), fact(1, 30, 300, "The Buddy {edition-X}.m4v")))).isEmpty();
    }

    @Test
    void aGroupThatAlreadyHasTodaysEvidenceIsNotLookedUpButTheOthersAre() {
        List<FileFact> files = List.of(fact(1, 10, 100, "Rocky IV.m4v"), fact(1, 20, 200, "Rocky V.m4v"),
            fact(1, 30, 300, "Enchanted.m4v"), fact(1, 40, 400, "Heat Wave {tmdb-1234}.m4v"));

        assertThat(RepairRules.lookups("Rocky IV", 1001L, files)).extracting(RepairRules.TitleGroup::comparisonTitle)
            .containsExactly("enchanted");
    }

    @Test
    void elfsRealHitsMakeEnchantedADifferentFilm() {
        List<SearchHit> hits = List.of(hit(4523, "Enchanted", "Enchanted"), hit(404277, "Enchanted", "Enchanted"),
            hit(14442, "Ella Enchanted", "Ella Enchanted"));

        assertThat(RepairRules.isDifferentFilm("enchanted", hits, 10719L, Set.of("Elf"))).isTrue();
    }

    @Test
    void anExactHitThatIsTheReferenceFilmMeansNotDifferent() {
        List<SearchHit> hits = List.of(hit(4523, "Enchanted", "Enchanted"), hit(10719, "Enchanted", "Elf"));

        assertThat(RepairRules.isDifferentFilm("enchanted", hits, 10719L, Set.of())).isFalse();
    }

    @Test
    void noExactHitIsNoEvidence() {
        assertThat(RepairRules.isDifferentFilm("blader runner", List.of(hit(78, "Blade Runner", "Blade Runner")), 78L,
            Set.of())).isFalse();
        assertThat(RepairRules.isDifferentFilm("the amazing spiderman",
            List.of(hit(1930, "The Amazing Spider-Man", "The Amazing Spider-Man")), 558L, Set.of())).isFalse();
        assertThat(RepairRules.isDifferentFilm("enchanted", List.of(), 10719L, Set.of())).isFalse();
    }

    @Test
    void anAlternativeTitleOfTheReferenceIsNotADifferentFilm() {
        List<SearchHit> hits = List.of(hit(10, "The Professional", "The Professional"));

        assertThat(RepairRules.isDifferentFilm("the professional", hits, 101L,
            Set.of("Léon: The Professional", "The Professional"))).isFalse();
        assertThat(RepairRules.isDifferentFilm("the professional", hits, 101L, Set.of("Léon"))).isTrue();
    }

    @Test
    void anExactMatchOnTheOriginalTitleAloneCounts() {
        assertThat(RepairRules.isDifferentFilm("enchanted", List.of(hit(5, "Zauberhaft", "Enchanted")), 10719L,
            Set.of())).isTrue();
    }

    @Test
    void hitsWithNullTitlesAreIgnored() {
        List<SearchHit> hits = List.of(hit(1, null, null), hit(2, null, "Enchanted"), hit(3, "Enchanted", null));

        assertThat(RepairRules.isDifferentFilm("enchanted", hits, 10719L, Set.of())).isTrue();
        assertThat(RepairRules.isDifferentFilm("enchanted", List.of(hit(1, null, null)), 10719L, Set.of())).isFalse();
    }

    @Test
    void anAmpersandAndAnAndAreNotTheSameTitle() {
        assertThat(RepairRules.isDifferentFilm("fast furious", List.of(hit(1, "Fast and Furious", null)), 2L,
            Set.of())).isFalse();
    }
}
