package gregochr.cinefilm.library;

import static gregochr.cinefilm.library.FileFacts.fact;
import static gregochr.cinefilm.library.FileFacts.withTmdb;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.RepairRules.Decision;
import gregochr.cinefilm.library.RepairRules.Kind;
import java.util.List;
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
}
