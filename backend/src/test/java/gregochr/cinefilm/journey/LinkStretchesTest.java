package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.PointState.BAD;
import static gregochr.cinefilm.journey.PointState.GOOD;
import static gregochr.cinefilm.journey.PointState.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every position is made up: a line due north at 20.0 W from 0.5 N, measured in metres from its start. On a meridian
 * the distance between two points is exactly their difference in metres, so each boundary is hit exactly.
 */
class LinkStretchesTest {

    private static final double METRES_PER_DEGREE = 6_371_000 * Math.PI / 180;
    private static final double SPEED = 50.0;
    private static final double TOLERANCE = 1e-9;

    private static TrackPoint pt(double northM, long t, PointState state, Double speed) {
        return new TrackPoint(0.5 + northM / METRES_PER_DEGREE, -20.0, 0.0, speed, t, state);
    }

    private static TrackPoint pt(double northM, long t, PointState state) {
        return pt(northM, t, state, SPEED);
    }

    /** Points every 500 m from {@code startM}, {@code step} seconds apart, in the given states. */
    private static List<TrackPoint> line(double startM, long step, PointState... states) {
        List<TrackPoint> points = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            points.add(pt(startM + i * 500.0, i * step, states[i]));
        }
        return points;
    }

    private static List<TrackPoint> line(PointState... states) {
        return line(0, 10, states);
    }

    @SafeVarargs
    private static List<LinkMap.Track> build(List<TrackPoint>... tracks) {
        return LinkStretches.build(List.of(tracks));
    }

    private static void assertStretch(LinkMap.Stretch actual, long from, long to, double need) {
        assertThat(actual.from()).isEqualTo(from);
        assertThat(actual.to()).isEqualTo(to);
        assertThat(actual.need()).isCloseTo(need, within(TOLERANCE));
    }

    @Test
    void oneGoodPointInsideARunIsAllowedAndTwoEndTheRun() {
        List<LinkMap.Track> one = build(line(GOOD, BAD, GOOD, BAD, GOOD));
        List<LinkMap.Track> two = build(line(GOOD, BAD, GOOD, GOOD, BAD, GOOD));

        assertThat(one.get(0).stretches()).hasSize(1);
        assertStretch(one.get(0).stretches().get(0), 10, 30, 20 * 1.25 + 60);
        assertThat(two.get(0).stretches()).hasSize(2);
        assertStretch(two.get(0).stretches().get(0), 10, 10, 60);
        assertStretch(two.get(0).stretches().get(1), 40, 40, 60);
    }

    @Test
    void oneUnknownPointInsideARunIsAllowedAndAnUnknownThenAGoodPointEndIt() {
        List<LinkMap.Track> one = build(line(GOOD, BAD, UNKNOWN, BAD, GOOD));
        List<LinkMap.Track> two = build(line(GOOD, BAD, UNKNOWN, GOOD, BAD, GOOD));

        assertThat(one.get(0).stretches()).hasSize(1);
        assertThat(two.get(0).stretches()).hasSize(2);
    }

    @Test
    void aGapTooLongForASegmentEndsARunAndCountsAsLeavingTheExtent() {
        List<TrackPoint> track = List.of(pt(0, 0, GOOD), pt(500, 10, BAD), pt(1000, 80, BAD), pt(1500, 90, GOOD));

        List<LinkMap.Stretch> stretches = build(track).get(0).stretches();

        assertThat(stretches).hasSize(2);
        assertStretch(stretches.get(0), 10, 10, 60);
        assertStretch(stretches.get(1), 80, 80, 60);
    }

    @Test
    void aStandstillLongerThanASegmentMayJoinSplitsTheTrackOnTheExtentIntoTwoStretches() {
        List<TrackPoint> track = List.of(pt(0, 0, GOOD), pt(500, 10, BAD), pt(500, 100, BAD), pt(1000, 110, GOOD));

        List<LinkMap.Stretch> stretches = build(track).get(0).stretches();

        assertThat(stretches).hasSize(2);
        assertStretch(stretches.get(0), 10, 10, 60);
        assertStretch(stretches.get(1), 100, 100, 60);
    }

    @Test
    void aTrackWithNoBadPointCarriesNoStretchAndTheMapKeepsItsPointsAndNullCourse() {
        TrackPoint first = new TrackPoint(0.5, -20.0, null, null, 0, GOOD);
        TrackPoint second = new TrackPoint(0.501, -20.0, 12.5, 30.0, 40, UNKNOWN);

        LinkMap.Track track = LinkStretches.build(List.<List<TrackPoint>>of(List.of(first, second))).get(0);

        assertThat(track.stretches()).isEmpty();
        assertThat(track.points()).containsExactly(new LinkMap.Point(0.5, -20.0, null, 0),
            new LinkMap.Point(0.501, -20.0, 12.5, 40));
    }

    @Test
    void aTrackOfUnderTwoPointsIsDropped() {
        List<LinkMap.Track> map = build(List.of(pt(0, 0, BAD)), List.of(), line(GOOD, BAD, GOOD));

        assertThat(map).hasSize(1);
        assertThat(map.get(0).points()).hasSize(3);
    }

    @Test
    void noTracksIsAnEmptyMap() {
        assertThat(LinkStretches.build(List.of())).isEmpty();
    }

    @Test
    void runsExactly300MetresApartAreOneStretchAndJustOverAreTwo() {
        List<TrackPoint> a = line(GOOD, BAD, BAD, GOOD);
        List<TrackPoint> atTheLimit = List.of(pt(800, 0, GOOD, 25.0), pt(1300, 10, BAD, 25.0),
            pt(1800, 20, GOOD, 25.0));
        List<TrackPoint> justOver = List.of(pt(800, 0, GOOD, 25.0), pt(1300.01, 10, BAD, 25.0),
            pt(1800, 20, GOOD, 25.0));

        List<LinkMap.Track> merged = build(a, atTheLimit);
        List<LinkMap.Track> apart = build(a, justOver);

        // Merged: neither track went through the whole extent (500 m to 1,500 m of a, 1,300 of b), so the longest
        // path inside it (a's 1,000 m) at the slowest moving speed inside it (25) gives 40 s: 110.
        assertThat(merged.get(0).stretches()).hasSize(1);
        assertStretch(merged.get(0).stretches().get(0), 10, 30, 110);
        assertThat(merged.get(1).stretches()).hasSize(1);
        assertStretch(merged.get(1).stretches().get(0), 0, 10, 110);
        // Apart: a's own 10 s through its own run, and the other run's extent on each track in its own seconds.
        assertThat(apart.get(0).stretches()).hasSize(2);
        assertStretch(apart.get(0).stretches().get(0), 10, 20, 72.5);
        assertStretch(apart.get(0).stretches().get(1), 30, 30, 60);
        assertThat(apart.get(1).stretches()).hasSize(2);
        assertStretch(apart.get(1).stretches().get(0), 0, 0, 72.5);
        assertStretch(apart.get(1).stretches().get(1), 10, 10, 60);
    }

    @Test
    void aShortRunInTheMiddleOfALongOneIsMergedWithItWhicheverIsFirst() {
        List<TrackPoint> longRun = List.of(pt(-500, 0, GOOD), pt(0, 10, BAD), pt(1000, 20, BAD), pt(1500, 30, GOOD));
        List<TrackPoint> inTheMiddle = List.of(pt(200, 0, GOOD), pt(500, 10, BAD), pt(800, 20, GOOD));

        List<LinkMap.Track> map = build(longRun, inTheMiddle);

        assertStretch(map.get(0).stretches().get(0), 10, 20, 72.5);
        assertStretch(map.get(1).stretches().get(0), 0, 20, 72.5);
    }

    @Test
    void aTrackThatDoublesBackCarriesTheStretchAtBothPassages() {
        List<TrackPoint> doubledBack = List.of(pt(0, 0, GOOD), pt(500, 10, BAD), pt(1000, 20, GOOD),
            pt(1500, 30, GOOD), pt(1000, 40, GOOD), pt(500, 50, BAD), pt(0, 60, GOOD));

        List<LinkMap.Stretch> stretches = build(doubledBack).get(0).stretches();

        assertThat(stretches).hasSize(2);
        assertStretch(stretches.get(0), 10, 10, 60);
        assertStretch(stretches.get(1), 50, 50, 60);
    }

    @Test
    void aChainOfOverlapsMergesEvenWhenTheEndsAreFarApart() {
        List<TrackPoint> a = List.of(pt(-1000, 0, GOOD), pt(0, 10, BAD), pt(1000, 20, GOOD));
        List<TrackPoint> b = List.of(pt(-750, 0, GOOD), pt(250, 10, BAD), pt(1250, 20, GOOD));
        List<TrackPoint> c = List.of(pt(-500, 0, GOOD), pt(500, 10, BAD), pt(1500, 20, GOOD));
        List<TrackPoint> d = List.of(pt(-400, 0, GOOD), pt(0, 50, GOOD), pt(250, 100, GOOD), pt(500, 150, GOOD),
            pt(900, 200, GOOD));

        List<LinkMap.Track> map = build(a, b, c, d);

        // The slow good track D went through all of 0 m to 500 m in 100 s: 100 x 1.25 + 60 on every track.
        assertStretch(map.get(0).stretches().get(0), 10, 10, 185);
        assertStretch(map.get(1).stretches().get(0), 10, 10, 185);
        assertStretch(map.get(2).stretches().get(0), 10, 10, 185);
        assertStretch(map.get(3).stretches().get(0), 50, 150, 185);
    }

    @Test
    void aTrackThatStartsInsideTheExtentIsNotCounted() {
        List<TrackPoint> through = line(GOOD, BAD, BAD, BAD, GOOD);
        List<TrackPoint> slowStart = line(500, 50, BAD, BAD, BAD, GOOD);

        List<LinkMap.Track> map = build(through, slowStart);

        assertStretch(map.get(0).stretches().get(0), 10, 30, 85);
        assertStretch(map.get(1).stretches().get(0), 0, 100, 85);
    }

    @Test
    void aTrackThatEndsInsideTheExtentIsNotCounted() {
        List<TrackPoint> through = line(GOOD, BAD, BAD, BAD, GOOD);
        List<TrackPoint> slowEnd = List.of(pt(2000, 0, GOOD), pt(1500, 50, BAD), pt(1000, 100, BAD),
            pt(500, 150, BAD));

        List<LinkMap.Track> map = build(through, slowEnd);

        assertStretch(map.get(0).stretches().get(0), 10, 30, 85);
        assertStretch(map.get(1).stretches().get(0), 50, 150, 85);
    }

    @Test
    void aTrackThatLeavesTheLineHalfWayDidNotGoThroughTheWholeExtent() {
        List<TrackPoint> through = line(GOOD, BAD, BAD, BAD, GOOD);
        List<TrackPoint> leaves = List.of(pt(0, 0, GOOD), pt(500, 40, BAD),
            new TrackPoint(0.5 + 1000 / METRES_PER_DEGREE, -19.9, 0.0, SPEED, 80, BAD));

        List<LinkMap.Track> map = build(through, leaves);

        // The other run left the line for 11 km: the first track did not go through all of it, so the longest path in
        // it (that diagonal, flat around its far end) at 50 m/s gives the duration.
        double lat = 0.5 + 1000 / METRES_PER_DEGREE;
        double east = Math.toRadians(0.1) * 6_371_000 * Math.cos(Math.toRadians(lat));
        double diagonal = Math.hypot(east, 500);
        assertStretch(map.get(0).stretches().get(0), 10, 30, diagonal / 50 * 1.25 + 60);
    }

    @Test
    void eachTrackCarriesTheMergedStretchInItsOwnSecondsEvenWhenReadBackwards() {
        List<TrackPoint> forward = line(GOOD, BAD, BAD, BAD, GOOD);
        List<TrackPoint> backwards = List.of(pt(2000, 0, GOOD), pt(1500, 15, BAD), pt(1000, 30, BAD),
            pt(500, 45, BAD), pt(0, 60, GOOD));
        List<TrackPoint> goodThroughout = List.of(pt(-500, 0, GOOD), pt(500, 25, GOOD), pt(1500, 55, GOOD),
            pt(2500, 80, GOOD));

        List<LinkMap.Track> map = build(forward, backwards, goodThroughout);

        // The backwards track took 30 s through it, the longest of the three: 30 x 1.25 + 60.
        assertStretch(map.get(0).stretches().get(0), 10, 30, 97.5);
        assertStretch(map.get(1).stretches().get(0), 15, 45, 97.5);
        assertStretch(map.get(2).stretches().get(0), 25, 55, 97.5);
    }

    @Test
    void theNeedIsTheLongestThroughTimeTimes125PlusSixtyAtExactValues() {
        assertStretch(build(line(0, 10, GOOD, BAD, BAD, GOOD)).get(0).stretches().get(0), 10, 20, 72.5);
        assertStretch(build(line(0, 40, GOOD, BAD, BAD, BAD, GOOD)).get(0).stretches().get(0), 40, 120, 160);
        assertStretch(build(line(0, 30, GOOD, BAD, GOOD)).get(0).stretches().get(0), 30, 30, 60);
    }

    @Test
    void withNoTrackThroughItTheLengthAtTheSlowestMovingSpeedIsUsedAndFiveMetresASecondCounts() {
        List<TrackPoint> startsInside = List.of(pt(0, 0, BAD, 40.0), pt(500, 10, BAD, 5.0), pt(1000, 20, BAD, 40.0),
            pt(1500, 30, GOOD, 40.0));

        // 1,000 m at 5 m/s is 200 s.
        assertStretch(build(startsInside).get(0).stretches().get(0), 0, 20, 310);
    }

    @Test
    void aSpeedBelowFiveIsNotMovingAndZeroOrMissingSpeedsAreSkipped() {
        List<TrackPoint> slowBit = List.of(pt(0, 0, BAD, 40.0), pt(500, 10, BAD, 4.99), pt(1000, 20, BAD, 0.0),
            pt(1250, 25, BAD, null), pt(1500, 30, BAD, 40.0), pt(2000, 40, GOOD, 40.0));

        // 1,500 m at the slowest speed that counts, 40 m/s: 37.5 s.
        assertStretch(build(slowBit).get(0).stretches().get(0), 0, 30, 37.5 * 1.25 + 60);
    }

    @Test
    void withNoMovingSampleInsideTheStretchIsLeftOut() {
        List<TrackPoint> allBelow = List.of(pt(0, 0, BAD, 4.99), pt(500, 10, BAD, 0.0), pt(1000, 20, BAD, null),
            pt(1500, 30, GOOD, 40.0));

        LinkMap.Track track = build(allBelow).get(0);

        assertThat(track.stretches()).isEmpty();
        assertThat(track.points()).hasSize(4);
    }

    @Test
    void aMovingSampleOutsideTheExtentDoesNotSaveIt() {
        List<TrackPoint> fastOnlyOutside = List.of(pt(0, 0, BAD, 1.0), pt(500, 10, BAD, 1.0), pt(2500, 20, GOOD, 40.0),
            pt(3000, 30, GOOD, 40.0));

        assertThat(build(fastOnlyOutside).get(0).stretches()).isEmpty();
    }

    @Test
    void aPathOfNoLengthTakesNoTimeSoTheNeedIsTheSixtySecondsAlone() {
        List<TrackPoint> starts = List.of(pt(0, 0, BAD, 40.0), pt(500, 10, GOOD, 40.0));
        List<TrackPoint> identical = List.of(pt(0, 0, BAD, 40.0), pt(0, 10, BAD, 40.0), pt(0, 20, GOOD, 40.0));

        // A path of no length at 40 m/s takes no time: the need is the sixty seconds alone.
        assertStretch(build(starts).get(0).stretches().get(0), 0, 0, 60);
        assertStretch(build(identical).get(0).stretches().get(0), 0, 20, 60);
    }
}
