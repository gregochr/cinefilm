package gregochr.cinefilm.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.health.HealthDtos.HealthReport;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/** Every coordinate is made up, in the middle of an ocean. */
@ExtendWith(MockitoExtension.class)
class TrackPointServiceTest {

    private static final long JOURNEY = 1L;
    private static final Instant T0 = TestData.NOW;
    private static final long RUNTIME = 6_000_000L;
    private static final List<Long> GOOD_MINUTE = row(0, 900, 60_000, 0, 30);
    /** A playing minute with no fragment; with nothing else said it is bad. */
    private static final List<Long> NO_FRAGMENT = row(1, 0, 60_000, 0, 30);

    @Mock
    private JourneySampleRepository samples;
    @Mock
    private JourneyPlaybackRepository links;
    @Mock
    private PlaybackHealthRepository health;
    @Mock
    private EditionRepository editions;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private TrackPointService service;

    @BeforeEach
    void setUp() {
        service = new TrackPointService(samples, links, health, editions, mapper);
    }

    private static List<Long> row(long minute, long median, long moved, long stalled, long ahead) {
        return List.of(minute, median, 3L, moved, stalled, ahead, 3L);
    }

    private static JourneySample round(int roundNo, long offsetSeconds, Integer probeMs) {
        JourneySample s = new JourneySample();
        s.setJourneyId(JOURNEY);
        s.setRoundNo(roundNo);
        s.setSampledAt(T0.plusSeconds(offsetSeconds));
        s.setLatitude(0.5 + roundNo / 1000.0);
        s.setLongitude(-20.0 - roundNo / 1000.0);
        s.setProbeMs(probeMs);
        return s;
    }

    private static Edition edition(long id, long durationMs) {
        Edition edition = new Edition();
        edition.setId(id);
        edition.setRuntimeMs(durationMs);
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setKind(MediaKind.ORIGINAL);
        file.setDurationMs(durationMs);
        edition.getMediaFiles().add(file);
        return edition;
    }

    private HealthReport report(Long seekCount, Long endPositionMs, List<List<Long>> minutes) {
        return new HealthReport(1L, 5L, null, null, null, null, null, null, null, null, null, null, null, null,
            seekCount, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, minutes, null, null, endPositionMs);
    }

    private PlaybackHealth stored(long id, long editionId, Instant startedAt, HealthReport report) {
        return stored(id, editionId, startedAt, mapper.writeValueAsString(report));
    }

    private static PlaybackHealth stored(long id, long editionId, Instant startedAt, String summary) {
        PlaybackHealth h = new PlaybackHealth();
        h.setId(id);
        h.setEditionId(editionId);
        h.setStartedAt(startedAt);
        h.setSummary(summary);
        return h;
    }

    /** Stubs the journey's rounds and its linked playbacks, whose rows and editions are read in one query each. */
    private void world(List<JourneySample> rounds, List<PlaybackHealth> healths, List<Edition> eds) {
        List<Long> ids = healths.stream().map(PlaybackHealth::getId).toList();
        List<JourneyPlayback> linked = new ArrayList<>();
        for (Long id : ids) {
            JourneyPlayback link = new JourneyPlayback();
            link.setJourneyId(JOURNEY);
            link.setPlaybackHealthId(id);
            linked.add(link);
        }
        when(samples.findByJourneyIdOrderByRoundNo(JOURNEY)).thenReturn(rounds);
        when(links.findByJourneyId(JOURNEY)).thenReturn(linked);
        when(health.findAllById(ids)).thenReturn(healths);
        when(editions.findAllById(healths.stream().map(PlaybackHealth::getEditionId).distinct().toList()))
            .thenReturn(eds);
    }

    /** One decidable part on a 100 minute film, minutes 0 and 1 as given. */
    private PlaybackHealth decidable(List<Long> minuteOne, long endPositionMs) {
        return stored(10, 5, T0, report(0L, endPositionMs, List.of(GOOD_MINUTE, minuteOne)));
    }

