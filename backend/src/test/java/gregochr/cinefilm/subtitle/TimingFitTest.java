package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Fitting a forced file's cue starts to a full English track's: a constant offset is found exactly, a dense reference
 * cannot give a fit by chance, and every way a file can fail to line up gets plain-words reasons. Synthetic tracks have
 * a cue every 2 to 8 seconds, like a real full track, from a fixed seed.
 */
class TimingFitTest {

    private static final long HOUR = 3_600_000L;

    /** A full track: {@code cues} starts with gaps of 2 to 8 seconds, from a fixed seed. */
    private static List<Long> track(int cues, long seed) {
        Random random = new Random(seed);
        List<Long> starts = new ArrayList<>();
        long at = 5_000;
        for (int i = 0; i < cues; i++) {
            starts.add(at);
            at += 2_000 + random.nextInt(6_001);
        }
        return starts;
    }

    /** A track so dense (a cue every 0.4 to 1.4 seconds) that a 300 ms window matches most times at random. */
    private static List<Long> dense(long seed) {
        Random random = new Random(seed);
        List<Long> starts = new ArrayList<>();
        long at = 0;
        for (int i = 0; i < 4000; i++) {
            at += 400 + random.nextInt(1_001);
            starts.add(at);
        }
        return starts;
    }

    /** Every {@code every}-th start of the track, moved by {@code -offset} (so it needs {@code +offset} to fit). */
    private static List<Long> forcedFrom(List<Long> track, int every, long offset) {
        return IntStream.range(0, track.size()).filter(i -> i % every == 0).mapToObj(i -> track.get(i) - offset)
            .toList();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 300, -300, 2_000, -2_000, 9_900, -9_900, 10_000, -10_000})
    void aConstantOffsetIsFoundExactlyAndIsAligned(long offset) {
        List<Long> track = track(600, 7);
        List<Long> forced = forcedFrom(track, 20, offset);

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.fittedOffsetMs()).isEqualTo((int) offset);
        assertThat(result.matchedShare()).isEqualTo(1.0);
        assertThat(result.matchedCount()).isEqualTo(30);
        assertThat(result.servedCount()).isEqualTo(30);
        assertThat(result.medianResidualMs()).isZero();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void anOffsetNotOnTheBucketGridIsRefinedToTenMilliseconds() {
        List<Long> track = track(600, 11);
        List<Long> forced = forcedFrom(track, 20, 237);

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.fittedOffsetMs()).isEqualTo(240);
        assertThat(result.medianResidualMs()).isEqualTo(3);
    }

    @Test
    void anOffsetOverTenSecondsIsFoundButIsACheckNotAnAlignment() {
        List<Long> track = track(600, 7);
        List<Long> forced = forcedFrom(track, 20, 14_000);

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.fittedOffsetMs()).isEqualTo(14_000);
        assertThat(result.matchedShare()).isEqualTo(1.0);
        assertThat(result.reasons()).containsExactly("Best fit is more than 10 s out (14.0 s later)");
    }

    @Test
    void anOffsetEarlierThanTenSecondsSaysEarlier() {
        List<Long> track = track(600, 7);

        Result result = TimingFit.fit(forcedFrom(track, 20, -25_000), track);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).containsExactly("Best fit is more than 10 s out (25.0 s earlier)");
    }

    @Test
    void aForcedFileThatIsASubsetOfTheTrackAlignsWhateverTheTrackHoldsBetween() {
        List<Long> track = track(1500, 3);

        Result result = TimingFit.fit(forcedFrom(track, 100, 1_200), track);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.servedCount()).isEqualTo(15);
        assertThat(result.fittedOffsetMs()).isEqualTo(1_200);
    }

    @Test
    void cuesThatWanderByAHundredMillisecondsStillAlignWithTheCentreOffset() {
        List<Long> track = track(600, 5);
        List<Long> forced = new ArrayList<>();
        for (int i = 0; i < track.size(); i += 20) {
            forced.add(track.get(i) - 800 + (i / 20 % 2 == 0 ? 100 : -100));
        }

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        // Anywhere from 700 to 900 leaves every cue within 200 ms at the same total distance: the smallest wins.
        assertThat(result.fittedOffsetMs()).isBetween(700, 900);
        assertThat(result.medianResidualMs()).isLessThanOrEqualTo(120);
        assertThat(result.matchedShare()).isEqualTo(1.0);
    }

    @Test
    void cuesThatAreAQuarterOfASecondOffEachWayLineUpOnlyLoosely() {
        List<Long> track = track(600, 5);
        List<Long> forced = new ArrayList<>();
        for (int i = 0; i < track.size(); i += 20) {
            forced.add(track.get(i) + (i / 20 % 2 == 0 ? 250 : -250));
        }

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.matchedShare()).isEqualTo(1.0);
        assertThat(result.fittedOffsetMs()).isBetween(-50, 50);
        assertThat(result.medianResidualMs()).isGreaterThan(120);
        assertThat(result.reasons()).singleElement().asString().startsWith("The lines line up only loosely (typically ")
            .endsWith(" ms off)");
    }

    @Test
    void aFileWhoseLinesAreMostlyNotInTheTrackSaysHowManyLineUp() {
        List<Long> track = track(600, 9);
        List<Long> forced = new ArrayList<>(forcedFrom(track, 150, 0));
        // Eight cues in the middle of the gaps between track cues: nowhere near any of them at any one offset.
        for (int i = 10; i < 600 && forced.size() < 12; i += 37) {
            forced.add(track.get(i) + 1_000 + i);
        }

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.servedCount()).isEqualTo(12);
        assertThat(result.matchedShare()).isLessThan(0.8);
        assertThat(result.reasons()).first().asString()
            .startsWith("Only " + result.matchedCount() + " of 12 lines line up with the film's English track");
    }

    @Test
    void aDenseTrackCannotGiveAnAlignmentToUnrelatedCuesByChance() {
        List<Long> dense = dense(42);
        int end = dense.get(dense.size() - 1).intValue();
        for (long seed = 1; seed <= 25; seed++) {
            Random other = new Random(seed);
            List<Long> unrelated = IntStream.range(0, 30).mapToObj(i -> (long) other.nextInt(end)).toList();

            Result result = TimingFit.fit(unrelated, dense);

            assertThat(result.verdict()).as("seed " + seed).isNotEqualTo(Verdict.ALIGNED);
        }
    }

    @Test
    void aTrackSoDenseThatEvenAFileThatIsInItCouldBeChanceIsNotAligned() {
        List<Long> dense = dense(8);

        Result result = TimingFit.fit(forcedFrom(dense, 130, 600), dense);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.matchedShare()).isEqualTo(1.0);
        assertThat(result.reasons()).containsExactly(
            "The film's English track has so many lines that this fit could be chance");
    }

    @Test
    void theRealToolFixtureIsAlignedAtTwoAndAHalfSecondsEarly() {
        List<Long> full = EmbeddedCueReaderRealToolsTest.irregular(120, 5_000);
        List<Long> early = EmbeddedCueReaderRealToolsTest.everyTenth(full).stream().map(s -> s - 2_500).toList();

        Result result = TimingFit.fit(early, full);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.fittedOffsetMs()).isEqualTo(2_500);
        assertThat(result.servedCount()).isEqualTo(12);
    }

    @Test
    void aSparseTrackKeepsTheMarginTheGuardAsksFor() {
        List<Long> track = track(80, 21);

        Result result = TimingFit.fit(forcedFrom(track, 5, 0), track);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
    }

    @Test
    void sevenCuesAreTooFewAndEightAreEnough() {
        List<Long> track = track(600, 7);

        Result seven = TimingFit.fit(forcedFrom(track, 80, 500).subList(0, 7), track);
        Result eight = TimingFit.fit(forcedFrom(track, 75, 500), track);

        assertThat(seven.verdict()).isEqualTo(Verdict.TOO_FEW);
        assertThat(seven.servedCount()).isEqualTo(7);
        assertThat(seven.fittedOffsetMs()).isZero();
        assertThat(seven.matchedCount()).isZero();
        assertThat(seven.reasons()).containsExactly("Too few lines to check (7, at least 8 are needed)");
        assertThat(eight.servedCount()).isEqualTo(8);
        assertThat(eight.verdict()).isEqualTo(Verdict.ALIGNED);
    }

    @Test
    void aTrackWithNoLinesIsNoReference() {
        Result result = TimingFit.fit(forcedFrom(track(600, 7), 20, 0), List.of());

        assertThat(result.verdict()).isEqualTo(Verdict.NO_REFERENCE);
        assertThat(result.reasons()).containsExactly("The film's English track has no lines to compare with");
    }

    @Test
    void noReferenceCarriesTheReasonAndTheCueCount() {
        Result result = TimingFit.noReference(12, "The file has no subtitle track");

        assertThat(result).isEqualTo(new Result(Verdict.NO_REFERENCE, 0, 0.0, 0, 12, 0,
            List.of("The file has no subtitle track")));
    }

    @Test
    void aFileFarFromEveryCueOfTheTrackFitsNothing() {
        List<Long> forced = IntStream.range(0, 10).mapToObj(i -> 10_000_000L + i * 5_000L).toList();

        Result result = TimingFit.fit(forced, List.of(1_000L, 2_000L));

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.matchedCount()).isZero();
        assertThat(result.fittedOffsetMs()).isZero();
        assertThat(result.reasons()).containsExactly("Only 0 of 10 lines line up with the film's English track");
    }

    @Test
    void aWholeEditionFileFitsThePartsJoinedOnTheEditionTimeline() {
        // Part 1 and part 2 each hold a full track on their own timeline; part 2 starts one hour in.
        List<Long> partOne = track(300, 4);
        List<Long> partTwo = track(300, 6);
        List<Long> joined = new ArrayList<>(partOne);
        partTwo.forEach(start -> joined.add(start + HOUR));
        List<Long> forced = new ArrayList<>(forcedFrom(partOne, 30, 700));
        forced.addAll(forcedFrom(partTwo, 30, 700).stream().map(start -> start + HOUR).toList());

        Result result = TimingFit.fit(forced, joined);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.servedCount()).isEqualTo(20);
        assertThat(result.fittedOffsetMs()).isEqualTo(700);
    }

    @Test
    void aPerPartFileFitsItsOwnPartsTrackOnThePartsTimeline() {
        List<Long> partTwo = track(300, 6);

        Result result = TimingFit.fit(forcedFrom(partTwo, 25, -1_500), partTwo);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.fittedOffsetMs()).isEqualTo(-1_500);
    }

    @Test
    void candidatesAreTheMostFrequentDifferencesTiesBySmallerThenLowerOffset() {
        long[] served = {1_000};
        long[] reference = {1_100, 900};

        assertThat(TimingFit.candidates(served, reference)).containsExactly(-100L, 100L);
    }

    @Test
    void candidatesBeyondTheSearchRangeAreNotConsidered() {
        long[] served = {0};
        long[] reference = {TimingFit.SEARCH_MS + 1_000};

        assertThat(TimingFit.candidates(served, reference)).isEmpty();
    }

    @Test
    void anEvenNumberOfMatchesGivesTheMeanOfTheMiddleTwoResiduals() {
        List<Long> track = track(600, 13);
        // Residuals after the fit: 0 and 100 for two cues in eight, the rest exact: the median is still 0.
        List<Long> forced = new ArrayList<>(forcedFrom(track, 75, 0));
        forced.set(1, forced.get(1) + 100);

        Result result = TimingFit.fit(forced, track);

        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.matchedCount()).isEqualTo(8);
        assertThat(result.medianResidualMs()).isLessThan(60);
    }
}
