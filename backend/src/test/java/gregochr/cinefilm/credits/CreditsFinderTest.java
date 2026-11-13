package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CoarseOutcome.Candidate;
import gregochr.cinefilm.credits.CoarseOutcome.Rejected;
import gregochr.cinefilm.credits.SyntheticFrames.DenseBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditsFinderTest {

    private static final ActiveArea WHOLE = new ActiveArea(0, 0, 160, 90);
    private static final int PEAK = 235;
    /** A candidate one hour in. */
    private static final long START = 3_600_000;
    private static final Candidate CANDIDATE = new Candidate(START);

    // ---- Coarse step ---------------------------------------------------------------------------------------

    /** Samples from a spec like {@code "P50 B1 T60"}: a class letter and a count, one sample every 5 seconds. */
    private static List<CoarseSample> coarse(String spec) {
        List<CoarseSample> samples = new ArrayList<>();
        for (String part : spec.isBlank() ? new String[0] : spec.split(" ")) {
            FrameClass frameClass = switch (part.charAt(0)) {
                case 'P' -> FrameClass.PICTURE;
                case 'B' -> FrameClass.BLACK;
                default -> FrameClass.TEXT_ON_DARK;
            };
            for (int i = Integer.parseInt(part.substring(1)); i > 0; i--) {
                samples.add(new CoarseSample(5_000L * samples.size(), frameClass));
            }
        }
        return samples;
    }

    private static CoarseOutcome coarseOutcome(String spec) {
        List<CoarseSample> samples = coarse(spec);
        return CreditsFinder.findCandidate(samples, 5_000L * samples.size());
    }

    private static void assertCandidate(String spec, long startMs) {
        assertThat(coarseOutcome(spec)).isEqualTo(new Candidate(startMs));
    }

    private static void assertNotFound(String spec, String reason) {
        assertThat(coarseOutcome(spec)).isEqualTo(new Rejected(CreditsResult.notFound(reason)));
    }

    @Test
    void aLongRunAtTheEndIsTheCandidateAtItsFirstSample() {
        assertCandidate("P100 B1 T60", 500_000);
    }

    @Test
    void aMidCreditsSceneUnder120SecondsKeepsTheFirstStart() {
        assertCandidate("P20 T40 P10 T20", 100_000);
    }

    @Test
    void aPictureGapOf120SecondsIsAllowedAndOf125IsNot() {
        assertCandidate("P20 T40 P24 T20", 100_000);
        assertCandidate("P20 T40 P25 T20", 425_000);
    }

    @Test
    void aPostCreditsSceneUpTo120SecondsAtTheEndIsAllowed() {
        assertCandidate("P50 T40 P24", 250_000);
        assertNotFound("P50 T40 P25", "The last 2 min of the film is picture, not credits");
    }

    @Test
    void aGapOver120SecondsMakesItTheLaterRunEvenWhenTheEarlierIsLonger() {
        assertCandidate("P20 T100 P30 T14", 20 * 5_000 + 100 * 5_000 + 30 * 5_000);
    }

    @Test
    void aSingleCardBeforeThePictureIsNotPartOfTheRun() {
        assertCandidate("P50 T1 P10 T40", 305_000);
    }

    @Test
    void twoLeadingCardsAreBothSkipped() {
        assertCandidate("P50 T1 P3 B1 P3 T40", 50 * 5_000 + 8 * 5_000);
    }

    @Test
    void aLeadingClusterOfTwoSamplesIsKept() {
        assertCandidate("P50 T2 P10 T40", 250_000);
    }

    @Test
    void aSingleBlackSampleBeforeThePictureIsAlsoSkipped() {
        assertCandidate("P50 B1 P10 T40", 305_000);
    }

    @Test
    void aStarfieldOrBlackEndingIsNotFound() {
        assertNotFound("P20 B60", "The ending is mostly black, with only 0 s of text on a dark screen");
    }

    @Test
    void anEpilogueCardThenPictureToTheEndIsNotFound() {
        assertNotFound("P50 T1 P30", "The last 3 min of the film is picture, not credits");
        assertNotFound("P50 T1 P10",
            "Black or text on a dark screen lasts only 5 s, and credits last at least a minute");
    }

    @Test
    void aShortTheEndCardIsNotFoundBelowSixtySeconds() {
        assertNotFound("P50 T11",
            "Black or text on a dark screen lasts only 55 s, and credits last at least a minute");
        assertCandidate("P50 T12", 250_000);
    }

    @Test
    void aRunUnderSixtySecondsMadeOfBlackAndTextIsNotFound() {
        assertNotFound("P50 B3 T8",
            "Black or text on a dark screen lasts only 55 s, and credits last at least a minute");
    }

    @Test
    void theRunNeedsThirtySecondsOfText() {
        assertCandidate("P50 B6 T6", 250_000);
        assertNotFound("P50 B7 T5", "The ending is mostly black, with only 25 s of text on a dark screen");
    }

    @Test
    void theTrailingPictureAfterTheRunMayBe120SecondsAndNotMore() {
        assertCandidate("P50 T12 P24", 250_000);
        assertNotFound("P50 T12 P25", "The last 2 min of the film is picture, not credits");
    }

    @Test
    void aWindowWithNoBlackOrTextIsNotFound() {
        String reason = "The ending has no black screen and no text on a dark screen";

        assertNotFound("P60", reason);
        assertNotFound("", reason);
    }

    @Test
    void samplesInAnyOrderAreSortedFirst() {
        List<CoarseSample> samples = coarse("P20 T40");
        Collections.reverse(samples);

        assertThat(CreditsFinder.findCandidate(samples, 300_000)).isEqualTo(new Candidate(100_000));
    }

    @Test
    void theWindowEndDecidesHowMuchPictureTrailsTheRun() {
        List<CoarseSample> samples = coarse("P20 T40");

        assertThat(CreditsFinder.findCandidate(samples, 300_000 + 120_000)).isEqualTo(new Candidate(100_000));
        assertThat(CreditsFinder.findCandidate(samples, 300_000 + 120_001))
            .isEqualTo(new Rejected(CreditsResult.notFound("The last 2 min of the film is picture, not credits")));
    }

    // ---- Dense step ----------------------------------------------------------------------------------------

    private static List<DenseFrame> dense(DenseBuilder builder) {
        return builder.build(START - 20_000);
    }

    private static DenseBuilder filmThen() {
        return new DenseBuilder().add(SyntheticFrames.picture(), 40);
    }

    private static CreditsResult refine(DenseBuilder builder) {
        return CreditsFinder.refine(CANDIDATE, dense(builder), WHOLE, PEAK, null);
    }

    @Test
    void aCrawlIsFoundAtItsFirstFrame() {
        CreditsResult result = refine(filmThen().crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START);
        assertThat(result.reasons()).containsExactly("Text on a dark screen moves up steadily from 1:00:00");
    }

    @Test
    void cardsAreFoundAtTheFirstCard() {
        CreditsResult result = refine(filmThen().cards(5, 12).add(SyntheticFrames.black(), 40));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 6_000);
        assertThat(result.reasons()).containsExactly("Cards of text on a dark screen change from 1:00:06");
    }

    @Test
    void aFadeOfTenSecondsOrLessIsPartOfTheCredits() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.black(), 20).crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START);
    }

    @Test
    void blackLongerThanTenSecondsIsPartOfTheFilm() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.black(), 21).crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 10_500);
    }

    @Test
    void aWindowCutShortByTheEndOfTheFramesStillShowsTheCrawl() {
        CreditsResult result = refine(filmThen().crawl(10, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START);
    }

    @Test
    void aPictureBetweenTheBlackAndTheTextBreaksTheFade() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.black(), 2).add(SyntheticFrames.picture(), 4)
            .crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 3_000);
    }

    @Test
    void sparseTwoLineCardsThatChangeTwiceAreFound() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.sparseCard(0), 12)
            .add(SyntheticFrames.sparseCard(1), 12).add(SyntheticFrames.sparseCard(2), 12)
            .add(SyntheticFrames.black(), 40));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 6_000);
        assertThat(result.reasons()).containsExactly("Cards of text on a dark screen change from 1:00:06");
    }

    @Test
    void aSingleOneLineTheEndCardUnderSixtySecondsIsStillNotFound() {
        FrameClass card = FrameFeatures.measure(SyntheticFrames.oneLineCard(), WHOLE, PEAK).frameClass();
        assertThat(card).isEqualTo(FrameClass.TEXT_ON_DARK);

        assertNotFound("P50 T8", "Black or text on a dark screen lasts only 40 s, and credits last at least a minute");
    }

    @Test
    void aStaticEpilogueCardThenShortBlackThenACrawlStartsAtTheBlack() {
        // 8 s of card, 4 s of black, then the crawl: the marker is the start of the black, after the card.
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 16).add(SyntheticFrames.black(), 8)
            .crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 8_000);
    }

    @Test
    void aStaticCardRightBeforeACrawlStartsAtTheFirstMovingPair() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 16).crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 8_000);
    }

    @Test
    void aStaticCardBeforeCardsThatChangeStartsAtTheFirstChange() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 16).add(SyntheticFrames.card(1), 12)
            .add(SyntheticFrames.card(2), 12).add(SyntheticFrames.card(3), 12).add(SyntheticFrames.card(4), 12));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 8_000);
        assertThat(result.reasons()).containsExactly("Cards of text on a dark screen change from 1:00:08");
    }

    @Test
    void aStaticCardThenAShortPictureThenACrawlStartsAtTheCrawl() {
        // 20 s of card, 2 s of picture, then the crawl: going into and out of the picture is not a card change.
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 40).add(SyntheticFrames.picture(), 4)
            .crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 22_000);
        assertThat(result.reasons()).containsExactly("Text on a dark screen moves up steadily from 1:00:22");
    }

    @Test
    void cardsSeparatedByPictureAreNotCards() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 12).add(SyntheticFrames.picture(), 2)
            .add(SyntheticFrames.card(1), 12).add(SyntheticFrames.picture(), 2).add(SyntheticFrames.card(2), 12));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).containsExactly("Text on a dark screen, but it never moves");
    }

    @Test
    void cardsWithShortBlackCutsAreStillCardsAndTheFadeIsCounted() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 12).add(SyntheticFrames.black(), 2)
            .add(SyntheticFrames.card(1), 12).add(SyntheticFrames.black(), 2).add(SyntheticFrames.card(2), 12)
            .add(SyntheticFrames.black(), 2).add(SyntheticFrames.card(3), 12));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        // The first change lands on card 1 at START + 7 s; the 1 s cut just before it is counted.
        assertThat(result.offsetMs()).isEqualTo(START + 6_000);
        assertThat(result.reasons()).containsExactly("Cards of text on a dark screen change from 1:00:06");
    }

    @Test
    void twelveSecondsOfBlackBeforeTheCrawlIsNotCounted() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.black(), 24).crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 12_000);
    }

    @Test
    void aShortFadeIsCountedFromWhereTheBlackBegins() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.black(), 6).crawl(200, 3));

        assertThat(result.offsetMs()).isEqualTo(START);
    }

    @Test
    void aColourTitleSequenceBeforeTheCrawlLeavesTheMarkerAtTheCrawl() {
        List<CoarseSample> samples = coarse("P30 T30");
        CoarseOutcome outcome = CreditsFinder.findCandidate(samples, 300_000);
        assertThat(outcome).isEqualTo(new Candidate(150_000));

        DenseBuilder builder = new DenseBuilder().add(SyntheticFrames.title(), 40).crawl(200, 3);
        CreditsResult result = CreditsFinder.refine((Candidate) outcome, builder.build(130_000), WHOLE, PEAK, null);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(150_000);
    }

    @Test
    void textThatNeverMovesIsUnsure() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 100));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.offsetMs()).isEqualTo(START);
        assertThat(result.reasons()).containsExactly("Text on a dark screen, but it never moves");
    }

    @Test
    void anEpilogueCardThenPictureIsUnsureBecauseItNeverMoves() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.card(0), 4)
            .add(SyntheticFrames.picture(), 100));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).containsExactly("Text on a dark screen, but it never moves");
    }

    @Test
    void aStarfieldWithNoTextIsUnsure() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.starfield(), 100));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons())
            .containsExactly("Looking closely at the start of the credits found no text on a dark screen");
    }

    @Test
    void aWindowOfNothingButPictureIsUnsure() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.picture(), 100));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.offsetMs()).isEqualTo(START);
    }

    @Test
    void noDenseFramesIsUnsure() {
        CreditsResult result = CreditsFinder.refine(CANDIDATE, List.of(), WHOLE, PEAK, null);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
    }

    @Test
    void denseFramesInAnyOrderAreSortedFirst() {
        List<DenseFrame> frames = new ArrayList<>(dense(filmThen().crawl(200, 3)));
        Collections.reverse(frames);

        CreditsResult result = CreditsFinder.refine(CANDIDATE, frames, WHOLE, PEAK, null);

        assertThat(result.offsetMs()).isEqualTo(START);
    }

    @Test
    void aCrawlEnteringFromTheBottomIsFoundLateNotLost() {
        DenseBuilder builder = filmThen();
        for (int i = 0; i < 200; i++) {
            builder.add(SyntheticFrames.crawlFromBottom(i, 3), 1);
        }

        CreditsResult result = refine(builder);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isGreaterThanOrEqualTo(START);
    }

    @Test
    void creditsStartingThreeMinutesAfterAnEndTitleSequenceOverPictureAreFoundWhenTheDenseWindowReachesThem() {
        // 3 minutes of colour title sequence (the candidate is its start), then the crawl.
        CreditsResult result = refine(filmThen().add(SyntheticFrames.title(), 360).crawl(200, 3));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(START + 180_000);
    }

    @Test
    void creditsThreeMinutesAfterTheTitleSequenceAreUnsureWhenTheDenseWindowEndsFirst() {
        CreditsResult result = refine(filmThen().add(SyntheticFrames.title(), 100));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
    }

    @Test
    void cardsFadingThroughBlackAreFoundAtTheFirstChange() {
        DenseBuilder builder = filmThen();
        for (int name = 0; name < 5; name++) {
            builder.add(SyntheticFrames.creditCard(name), 8).add(SyntheticFrames.black(), 16);
        }

        CreditsResult result = refine(builder);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.reasons()).hasSize(1);
        assertThat(result.reasons().getFirst()).startsWith("Cards of text on a dark screen change from");
    }

    @Test
    void aSlowTiltOverLitWindowsIsUnsureNotFound() {
        DenseBuilder builder = filmThen();
        for (int i = 0; i < 200; i++) {
            builder.add(SyntheticFrames.tiltedWindows(i), 1);
        }

        assertThat(refine(builder).verdict()).isEqualTo(CreditsVerdict.UNSURE);
    }

    @Test
    void aDarkSceneWithASmallFigureMovingIsUnsureNotFound() {
        DenseBuilder builder = filmThen();
        for (int i = 0; i < 200; i++) {
            builder.add(SyntheticFrames.figure(40 + 2 * i % 60), 1);
        }

        assertThat(refine(builder).verdict()).isEqualTo(CreditsVerdict.UNSURE);
    }

    @Test
    void aLetterboxedFrameWithAWiderLitStreetIsJudgedInsideItsActiveArea() {
        ActiveArea area = new ActiveArea(0, 11, 160, 68);
        List<DenseFrame> night = new DenseBuilder().add(SyntheticFrames.letterboxedNightWithAWiderStreet(20), 200)
            .build(START - 20_000);

        CreditsResult result = CreditsFinder.refine(CANDIDATE, night, area, 230, null);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons())
            .containsExactly("Looking closely at the start of the credits found no text on a dark screen");
    }

    @Test
    void theOriginalLetterboxedNightSceneReadsAsTextButItsStillLightsNeverMakeAFoundResult() {
        // 74% dark inside its active area, so under 0.70 it is TEXT_ON_DARK: only the motion check rejects it.
        ActiveArea area = new ActiveArea(0, 11, 160, 68);
        List<DenseFrame> night = new DenseBuilder().add(SyntheticFrames.letterboxedNight(20), 200)
            .build(START - 20_000);

        CreditsResult result = CreditsFinder.refine(CANDIDATE, night, area, 230, null);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).containsExactly("Text on a dark screen, but it never moves");
    }

    // ---- Subtitles -----------------------------------------------------------------------------------------

    private static CreditsResult withCues(Long... cues) {
        return CreditsFinder.refine(CANDIDATE, dense(filmThen().crawl(200, 3)), WHOLE, PEAK, List.of(cues));
    }

    @Test
    void dialogueTwoMinutesPastTheStartMakesItUnsure() {
        CreditsResult result = withCues(START + 120_000);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.offsetMs()).isEqualTo(START);
        assertThat(result.reasons()).containsExactly("Someone is still talking 2 min after the credits seem to start");
    }

    @Test
    void dialogueUpToThirtySecondsPastTheStartIsAllowedAndMoreIsNot() {
        assertThat(withCues(START + 30_000).verdict()).isEqualTo(CreditsVerdict.FOUND);

        CreditsResult result = withCues(START + 30_001);
        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).containsExactly("Someone is still talking 30 s after the credits seem to start");
    }

    @Test
    void cuesBeforeTheStartAreIgnored() {
        assertThat(withCues(START - 1, START - 600_000).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void aCueAtExactlyTheStartIsNoContradiction() {
        assertThat(withCues(START).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void cuesAfterASilenceOver120SecondsAreIgnored() {
        // 10 s in, then nothing for 120.001 s: the later cue is another conversation, not this one.
        assertThat(withCues(START + 10_000, START + 130_001).verdict()).isEqualTo(CreditsVerdict.FOUND);
        // A silence of exactly 120 s still continues the conversation.
        CreditsResult result = withCues(START + 10_000, START + 130_000);
        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).containsExactly("Someone is still talking 2 min after the credits seem to start");
    }

    @Test
    void aSilenceOver120SecondsRightAfterTheStartIgnoresEverythingLater() {
        assertThat(withCues(START + 120_001, START + 125_000).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void cuesInAnyOrderAreSortedFirst() {
        CreditsResult result = withCues(START + 130_000, START + 10_000);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
    }

    @Test
    void noCuesOrAnEmptyListIsNoContradiction() {
        assertThat(withCues().verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(refine(filmThen().crawl(200, 3)).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void theStartIsReportedWithADialogueContradictionAfterAFade() {
        CreditsResult result = CreditsFinder.refine(CANDIDATE,
            dense(filmThen().add(SyntheticFrames.black(), 20).crawl(200, 3)), WHOLE, PEAK,
            List.of(START + 100_000));

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.offsetMs()).isEqualTo(START);
    }

    @Test
    void theClockShowsHoursMinutesAndSeconds() {
        Candidate later = new Candidate(2 * 3_600_000L + 5 * 60_000L + 9_000L);
        List<DenseFrame> frames = filmThen().crawl(200, 3).build(later.startMs() - 20_000);

        CreditsResult result = CreditsFinder.refine(later, frames, WHOLE, PEAK, null);

        assertThat(result.reasons()).containsExactly("Text on a dark screen moves up steadily from 2:05:09");
    }
}
