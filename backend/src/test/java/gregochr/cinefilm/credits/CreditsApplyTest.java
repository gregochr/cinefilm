package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsApply.Applied;
import gregochr.cinefilm.credits.CreditsApply.AutoInputs;
import gregochr.cinefilm.credits.CreditsApply.Detection;
import gregochr.cinefilm.credits.CreditsApply.Part;
import gregochr.cinefilm.credits.CreditsApply.PlexRow;
import gregochr.cinefilm.credits.CreditsApply.PlexState;
import gregochr.cinefilm.credits.CreditsApply.PlexStatus;
import gregochr.cinefilm.credits.CreditsApply.UnitInputs;
import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.film.CreditsMarkerSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The marker an edition should have: step 1 owner, step 2 Plex, step 3 automatic, step 4 cleared. */
class CreditsApplyTest {

    private static final String FILE = "4000000000:1700000000000";
    /** One part of 6,000,000 ms: 60% is 3,600,000; 60 s from the end is 5,940,000. */
    private static final List<Part> ONE_PART = List.of(new Part(1, 6_000_000L));
    /** Parts of 4,000,000 and 2,000,000 ms: 6,000,000 in all; the same limits. */
    private static final List<Part> TWO_PARTS = List.of(new Part(1, 4_000_000L), new Part(2, 2_000_000L));
    private static final Applied NONE = new Applied(null, null);

    private static Applied plex(List<Part> parts, long startMs) {
        return CreditsApply.apply(parts, FILE, null, null, new PlexRow(FILE, startMs));
    }

