package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.CreditsApply.UnitInputs;
import gregochr.cinefilm.credits.learn.CreditsSplit.SplitFilm;
import gregochr.cinefilm.plex.PlexRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CreditsSplitTest {

    private static final Map<Long, ManifestEntry> NONE = Map.of();
    private static final Path FIXTURES = LearnFixtures.dir();

    @TempDir
    Path temp;

    private static SplitFilm film(long id, String unit) {
        return new SplitFilm(id, unit);
    }

    private static ManifestEntry entry(long id, String unit, Side side, Integer fold) {
        return new ManifestEntry(id, unit, side, fold);
    }

    private static Map<Long, ManifestEntry> previous(ManifestEntry... entries) {
        Map<Long, ManifestEntry> map = new java.util.TreeMap<>();
        for (ManifestEntry e : entries) {
            map.put(e.filmId(), e);
        }
        return map;
    }

    @Test
    void theHashOfTmdb333371IsFortyOnTheDevelopmentSideInFoldZero() {
        assertThat(Long.remainderUnsigned(CreditsSplit.hash("tmdb:333371"), 100)).isEqualTo(40);
        assertThat(CreditsSplit.hashSide("tmdb:333371")).isEqualTo(Side.DEV);
        assertThat(CreditsSplit.hashFold("tmdb:333371")).isZero();
    }

    @Test
    void theHashOfCtmdb263IsSixtyOnTheDevelopmentSideInFoldZero() {
        assertThat(Long.remainderUnsigned(CreditsSplit.hash("ctmdb:263"), 100)).isEqualTo(60);
        assertThat(CreditsSplit.hashSide("ctmdb:263")).isEqualTo(Side.DEV);
        assertThat(CreditsSplit.hashFold("ctmdb:263")).isZero();
    }

    @Test
    void sixtyNineIsDevelopmentAndSeventyIsTestAndFoldsFollowModFive() {
        assertThat(Long.remainderUnsigned(CreditsSplit.hash("tmdb:14"), 100)).isEqualTo(69);
        assertThat(CreditsSplit.hashSide("tmdb:14")).isEqualTo(Side.DEV);
        assertThat(CreditsSplit.hashFold("tmdb:14")).isEqualTo(4);
        assertThat(Long.remainderUnsigned(CreditsSplit.hash("tmdb:75"), 100)).isEqualTo(70);
        assertThat(CreditsSplit.hashSide("tmdb:75")).isEqualTo(Side.TEST);
        assertThat(CreditsSplit.hashFold("tmdb:1004")).isEqualTo(4);
        assertThat(CreditsSplit.hashFold("tmdb:1008")).isEqualTo(1);
        assertThat(CreditsSplit.hashFold("tmdb:1012")).isEqualTo(3);
        assertThat(CreditsSplit.hashFold("tmdb:1014")).isEqualTo(2);
    }

    @Test
    void theHashReadsTheFirstEightBytesBigEndianAsAnUnsignedNumber() {
        // SHA-256("tmdb:1003") starts c1 12 3a 51 1c 53 3f ab: negative as a signed long, so only the unsigned reading
        // gives 75 (a signed remainder would give a negative number)
        assertThat(CreditsSplit.hash("tmdb:1003")).isEqualTo(0xc1123a511c533fabL);
        assertThat(Long.remainderUnsigned(CreditsSplit.hash("tmdb:1003"), 100)).isEqualTo(75);
        assertThat(Long.remainderUnsigned(CreditsSplit.hash("tmdb:1003"), 5)).isZero();
        assertThat(CreditsSplit.hash("tmdb:333371")).isEqualTo(0x1f1961a19ab36bc4L);
    }

    @Test
    void aJvmWithoutSha256IsAnIllegalState() {
        assertThatThrownBy(() -> CreditsSplit.hash("tmdb:1", () -> {
            throw new java.security.NoSuchAlgorithmException("none");
        })).isInstanceOf(IllegalStateException.class).hasMessage("SHA-256 is part of every JVM");
    }

    @Test
    void theUnitKeyIsTheCollectionsTmdbIdFirst() {
        assertThat(CreditsSplit.unitKey(263L, 4L, 333371L, "X", 2000)).isEqualTo("ctmdb:263");
    }

    @Test
    void theUnitKeyIsTheCollectionIdWhenThereIsNoCollectionTmdbId() {
        assertThat(CreditsSplit.unitKey(null, 4L, 333371L, "X", 2000)).isEqualTo("cid:4");
    }

    @Test
    void theUnitKeyIsTheFilmsTmdbIdWithNoCollection() {
        assertThat(CreditsSplit.unitKey(null, null, 333371L, "X", 2000)).isEqualTo("tmdb:333371");
    }

    @Test
    void withNoIdsTheUnitKeyIsTheNormalisedTitleAndYear() {
        assertThat(CreditsSplit.unitKey(null, null, null, "The Matrix: Reloaded!", 2003))
            .isEqualTo("title:thematrixreloaded|2003");
    }

    @Test
    void theTitleIsNfcLowercasedAndKeepsOnlyLettersAndNumbers() {
        assertThat(CreditsSplit.unitKey(null, null, null, "Amélie", 2001)).isEqualTo("title:amélie|2001");
        assertThat(CreditsSplit.unitKey(null, null, null, "Amélie", 2001)).isEqualTo("title:amélie|2001");
        assertThat(CreditsSplit.unitKey(null, null, null, "  Se7en - 1995 Ⅰ", 1995))
            .isEqualTo("title:se7en1995ⅰ|1995");
        assertThat(CreditsSplit.unitKey(null, null, null, "İSTANBUL", 2000)).isEqualTo("title:istanbul|2000");
    }

    @Test
    void aMissingYearLeavesTheYearEmpty() {
        assertThat(CreditsSplit.unitKey(null, null, null, "Zeta Film!", null)).isEqualTo("title:zetafilm|");
    }

    @Test
    void aFirstRunPlacesEveryFilmByItsHashWithAFoldOnlyOnDevelopment() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(1, "tmdb:1004"), film(2, "tmdb:1003"),
            film(3, "tmdb:75")), NONE, Set.of());

        assertThat(result.entries().values()).containsExactly(entry(1, "tmdb:1004", Side.DEV, 4),
            entry(2, "tmdb:1003", Side.TEST, null), entry(3, "tmdb:75", Side.TEST, null));
        assertThat(result.keptOnOldSide()).isEmpty();
        assertThat(result.preseedOverridden()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.unitsSplit()).isZero();
        assertThat(result.preseedMissing()).isEmpty();
    }

    @Test
    void aPreseededFilmAndItsWholeUnitGoToDevelopmentWithTheHashFold() {
        // tmdb:1003 hashes to test; ctmdb:501 hashes to test and holds films 7 and 8, film 7 is pre-seeded
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "tmdb:1003"), film(7, "ctmdb:501"),
            film(8, "ctmdb:501")), NONE, Set.of(7L, 99L));

        assertThat(result.entries().values()).containsExactly(entry(3, "tmdb:1003", Side.TEST, null),
            entry(7, "ctmdb:501", Side.DEV, 2), entry(8, "ctmdb:501", Side.DEV, 2));
        assertThat(result.preseedMissing()).containsExactly(99L);
        assertThat(result.unitsSplit()).isZero();
    }

    @Test
    void aFilmWithAPreviousEntryKeepsItsSideAndFoldEvenAgainstItsHash() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(1, "tmdb:1004")), previous(
            entry(1, "tmdb:1004", Side.TEST, null)), Set.of());

        assertThat(result.entries().get(1L)).isEqualTo(entry(1, "tmdb:1004", Side.TEST, null));
        assertThat(result.keptOnOldSide()).isEmpty();
        assertThat(result.preseedOverridden()).isEmpty();
    }

    @Test
    void aFilmThatChangedUnitKeepsItsOldSideIsReportedAndRecordsItsNewUnitKey() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(4, "ctmdb:500")), previous(
            entry(4, "tmdb:1004", Side.TEST, null)), Set.of());

        assertThat(result.entries().get(4L)).isEqualTo(entry(4, "ctmdb:500", Side.TEST, null));
        assertThat(result.keptOnOldSide()).containsExactly(4L);
    }

    @Test
    void thePreviousManifestWinsOverThePreseedAndTheConflictIsReported() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(7, "tmdb:1007"), film(8, "tmdb:1008")),
            previous(entry(7, "tmdb:1007", Side.TEST, null), entry(8, "tmdb:1008", Side.DEV, 1)), Set.of(7L, 8L));

        assertThat(result.entries().get(7L)).isEqualTo(entry(7, "tmdb:1007", Side.TEST, null));
        assertThat(result.preseedOverridden()).containsExactly(7L);
    }

    @Test
    void aNewFilmTakesTheSideAndFoldOfTheLowestFilmIdMemberOfItsUnit() {
        CreditsSplit.Result result = CreditsSplit.assign(
            List.of(film(3, "ctmdb:500"), film(4, "ctmdb:500"), film(5, "ctmdb:500")),
            previous(entry(5, "ctmdb:500", Side.DEV, 3), entry(4, "ctmdb:500", Side.DEV, 2)), Set.of());

        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "ctmdb:500", Side.DEV, 2));
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void aNewFilmJoiningATestUnitGoesToTestWithNoFold() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "tmdb:1004"), film(4, "tmdb:1004")),
            previous(entry(4, "tmdb:1004", Side.TEST, null)), Set.of());

        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "tmdb:1004", Side.TEST, null));
    }

    @Test
    void aNewFilmWhoseUnitHasMembersOnBothSidesIsAConflictWithItsOwnHashSideForReference() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "ctmdb:500"), film(4, "ctmdb:500"),
            film(5, "ctmdb:500")), previous(entry(4, "ctmdb:500", Side.DEV, 2), entry(5, "ctmdb:500", Side.TEST, null)),
            Set.of());

        assertThat(result.conflicts()).containsExactly(3L);
        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "ctmdb:500", Side.DEV, 1));
        assertThat(result.entries().get(4L)).isEqualTo(entry(4, "ctmdb:500", Side.DEV, 2));
        assertThat(result.entries().get(5L)).isEqualTo(entry(5, "ctmdb:500", Side.TEST, null));
        assertThat(result.unitsSplit()).isEqualTo(1);
    }

    @Test
    void aConflictOnATestHashedUnitHasNoFold() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "tmdb:1003"), film(4, "tmdb:1003"),
            film(5, "tmdb:1003")), previous(entry(4, "tmdb:1003", Side.DEV, 0), entry(5, "tmdb:1003", Side.TEST, null)),
            Set.of());

        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "tmdb:1003", Side.TEST, null));
        assertThat(result.conflicts()).containsExactly(3L);
    }

    @Test
    void aPreviousMemberNoLongerInTheExportStillCountsUnderItsOldUnit() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "ctmdb:500")),
            previous(entry(99, "ctmdb:500", Side.TEST, null)), Set.of());

        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "ctmdb:500", Side.TEST, null));
        assertThat(result.keptOnOldSide()).isEmpty();
    }

    @Test
    void aPreviousMemberThatMovedToAnotherUnitIsCountedInItsCurrentUnitNotItsOld() {
        // film 4 was alone in tmdb:1004 and is now in ctmdb:500; new film 3 is in ctmdb:500 as well
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "ctmdb:500"), film(4, "ctmdb:500")),
            previous(entry(4, "tmdb:1004", Side.DEV, 4)), Set.of());

        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "ctmdb:500", Side.DEV, 4));
        assertThat(result.keptOnOldSide()).containsExactly(4L);
    }

    @Test
    void aNewFilmInAPreseededUnitWithPreviousMembersTakesTheMembersSideFirst() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(3, "ctmdb:501"), film(4, "ctmdb:501")),
            previous(entry(4, "ctmdb:501", Side.TEST, null)), Set.of(3L));

        assertThat(result.entries().get(3L)).isEqualTo(entry(3, "ctmdb:501", Side.TEST, null));
    }

    @Test
    void unitsSplitCountsUnitsWithFilmsOnBothSides() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(1, "ctmdb:500"), film(2, "ctmdb:500"),
            film(3, "ctmdb:501"), film(4, "ctmdb:501"), film(5, "tmdb:1004")),
            previous(entry(1, "ctmdb:500", Side.DEV, 1), entry(2, "ctmdb:500", Side.TEST, null),
                entry(3, "ctmdb:501", Side.DEV, 1), entry(4, "ctmdb:501", Side.DEV, 1)), Set.of());

        assertThat(result.unitsSplit()).isEqualTo(1);
    }

    @Test
    void theOutputIsInFilmIdOrderWhateverTheInputOrder() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(30, "tmdb:1004"), film(10, "tmdb:1003"),
            film(20, "tmdb:1008")), NONE, Set.of());

        assertThat(result.entries().keySet()).containsExactly(10L, 20L, 30L);
    }

    private static ManifestEntry looked(long id, String unit) {
        return new ManifestEntry(id, unit, Side.TEST, null, true);
    }

    @Test
    void aLookedAtNewcomerTakingTheTestSideThroughRuleTwoIsExcludedAndCounted() {
        // ctmdb:500 has an old member on test; film 5 joins it and is listed
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(1, "ctmdb:500"), film(5, "ctmdb:500")),
            previous(entry(1, "ctmdb:500", Side.TEST, null)), Set.of(), Set.of(5L));

        assertThat(result.entries().get(5L)).isEqualTo(looked(5, "ctmdb:500"));
        assertThat(result.entries().get(1L)).isEqualTo(entry(1, "ctmdb:500", Side.TEST, null));
        assertThat(result.lookedAt()).containsExactly(5L);
        assertThat(result.unitsSplit()).isZero();
    }

    @Test
    void aLookedAtFilmOnTestByTheHashAloneIsExcludedAndCounted() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(2, "tmdb:1003")), NONE, Set.of(), Set.of(2L));

        assertThat(result.entries().get(2L)).isEqualTo(looked(2, "tmdb:1003"));
        assertThat(result.lookedAt()).containsExactly(2L);
    }

    @Test
    void aLookedAtFilmOnDevelopmentIsUnaffected() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(1, "tmdb:1004")), NONE, Set.of(), Set.of(1L));

        assertThat(result.entries().get(1L)).isEqualTo(entry(1, "tmdb:1004", Side.DEV, 4));
        assertThat(result.entries().get(1L).lookedAt()).isNull();
        assertThat(result.lookedAt()).isEmpty();
    }

    @Test
    void anUnlistedFilmOnTestIsNotFlagged() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(2, "tmdb:1003"), film(3, "tmdb:75")), NONE,
            Set.of(), Set.of(3L));

        assertThat(result.entries().get(2L)).isEqualTo(entry(2, "tmdb:1003", Side.TEST, null));
        assertThat(result.lookedAt()).containsExactly(3L);
    }

    @Test
    void aListedSplitConflictFilmKeepsTheConflictAndIsNotFlagged() {
        CreditsSplit.Result result = CreditsSplit.assign(
            List.of(film(3, "ctmdb:500"), film(4, "ctmdb:500"), film(5, "ctmdb:500")),
            previous(entry(3, "ctmdb:500", Side.DEV, 1), entry(4, "ctmdb:500", Side.TEST, null)), Set.of(),
            Set.of(5L));

        assertThat(result.conflicts()).containsExactly(5L);
        assertThat(result.entries().get(5L).lookedAt()).isNull();
        assertThat(result.lookedAt()).isEmpty();
    }

    @Test
    void aListedConflictFilmWhoseHashSideIsTestIsFlaggedAndStaysAConflict() {
        CreditsSplit.Result result = CreditsSplit.assign(
            List.of(film(3, "tmdb:1003"), film(4, "tmdb:1003"), film(5, "tmdb:1003")),
            previous(entry(3, "tmdb:1003", Side.DEV, 1), entry(4, "tmdb:1003", Side.TEST, null)), Set.of(),
            Set.of(5L));

        assertThat(result.conflicts()).containsExactly(5L);
        assertThat(result.entries().get(5L)).isEqualTo(looked(5, "tmdb:1003"));
        assertThat(result.lookedAt()).containsExactly(5L);
    }

    @Test
    void aFilmFlaggedInThePreviousManifestStaysFlaggedOnALaterRunWhetherListedOrNot() {
        Map<Long, ManifestEntry> before = previous(looked(5, "ctmdb:500"));

        CreditsSplit.Result notListed = CreditsSplit.assign(List.of(film(5, "ctmdb:500")), before, Set.of(), Set.of());
        CreditsSplit.Result listed = CreditsSplit.assign(List.of(film(5, "ctmdb:500")), before, Set.of(), Set.of(5L));

        assertThat(notListed.entries().get(5L)).isEqualTo(looked(5, "ctmdb:500"));
        assertThat(notListed.lookedAt()).containsExactly(5L);
        assertThat(listed.entries().get(5L)).isEqualTo(looked(5, "ctmdb:500"));
        assertThat(listed.keptOnOldSide()).isEmpty();
    }

    @Test
    void aFilmFlaggedInThePreviousManifestThatChangedUnitKeepsTheFlagAndIsReportedAsMoved() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(5, "ctmdb:501")),
            previous(looked(5, "tmdb:1005")), Set.of(), Set.of());

        assertThat(result.entries().get(5L)).isEqualTo(looked(5, "ctmdb:501"));
        assertThat(result.keptOnOldSide()).containsExactly(5L);
    }

    @Test
    void aNewcomerToAUnitWithALookedAtMemberTakesTheMembersTestSideUnflagged() {
        CreditsSplit.Result result = CreditsSplit.assign(List.of(film(5, "ctmdb:500"), film(6, "ctmdb:500")),
            previous(looked(5, "ctmdb:500")), Set.of(), Set.of());

        assertThat(result.entries().get(6L)).isEqualTo(entry(6, "ctmdb:500", Side.TEST, null));
        assertThat(result.lookedAt()).containsExactly(5L);
    }

    @Test
    void theLookedAtFlagRoundTripsThroughJsonAndIsLeftOutWhenNull() {
        JsonMapper mapper = JsonMapper.builder().build();

        String flagged = mapper.writeValueAsString(looked(5, "ctmdb:500"));
        String plain = mapper.writeValueAsString(entry(1, "tmdb:1", Side.DEV, 2));

        assertThat(flagged).isEqualTo("{\"film_id\":5,\"unit_key\":\"ctmdb:500\",\"side\":\"test\",\"fold\":null,"
            + "\"looked_at\":true}");
        assertThat(plain).isEqualTo("{\"film_id\":1,\"unit_key\":\"tmdb:1\",\"side\":\"dev\",\"fold\":2}");
        assertThat(mapper.readValue(flagged, ManifestEntry.class)).isEqualTo(looked(5, "ctmdb:500"));
        assertThat(mapper.readValue(plain, ManifestEntry.class)).isEqualTo(entry(1, "tmdb:1", Side.DEV, 2));
        assertThat(looked(5, "x").isLookedAt()).isTrue();
        assertThat(entry(1, "x", Side.DEV, 2).isLookedAt()).isFalse();
    }

    @Test
    void theProductsUnitKeyEqualsTheOneL1WritesForEveryFixtureFile() throws IOException {
        LabelsJob.run(new LabelsOptions(FIXTURES.resolve("archive"), FIXTURES.resolve("export.csv"),
            FIXTURES.resolve("preseed.txt"), PlexRules.prefix(LearnFixtures.PREFIX), LocalDate.of(2026, 11, 13), temp,
            null, null, null));
        List<ExportRow> rows = ExportReader.parse(Files.readString(FIXTURES.resolve("export.csv")));
        Map<Long, String> l1 = new java.util.HashMap<>();
        for (JsonNode node : JsonMapper.builder().build().readTree(
            Files.readString(temp.resolve("labels-2026-11-13.json")))) {
            l1.put(node.path("media_file_id").asLong(), node.path("unit_key").asString());
        }

        Set<String> kinds = new HashSet<>();
        for (ExportRow row : rows) {
            String product = new UnitInputs(row.collectionTmdbId(), row.collectionId(), row.filmTmdbId(),
                row.filmTitle(), row.filmYear()).unitKey();
            assertThat(product).as("file %d", row.mediaFileId()).isEqualTo(l1.get(row.mediaFileId()));
            kinds.add(product.substring(0, product.indexOf(':')));
        }
        assertThat(kinds).containsExactlyInAnyOrder("ctmdb", "cid", "tmdb", "title");
    }
}