    private PointState stateOfOnlyPoint(List<JourneySample> rounds, List<PlaybackHealth> healths,
                                        List<Edition> eds) {
        world(rounds, healths, eds);
        List<TrackPoint> points = service.pointsOf(JOURNEY);
        assertThat(points).hasSize(rounds.size());
        return points.get(0).state();
    }

    private PointState inMinuteOne(Integer probe, HealthReport part, long editionDuration) {
        return stateOfOnlyPoint(List.of(round(1, 90, probe)), List.of(stored(10, 5, T0, part)),
            List.of(edition(5, editionDuration)));
    }

    @Test
    void pointsCarryTheRoundsPositionCourseAndSpeedAndSecondsFromTheFirstPoint() {
        JourneySample unpositioned = round(1, 5, 10);
        unpositioned.setLatitude(null);
        JourneySample first = round(2, 30, 10);
        first.setCourseDeg(187.5);
        first.setSpeedMps(21.5);
        JourneySample second = round(3, 150, 10);
        world(List.of(unpositioned, first, second), List.of(), List.of());

        assertThat(service.pointsOf(JOURNEY)).containsExactly(
            new TrackPoint(0.5 + 2 / 1000.0, -20.0 - 2 / 1000.0, 187.5, 21.5, 0, PointState.GOOD),
            new TrackPoint(0.5 + 3 / 1000.0, -20.0 - 3 / 1000.0, null, null, 120, PointState.GOOD));
    }