    @Test
    void aMatchingPlexRowInsideTheLimitsSetsPlexMarker() {
        assertThat(plex(ONE_PART, 5_000_000)).isEqualTo(new Applied(5_000_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void aPlexRowReplacesAnAutoMarkerAndAMarkerWithNoSource() {
        PlexRow row = new PlexRow(FILE, 5_000_000);

        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.AUTO, row))
            .isEqualTo(new Applied(5_000_000L, CreditsMarkerSource.PLEX));
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, null, row))
            .isEqualTo(new Applied(5_000_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void anOwnerMarkerIsKeptEvenWhenPlexHasAMatchingRow() {
        Applied applied = CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.OWNER,
            new PlexRow(FILE, 5_000_000));

        assertThat(applied).isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.OWNER));
    }

    @Test
    void anOwnerMarkerIsKeptWithNoFingerprintAndNoDurations() {
        Applied applied = CreditsApply.apply(List.of(new Part(1, null)), null, 5_500_000L,
            CreditsMarkerSource.OWNER, null);

        assertThat(applied).isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.OWNER));
    }

    @Test
    void aRowWhoseFingerprintNoLongerMatchesTheFileClearsTheMarker() {
        Applied applied = CreditsApply.apply(ONE_PART, "4000000001:1700000000000", 5_500_000L,
            CreditsMarkerSource.PLEX, new PlexRow(FILE, 5_000_000));

        assertThat(applied).isEqualTo(NONE);
    }

    @Test
    void noPlexRowClearsAnExistingMarker() {
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.AUTO, null)).isEqualTo(NONE);
    }

    @Test
    void sixtyPercentIntoTheEditionIsTheEarliestPlexMarkerAllowed() {
        assertThat(plex(ONE_PART, 3_600_000)).isEqualTo(new Applied(3_600_000L, CreditsMarkerSource.PLEX));
        assertThat(plex(ONE_PART, 3_599_999)).isEqualTo(NONE);
        assertThat(plex(ONE_PART, 3_600_001)).isEqualTo(new Applied(3_600_001L, CreditsMarkerSource.PLEX));
    }

    @Test
    void sixtySecondsBeforeTheEndIsTheLatestPlexMarkerAllowed() {
        assertThat(plex(ONE_PART, 5_940_000)).isEqualTo(new Applied(5_940_000L, CreditsMarkerSource.PLEX));
        assertThat(plex(ONE_PART, 5_939_999)).isEqualTo(new Applied(5_939_999L, CreditsMarkerSource.PLEX));
        assertThat(plex(ONE_PART, 5_940_001)).isEqualTo(NONE);
    }

    @Test
    void anOffsetOutsideTheLastPartGivesNoMarker() {
        assertThat(plex(ONE_PART, 6_000_001)).isEqualTo(NONE);
        assertThat(plex(ONE_PART, -1)).isEqualTo(NONE);
    }

    @Test
    void aTwoPartEditionsMarkerLandsAtPartTwosEditionTime() {
        assertThat(plex(TWO_PARTS, 1_500_000)).isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void noPartsOrAPartWithoutADurationMeansNoTimelineAndNoMarker() {
        assertThat(plex(List.of(), 5_000_000)).isEqualTo(NONE);
        assertThat(plex(List.of(new Part(1, null)), 5_000_000)).isEqualTo(NONE);
        assertThat(plex(List.of(new Part(1, 0L)), 5_000_000)).isEqualTo(NONE);
        assertThat(plex(List.of(new Part(1, 4_000_000L), new Part(2, null)), 1_500_000)).isEqualTo(NONE);
    }

    @Test
    void aLastPartWithNoFingerprintGivesNoMarker() {
        Applied applied = CreditsApply.apply(ONE_PART, null, 5_500_000L, CreditsMarkerSource.PLEX,
            new PlexRow(FILE, 5_000_000));

        assertThat(applied).isEqualTo(NONE);
    }

    @Test
    void anEditionWithNoMarkerAndNoRowStaysWithNone() {
        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, null)).isEqualTo(NONE);
    }

    private static PlexStatus status(List<Part> parts, String fingerprint, PlexRow row) {
        return CreditsApply.plexStatus(parts, fingerprint, row);
    }

    @Test
    void noRowIsNoneWhateverElseIsWrong() {
        assertThat(status(List.of(), null, null)).isEqualTo(new PlexStatus(PlexState.NONE, null));
        assertThat(status(ONE_PART, FILE, null)).isEqualTo(new PlexStatus(PlexState.NONE, null));
    }

    @Test
    void aRowWithNoFingerprintOnTheFileIsNoFingerprintBeforeAnyLaterCheck() {
        assertThat(status(List.of(new Part(1, null)), null, new PlexRow(FILE, 5_000_000)))
            .isEqualTo(new PlexStatus(PlexState.NO_FINGERPRINT, null));
    }

    @Test
    void aRowForAnOlderCopyIsStaleEvenWhenThePartHasNoDuration() {
        assertThat(status(List.of(new Part(1, null)), "1:2", new PlexRow(FILE, 5_000_000)))
            .isEqualTo(new PlexStatus(PlexState.STALE, null));
    }

    @Test
    void noPartsOrAPartWithoutADurationIsNoTimelineBeforeTheOffsetIsLookedAt() {
        PlexRow row = new PlexRow(FILE, 99_000_000);

        assertThat(status(List.of(), FILE, row)).isEqualTo(new PlexStatus(PlexState.NO_TIMELINE, null));
        assertThat(status(List.of(new Part(1, null)), FILE, row))
            .isEqualTo(new PlexStatus(PlexState.NO_TIMELINE, null));
    }

    @Test
    void anOffsetOutsideTheLastPartIsOutsideBeforeTheGuardIsApplied() {
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 6_000_001)))
            .isEqualTo(new PlexStatus(PlexState.OUTSIDE, null));
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, -1)))
            .isEqualTo(new PlexStatus(PlexState.OUTSIDE, null));
    }

    @Test
    void aTimeInsideThePartButOutsideTheGuardIsRefusedAndCarriesPlexsTime() {
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_599_999)))
            .isEqualTo(new PlexStatus(PlexState.REFUSED, 3_599_999L));
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_940_001)))
            .isEqualTo(new PlexStatus(PlexState.REFUSED, 5_940_001L));
    }

    @Test
    void theGuardBoundsAreUsableAtAndInsideAndRefusedOneMillisecondOutside() {
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_600_000)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_599_999)).state()).isEqualTo(PlexState.REFUSED);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_600_001)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_940_000)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_939_999)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_940_001)).state()).isEqualTo(PlexState.REFUSED);
    }

    @Test
    void aUsableRowCarriesItsTimeInEditionTime() {
        assertThat(status(TWO_PARTS, FILE, new PlexRow(FILE, 1_500_000)))
            .isEqualTo(new PlexStatus(PlexState.USABLE, 5_500_000L));
    }

    @Test
    void theStatusIsTheSameWhateverTheEditionsSourceIs() {
        PlexRow row = new PlexRow(FILE, 5_000_000);
        PlexStatus expected = new PlexStatus(PlexState.USABLE, 5_000_000L);

        assertThat(status(ONE_PART, FILE, row)).isEqualTo(expected);
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.OWNER, row))
            .isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.OWNER));
    }

    // ---- step 3: AUTO ----

    private static final DenyList EMPTY_LIST = new DenyList(Set.of(), Set.of(), Set.of(), Set.of());
    /** Unit key {@code tmdb:333371}: its hash is 40, development. */
    private static final UnitInputs DEV_FILM = new UnitInputs(null, null, 333371L, "Film", 2000);
    private static final String CRAWL = "Text on a dark screen moves up steadily from 1:23:20";
    private static final String CARDS = "Cards of text on a dark screen change from 1:23:20";
    private static final long EDITION = 7;
    private static final long FILM = 3;
    private static final Applied AUTO_5_020 = new Applied(5_020_000L, CreditsMarkerSource.AUTO);

    private static Detection found(long offsetMs, String reasons) {
        return new Detection(2, CreditsVerdict.FOUND, offsetMs, FILE, reasons);
    }

    private static AutoInputs inputs(boolean enabled, boolean cards, DenyList list, UnitInputs film,
                                     Detection detection) {
        return new AutoInputs(enabled, cards, list, EDITION, FILM, film, Map.of(), detection);
    }

    private static AutoInputs on(Detection detection) {
        return inputs(true, true, EMPTY_LIST, DEV_FILM, detection);
    }

    private static Applied auto(List<Part> parts, AutoInputs in) {
        return CreditsApply.apply(parts, FILE, null, null, null, in);
    }

    private static Applied auto(AutoInputs in) {
        return auto(ONE_PART, in);
    }

    private static Applied autoWithList(DenyList list) {
        return auto(inputs(true, true, list, DEV_FILM, found(5_000_000, CRAWL)));
    }

    @Test
    void theReasonOpeningsAreExactlyTheFindersTwoFoundSentences() {
        assertThat(CreditsFinder.CRAWL_OPENING).isEqualTo("Text on a dark screen moves up steadily from ");
        assertThat(CreditsFinder.CARD_OPENING).isEqualTo("Cards of text on a dark screen change from ");
    }

    @Test
    void aCrawlResultSetsAutoTwentySecondsAfterTheStart() {
        assertThat(auto(on(found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void aCardsResultSetsAutoWhileCardsAreOn() {
        assertThat(auto(inputs(true, true, EMPTY_LIST, DEV_FILM, found(5_000_000, CARDS)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void aCardsResultIsRefusedWhileCardsAreOffButACrawlStillCounts() {
        assertThat(auto(inputs(true, false, EMPTY_LIST, DEV_FILM, found(5_000_000, CARDS)))).isEqualTo(NONE);
        assertThat(auto(inputs(true, false, EMPTY_LIST, DEV_FILM, found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void onlyTheFirstReasonDecidesTheKind() {
        assertThat(auto(on(found(5_000_000, CRAWL + "\nThe credits would start only 5% into the film"))))
            .isEqualTo(AUTO_5_020);
        assertThat(auto(on(found(5_000_000, "Something else\n" + CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void anotherOrEmptyReasonOrOneThatMerelyContainsTheOpeningIsNotAuto() {
        assertThat(auto(on(found(5_000_000, "Text on a dark screen, but it never moves")))).isEqualTo(NONE);
        assertThat(auto(on(found(5_000_000, "Text on a dark screen moves up steadily")))).isEqualTo(NONE);
        assertThat(auto(on(found(5_000_000, "")))).isEqualTo(NONE);
        assertThat(auto(on(found(5_000_000, " " + CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void onlyAFoundResultWithAnOffsetCounts() {
        assertThat(auto(on(new Detection(2, CreditsVerdict.UNSURE, 5_000_000L, FILE, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(on(new Detection(2, CreditsVerdict.NOT_FOUND, 5_000_000L, FILE, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(on(new Detection(2, CreditsVerdict.FOUND, null, FILE, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(on(null))).isEqualTo(NONE);
    }

    @Test
    void onlyAnalysisVersionTwoCounts() {
        assertThat(CreditsApply.AUTO_ANALYSIS_VERSION).isEqualTo(2);
        assertThat(auto(on(new Detection(1, CreditsVerdict.FOUND, 5_000_000L, FILE, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(on(new Detection(3, CreditsVerdict.FOUND, 5_000_000L, FILE, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void aStaleOrMissingFingerprintIsNotAuto() {
        String other = "4000000001:1700000000000";
        assertThat(auto(on(new Detection(2, CreditsVerdict.FOUND, 5_000_000L, other, CRAWL)))).isEqualTo(NONE);
        assertThat(CreditsApply.apply(ONE_PART, null, null, null, null, on(found(5_000_000, CRAWL))))
            .isEqualTo(NONE);
    }

    @Test
    void theSwitchOffADenyListMissingAndAnUnmatchedFilmEachRefuse() {
        assertThat(auto(inputs(false, true, EMPTY_LIST, DEV_FILM, found(5_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(inputs(true, true, null, DEV_FILM, found(5_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(inputs(true, true, EMPTY_LIST, new UnitInputs(null, null, null, "Film", 2000),
            found(5_000_000, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void theStartAtTheGuardsLowerBoundMarksAndOneMillisecondUnderDoesNot() {
        assertThat(auto(on(found(3_600_000, CRAWL)))).isEqualTo(new Applied(3_620_000L, CreditsMarkerSource.AUTO));
        assertThat(auto(on(found(3_599_999, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void theStartAtTheGuardsUpperBoundMarksAtTheCapAndOneMillisecondOverDoesNot() {
        assertThat(auto(on(found(5_940_000, CRAWL)))).isEqualTo(new Applied(5_940_000L, CreditsMarkerSource.AUTO));
        assertThat(auto(on(found(5_940_001, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void theMarkerIsCappedSixtySecondsBeforeTheEnd() {
        assertThat(auto(on(found(5_920_000, CRAWL)))).isEqualTo(new Applied(5_940_000L, CreditsMarkerSource.AUTO));
        assertThat(auto(on(found(5_920_001, CRAWL)))).isEqualTo(new Applied(5_940_000L, CreditsMarkerSource.AUTO));
        assertThat(auto(on(found(5_919_999, CRAWL)))).isEqualTo(new Applied(5_939_999L, CreditsMarkerSource.AUTO));
    }

    @Test
    void anOffsetOutsideTheLastPartIsRefusedByToGlobal() {
        assertThat(auto(on(found(6_000_001, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(on(found(-1, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void anUnknownTotalOrNoPartsIsNotAuto() {
        assertThat(auto(List.of(new Part(1, null)), on(found(5_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(List.of(new Part(1, 4_000_000L), new Part(2, null)), on(found(1_000_000, CRAWL))))
            .isEqualTo(NONE);
        assertThat(auto(List.of(), on(found(5_000_000, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void aTwoPartEditionUsesTheLastPartsOffsetOnTheEditionTimeline() {
        assertThat(auto(TWO_PARTS, on(found(1_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
        assertThat(auto(TWO_PARTS, on(found(2_000_001, CRAWL)))).isEqualTo(NONE);
        // offset 0 of the last part is edition time 4,000,000 (66%): inside the guard
        assertThat(auto(TWO_PARTS, on(found(0, CRAWL)))).isEqualTo(new Applied(4_020_000L, CreditsMarkerSource.AUTO));
        assertThat(auto(TWO_PARTS, on(found(2_000_000, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void ownerAndPlexStillWinOverAuto() {
        AutoInputs in = on(found(5_000_000, CRAWL));

        assertThat(CreditsApply.apply(ONE_PART, FILE, 4_000_000L, CreditsMarkerSource.OWNER, null, in))
            .isEqualTo(new Applied(4_000_000L, CreditsMarkerSource.OWNER));
        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, new PlexRow(FILE, 4_000_000), in))
            .isEqualTo(new Applied(4_000_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void anUnusablePlexRowFallsThroughToAuto() {
        AutoInputs in = on(found(5_000_000, CRAWL));

        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, new PlexRow("4000000001:1", 4_000_000), in))
            .isEqualTo(AUTO_5_020);
        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, new PlexRow(FILE, 100), in)).isEqualTo(AUTO_5_020);
    }

    @Test
    void anAutoMarkerIsKeptWhileStepThreeHoldsAndClearedWhenItNoLongerDoes() {
        Detection d = found(5_000_000, CRAWL);

        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_020_000L, CreditsMarkerSource.AUTO, null, on(d)))
            .isEqualTo(AUTO_5_020);
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_020_000L, CreditsMarkerSource.AUTO, null,
            inputs(false, true, EMPTY_LIST, DEV_FILM, d))).isEqualTo(NONE);
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_020_000L, CreditsMarkerSource.AUTO, null,
            inputs(true, true, null, DEV_FILM, d))).isEqualTo(NONE);
    }

    @Test
    void callersWithNoAutoInputsNeverGetAuto() {
        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, null)).isEqualTo(NONE);
        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, null, null)).isEqualTo(NONE);
    }

    // ---- the six deny rules, each beside its not-denied twin ----

    @Test
    void aListThatNamesNoPartOfTheEditionDeniesNothing() {
        assertThat(autoWithList(EMPTY_LIST)).isEqualTo(AUTO_5_020);
        assertThat(autoWithList(new DenyList(Set.of(4L), Set.of("tmdb:1"), Set.of(2L), Set.of(8L))))
            .isEqualTo(AUTO_5_020);
    }

    @Test
    void ruleOneADeniedFilm() {
        assertThat(autoWithList(new DenyList(Set.of(FILM), Set.of(), Set.of(), Set.of()))).isEqualTo(NONE);
    }

    @Test
    void ruleTwoAFilmWhoseUnitKeyHashesToTestEvenWhenNothingIsListed() {
        UnitInputs testFilm = new UnitInputs(null, null, 75L, "Film", 2000);

        assertThat(auto(inputs(true, true, EMPTY_LIST, testFilm, found(5_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(inputs(true, true, EMPTY_LIST, new UnitInputs(null, null, 14L, "Film", 2000),
            found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void ruleThreeAListedUnitKey() {
        assertThat(autoWithList(new DenyList(Set.of(), Set.of("tmdb:333371"), Set.of(), Set.of()))).isEqualTo(NONE);
    }

    @Test
    void ruleFourAListedFileOfAnyPartEvenInAnotherEdition() {
        DenyList lastPart = new DenyList(Set.of(), Set.of(), Set.of(2L), Set.of());
        DenyList firstPart = new DenyList(Set.of(), Set.of(), Set.of(1L), Set.of());

        assertThat(auto(TWO_PARTS, new AutoInputs(true, true, lastPart, EDITION, FILM, DEV_FILM, Map.of(),
            found(1_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(TWO_PARTS, new AutoInputs(true, true, firstPart, 99L, FILM, DEV_FILM, Map.of(),
            found(1_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(TWO_PARTS, on(found(1_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void ruleFiveAListedEditionAlsoAfterAMergeMovedItToAnotherFilm() {
        DenyList list = new DenyList(Set.of(), Set.of(), Set.of(), Set.of(EDITION));

        assertThat(auto(new AutoInputs(true, true, list, EDITION, 55L, DEV_FILM, Map.of(), found(5_000_000, CRAWL))))
            .isEqualTo(NONE);
        assertThat(auto(new AutoInputs(true, true, list, 8L, 55L, DEV_FILM, Map.of(), found(5_000_000, CRAWL))))
            .isEqualTo(AUTO_5_020);
    }

    @Test
    void ruleSixAListedFilmWhoseUnitKeyIsTodayTheEditionsFilmsUnitKey() {
        DenyList list = new DenyList(Set.of(99L), Set.of(), Set.of(), Set.of());

        assertThat(auto(new AutoInputs(true, true, list, EDITION, FILM, DEV_FILM, Map.of(99L, "tmdb:333371"),
            found(5_000_000, CRAWL)))).isEqualTo(NONE);
        assertThat(auto(new AutoInputs(true, true, list, EDITION, FILM, DEV_FILM, Map.of(99L, "tmdb:1"),
            found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
        assertThat(auto(new AutoInputs(true, true, list, EDITION, FILM, DEV_FILM, Map.of(98L, "tmdb:333371"),
            found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
        assertThat(auto(new AutoInputs(true, true, list, EDITION, FILM, DEV_FILM, Map.of(),
            found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void ruleSixFollowsASplitConflictFilmListedByItsReferenceSide() {
        // film 99 is listed as a film (its reference side is test) although its unit is a SPLIT_CONFLICT one; a film
        // that has since joined that unit (same current key) is denied, a film in another unit is not
        DenyList list = new DenyList(Set.of(99L), Set.of(), Set.of(), Set.of());
        UnitInputs joined = new UnitInputs(263L, 7L, 333371L, "Film", 2000);
        // ctmdb:263 hashes to development, so only rule 6 can deny the joined film
        Map<Long, String> current = Map.of(99L, "ctmdb:263");

        assertThat(auto(new AutoInputs(true, true, list, EDITION, FILM, joined, current, found(5_000_000, CRAWL))))
            .isEqualTo(NONE);
        assertThat(auto(new AutoInputs(true, true, list, EDITION, FILM, DEV_FILM, current,
            found(5_000_000, CRAWL)))).isEqualTo(AUTO_5_020);
    }

    @Test
    void anUnclearEditionsFileMovedToANewEditionStaysDenied() {
        DenyList list = new DenyList(Set.of(), Set.of(), Set.of(2L), Set.of(EDITION));

        assertThat(auto(TWO_PARTS, new AutoInputs(true, true, list, 123L, FILM, DEV_FILM, Map.of(),
            found(1_000_000, CRAWL)))).isEqualTo(NONE);
    }

    @Test
    void anOlderCopyOfTheListIsAppliedInFullSoAFilmMovedIntoAListedUnitIsCleared() {
        DenyList older = new DenyList(Set.of(), Set.of("ctmdb:500"), Set.of(), Set.of());
        UnitInputs movedIn = new UnitInputs(500L, 7L, 333371L, "Film", 2000);

        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_020_000L, CreditsMarkerSource.AUTO, null,
            inputs(true, true, older, movedIn, found(5_000_000, CRAWL)))).isEqualTo(NONE);
    }

    // ---- the candidates: step 3 with the source left out ----

    @Test
    void aCrawlCandidateCarriesItsKindOffsetStartAndMarker() {
        assertThat(CreditsApply.candidate(ONE_PART, FILE, null, on(found(5_000_000, CRAWL))))
            .isEqualTo(new CreditsApply.AutoMarker(CreditsApply.AutoKind.CRAWL, 5_000_000, 5_000_000, 5_020_000));
    }

    @Test
    void aCardCandidateIsOfKindCardAndTwoPartsTurnTheOffsetIntoEditionTime() {
        assertThat(CreditsApply.candidate(TWO_PARTS, FILE, null, on(found(1_000_000, CARDS))))
            .isEqualTo(new CreditsApply.AutoMarker(CreditsApply.AutoKind.CARD, 1_000_000, 5_000_000, 5_020_000));
    }

    @Test
    void aCardCandidateIsNoneWhileCardsAreOff() {
        assertThat(CreditsApply.candidate(ONE_PART, FILE, null,
            inputs(true, false, EMPTY_LIST, DEV_FILM, found(5_000_000, CARDS)))).isNull();
    }

    @Test
    void aUsablePlexRowMeansNoCandidateButAnUnusableOneDoesNot() {
        AutoInputs in = on(found(5_000_000, CRAWL));

        assertThat(CreditsApply.candidate(ONE_PART, FILE, new PlexRow(FILE, 4_000_000), in)).isNull();
        assertThat(CreditsApply.candidate(ONE_PART, FILE, new PlexRow("4000000001:1", 4_000_000), in)).isNotNull();
    }

    @Test
    void noInputsMeansNoCandidate() {
        assertThat(CreditsApply.candidate(ONE_PART, FILE, null, null)).isNull();
    }

    @Test
    void aDeniedEditionIsNoCandidate() {
        DenyList list = new DenyList(Set.of(), Set.of(), Set.of(), Set.of(EDITION));

        assertThat(CreditsApply.candidate(ONE_PART, FILE, null,
            inputs(true, true, list, DEV_FILM, found(5_000_000, CRAWL)))).isNull();
    }
}