    @Test
    void aRoundWithALongitudeButNoLatitudeOrTheOtherWayRoundIsNotAPoint() {
        JourneySample noLat = round(1, 0, 10);
        noLat.setLatitude(null);
        JourneySample noLon = round(2, 10, 10);
        noLon.setLongitude(null);
        world(List.of(noLat, noLon, round(3, 20, 10)), List.of(), List.of());

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::secondsFromStart).containsExactly(0L);
    }

    @Test
    void aJourneyWithNoPositionsHasNoPoints() {
        JourneySample noPosition = round(1, 0, 10);
        noPosition.setLatitude(null);
        noPosition.setLongitude(null);
        when(samples.findByJourneyIdOrderByRoundNo(JOURNEY)).thenReturn(List.of(noPosition));

        assertThat(service.pointsOf(JOURNEY)).isEmpty();
    }

    @Test
    void aJourneyWithNoRoundsHasNoPoints() {
        when(samples.findByJourneyIdOrderByRoundNo(JOURNEY)).thenReturn(List.of());

        assertThat(service.pointsOf(JOURNEY)).isEmpty();
    }

    @Test
    void theInternetCheckAloneDecidesAtOneSecond() {
        world(List.of(round(1, 0, 1_000), round(2, 10, 1_001), round(3, 20, 0)), List.of(), List.of());

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.GOOD, PointState.BAD, PointState.GOOD);
    }

    @Test
    void aMissingCheckIsBadWhereOtherRoundsCarryChecksAndUnknownWhereNoneDo() {
        world(List.of(round(1, 0, null), round(2, 10, 20)), List.of(), List.of());
        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.BAD, PointState.GOOD);

        world(List.of(round(1, 0, null), round(2, 10, null)), List.of(), List.of());
        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.UNKNOWN, PointState.UNKNOWN);
    }

    @Test
    void aCheckOnARoundWithNoPositionCountsAsAChecksElsewhere() {
        JourneySample unpositioned = round(1, 0, 20);
        unpositioned.setLatitude(null);
        world(List.of(unpositioned, round(2, 10, null)), List.of(), List.of());

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state).containsExactly(PointState.BAD);
    }

    @Test
    void aBadMinuteStandsInBothHalvesOfItAndItsOwnGoodNextMinuteIsGood() {
        List<Long> badMinute = row(1, 100, 60_000, 0, 30);
        PlaybackHealth part = stored(10, 5, T0, report(0L, 3_000_000L,
            List.of(GOOD_MINUTE, badMinute, row(2, 900, 60_000, 0, 30))));
        world(List.of(round(1, 70, 10), round(2, 100, 10), round(3, 119, 10), round(4, 120, 10)),
            List.of(part), List.of(edition(5, RUNTIME)));

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.BAD, PointState.BAD, PointState.BAD, PointState.GOOD);
    }

    @Test
    void aGoodMinuteStaysGoodWhenItsRoundHasASlowOrAMissingCheck() {
        PlaybackHealth part = stored(10, 5, T0, report(0L, 3_000_000L, List.of(GOOD_MINUTE)));
        world(List.of(round(1, 10, 5_000), round(2, 20, null), round(3, 30, 10)),
            List.of(part), List.of(edition(5, RUNTIME)));

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.GOOD, PointState.GOOD, PointState.GOOD);
    }

    @Test
    void aPlayingNoFragmentMinuteInADecidablePartWellBeforeTheFilmsEndIsBadWhateverItsCheck() {
        assertThat(inMinuteOne(10, report(0L, 3_000_000L, List.of(GOOD_MINUTE, NO_FRAGMENT)), RUNTIME))
            .isEqualTo(PointState.BAD);
    }

    @Test
    void aNoFragmentMinuteAtTheFilmsEndFallsToTheCheck() {
        HealthReport atEnd = report(0L, RUNTIME - 60_000, List.of(GOOD_MINUTE, row(1, 0, 60_000, 0, 0)));

        assertThat(inMinuteOne(10, atEnd, RUNTIME)).isEqualTo(PointState.GOOD);
        assertThat(inMinuteOne(null, atEnd, RUNTIME)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aNoFragmentMinuteJustShortOfTheFilmsEndIsBad() {
        HealthReport nearEnd = report(0L, RUNTIME - 60_001, List.of(GOOD_MINUTE, row(1, 0, 60_000, 0, 0)));

        assertThat(inMinuteOne(10, nearEnd, RUNTIME)).isEqualTo(PointState.BAD);
    }

    @Test
    void aSeekLeavesANoFragmentMinuteToTheCheck() {
        HealthReport seeked = report(1L, 3_000_000L, List.of(GOOD_MINUTE, NO_FRAGMENT));

        assertThat(inMinuteOne(10, seeked, RUNTIME)).isEqualTo(PointState.GOOD);
        assertThat(inMinuteOne(null, seeked, RUNTIME)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aReportWithNoSeekCountIsTakenToHaveSeeked() {
        HealthReport noCount = report(null, 3_000_000L, List.of(GOOD_MINUTE, NO_FRAGMENT));

        assertThat(inMinuteOne(10, noCount, RUNTIME)).isEqualTo(PointState.GOOD);
    }

    @Test
    void aMissingEndPositionLeavesANoFragmentMinuteToTheCheck() {
        HealthReport noEnd = report(0L, null, List.of(GOOD_MINUTE, NO_FRAGMENT));

        assertThat(inMinuteOne(10, noEnd, RUNTIME)).isEqualTo(PointState.GOOD);
        assertThat(inMinuteOne(null, noEnd, RUNTIME)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aNullKnownRuntimeLeavesANoFragmentMinuteToTheCheckAndNotToTheStoredRuntimeOrAPartCountedAsZero() {
        Edition unknownPart = new Edition();
        unknownPart.setId(5L);
        unknownPart.setRuntimeMs(7_200_000);
        MediaFile one = new MediaFile();
        one.setId(1L);
        one.setPartNumber(1);
        one.setKind(MediaKind.ORIGINAL);
        one.setDurationMs(7_200_000L);
        MediaFile two = new MediaFile();
        two.setId(2L);
        two.setPartNumber(2);
        two.setKind(MediaKind.ORIGINAL);
        unknownPart.getMediaFiles().add(one);
        unknownPart.getMediaFiles().add(two);
        HealthReport report = report(0L, 1_000_000L, List.of(GOOD_MINUTE, NO_FRAGMENT));
        world(List.of(round(1, 90, 10)), List.of(stored(10, 5, T0, report)), List.of(unknownPart));

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state).containsExactly(PointState.GOOD);

        world(List.of(round(1, 90, null), round(2, 100, null)), List.of(stored(10, 5, T0, report)),
            List.of(unknownPart));
        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.UNKNOWN, PointState.UNKNOWN);
    }

    @Test
    void aStoredRuntimeShorterThanThePartsSumIsNotUsed() {
        Edition edition = edition(5, RUNTIME);
        edition.setRuntimeMs(RUNTIME - 600_000);
        long between = RUNTIME - 300_000;
        HealthReport report = report(0L, between, List.of(GOOD_MINUTE, row(1, 0, 60_000, 0, 0)));

        assertThat(stateOfOnlyPoint(List.of(round(1, 90, 10)), List.of(stored(10, 5, T0, report)),
            List.of(edition))).isEqualTo(PointState.BAD);
    }

    @Test
    void whereTwoPartsRowsOverlapTheLaterStartedPartsRowIsUsed() {
        PlaybackHealth earlierBad = stored(10, 5, T0, report(0L, 3_000_000L, List.of(
            GOOD_MINUTE, row(1, 100, 60_000, 0, 30))));
        PlaybackHealth laterGood = stored(11, 5, T0.plusSeconds(30), report(0L, 3_000_000L, List.of(
            row(0, 900, 60_000, 0, 30), row(1, 900, 60_000, 0, 30))));
        PlaybackHealth earlierGood = stored(12, 5, T0, report(0L, 3_000_000L, List.of(
            GOOD_MINUTE, row(1, 900, 60_000, 0, 30))));
        PlaybackHealth laterBad = stored(13, 5, T0.plusSeconds(30), report(0L, 3_000_000L, List.of(
            row(0, 100, 60_000, 0, 30), row(1, 100, 60_000, 0, 30))));
        List<Edition> eds = List.of(edition(5, RUNTIME));

        assertThat(stateOfOnlyPoint(List.of(round(1, 70, 10)), List.of(earlierBad, laterGood), eds))
            .isEqualTo(PointState.GOOD);
        assertThat(stateOfOnlyPoint(List.of(round(1, 70, 10)), List.of(laterGood, earlierBad), eds))
            .isEqualTo(PointState.GOOD);
        assertThat(stateOfOnlyPoint(List.of(round(1, 70, 10)), List.of(earlierGood, laterBad), eds))
            .isEqualTo(PointState.BAD);
    }

    @Test
    void aMostlyPausedNoFragmentMinuteFallsToTheCheck() {
        List<Long> paused = row(1, 0, 20_000, 0, 30);

        assertThat(inMinuteOne(10, report(0L, 3_000_000L, List.of(GOOD_MINUTE, paused)), RUNTIME))
            .isEqualTo(PointState.GOOD);
        assertThat(inMinuteOne(1_001, report(0L, 3_000_000L, List.of(GOOD_MINUTE, paused)), RUNTIME))
            .isEqualTo(PointState.BAD);
    }

    @Test
    void aMostlyPausedNoFragmentMinuteWithAMissingCheckIsBadOrUnknownByTheRestOfTheJourney() {
        PlaybackHealth part = stored(10, 5, T0, report(0L, 3_000_000L, List.of(GOOD_MINUTE,
            row(1, 0, 20_000, 0, 30))));

        assertThat(stateOfOnlyPoint(List.of(round(1, 90, null), round(2, 100, 10)), List.of(part),
            List.of(edition(5, RUNTIME)))).isEqualTo(PointState.BAD);
        assertThat(stateOfOnlyPoint(List.of(round(1, 90, null), round(2, 100, null)), List.of(part),
            List.of(edition(5, RUNTIME)))).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aMinuteOfFortySecondsStalledAndTwentyMovedOutsideTheFirstMinuteIsBad() {
        HealthReport report = report(0L, 3_000_000L, List.of(GOOD_MINUTE, row(1, 900, 20_000, 40_000, 30)));

        assertThat(inMinuteOne(10, report, RUNTIME)).isEqualTo(PointState.BAD);
    }

    @Test
    void aMinuteOfFortySecondsStalledAndNoneMovedIsNotPlayingSoTheCheckDecides() {
        HealthReport report = report(0L, 3_000_000L, List.of(GOOD_MINUTE, row(1, 900, 0, 40_000, 30)));

        assertThat(inMinuteOne(10, report, RUNTIME)).isEqualTo(PointState.GOOD);
    }

    @Test
    void aStallInAPartsFirstMinuteFallsToTheCheck() {
        PlaybackHealth part = stored(10, 5, T0, report(0L, 3_000_000L, List.of(row(0, 900, 20_000, 40_000, 0))));

        assertThat(stateOfOnlyPoint(List.of(round(1, 30, 10)), List.of(part), List.of(edition(5, RUNTIME))))
            .isEqualTo(PointState.GOOD);
    }

    @Test
    void aTwoPartViewingUsesEachPartsOwnStartAndRows() {
        PlaybackHealth first = stored(10, 5, T0, report(0L, 3_000_000L, List.of(GOOD_MINUTE,
            row(1, 900, 60_000, 0, 30))));
        PlaybackHealth second = stored(11, 5, T0.plusSeconds(120), report(0L, 3_600_000L, List.of(
            row(0, 100, 60_000, 0, 30), row(1, 900, 60_000, 0, 30))));
        world(List.of(round(1, 30, 10), round(2, 100, 10), round(3, 150, 10), round(4, 200, 10)),
            List.of(first, second), List.of(edition(5, RUNTIME)));

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state)
            .containsExactly(PointState.GOOD, PointState.GOOD, PointState.BAD, PointState.GOOD);
    }

    @Test
    void aTimeInNoRowFallsToTheCheck() {
        PlaybackHealth part = stored(10, 5, T0, report(0L, 3_000_000L, List.of(GOOD_MINUTE)));

        assertThat(stateOfOnlyPoint(List.of(round(1, 600, 5_000)), List.of(part), List.of(edition(5, RUNTIME))))
            .isEqualTo(PointState.BAD);
    }

    @Test
    void aTimeBeforeThePartStartedFallsToTheCheck() {
        PlaybackHealth part = stored(10, 5, T0.plusSeconds(300), report(0L, 3_000_000L,
            List.of(row(0, 100, 60_000, 0, 30))));

        assertThat(stateOfOnlyPoint(List.of(round(1, 0, 10)), List.of(part), List.of(edition(5, RUNTIME))))
            .isEqualTo(PointState.GOOD);
    }

    @Test
    void aJourneyWhoseViewingIsGoneIsJudgedByTheChecksAlone() {
        JourneyPlayback link = new JourneyPlayback();
        link.setJourneyId(JOURNEY);
        link.setPlaybackHealthId(10L);
        when(samples.findByJourneyIdOrderByRoundNo(JOURNEY)).thenReturn(List.of(round(1, 90, 5_000)));
        when(links.findByJourneyId(JOURNEY)).thenReturn(List.of(link));
        when(health.findAllById(List.of(10L))).thenReturn(List.of());
        when(editions.findAllById(List.of())).thenReturn(List.of());

        assertThat(service.pointsOf(JOURNEY)).extracting(TrackPoint::state).containsExactly(PointState.BAD);
    }

    @Test
    void aViewingWhoseEditionIsGoneHasNoKnownRuntime() {
        PlaybackHealth part = decidable(NO_FRAGMENT, 3_000_000L);

        assertThat(stateOfOnlyPoint(List.of(round(1, 90, 10)), List.of(part), List.of())).isEqualTo(PointState.GOOD);
    }

    @Test
    void anUnreadableStoredReportAddsNoMinutes() {
        PlaybackHealth garbled = stored(10, 5, T0, "not json");

        assertThat(stateOfOnlyPoint(List.of(round(1, 90, 5_000)), List.of(garbled), List.of(edition(5, RUNTIME))))
            .isEqualTo(PointState.BAD);
    }

    @Test
    void aReportWithNoMinutesAddsNoMinutes() {
        PlaybackHealth part = stored(10, 5, T0, report(0L, 3_000_000L, null));

        assertThat(stateOfOnlyPoint(List.of(round(1, 90, 5_000)), List.of(part), List.of(edition(5, RUNTIME))))
            .isEqualTo(PointState.BAD);
    }
}
