package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.plex.PlexWatchMerge.Change;
import gregochr.cinefilm.plex.PlexWatchMerge.Cut;
import gregochr.cinefilm.plex.PlexWatchMerge.Existing;
import gregochr.cinefilm.plex.PlexWatchMerge.Plex;
import gregochr.cinefilm.plex.PlexWatchMerge.Result;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** How Plex's per-film watching becomes per-edition state. */
class PlexWatchMergeTest {

    private static final Instant VIEWED = NOW.minus(Duration.ofDays(10));
    private static final Instant UPDATED = NOW.minus(Duration.ofDays(5));
    private static final Cut CUT = new Cut(100 * MIN, null);

    private static Result merge(Existing existing, Plex plex, boolean carriesOffset) {
        return PlexWatchMerge.merge(existing, plex, carriesOffset, CUT, NOW);
    }

    @Test
    void aFilmPlexNeverPlayedWritesNothing() {
        Result result = merge(null, new Plex(0, 0, null, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.NONE);
        assertThat(result.watched()).isFalse();
        assertThat(result.positionMs()).isZero();
        assertThat(result.lastPlayedAt()).isNull();
    }

    @Test
    void aFilmPlexNeverPlayedLeavesAnExistingStateAsItIs() {
        Existing existing = new Existing(5 * MIN, false, null, VIEWED);

        Result result = merge(existing, new Plex(0, 0, null, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.NONE);
        assertThat(result.positionMs()).isEqualTo(5 * MIN);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED);
    }

    @Test
    void aViewMakesTheEditionWatchedAtTheLastViewedTimeWithPositionZero() {
        Result result = merge(null, new Plex(1, 0, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(VIEWED);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED);
        assertThat(result.positionMs()).isZero();
        assertThat(result.newlyWatched()).isTrue();
        assertThat(result.newResumePoint()).isFalse();
    }

    @Test
    void aViewCountOfManyIsStillJustWatched() {
        Result result = merge(null, new Plex(7, 0, VIEWED, UPDATED), false);

        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(VIEWED);
    }

    @Test
    void aMidFilmOffsetOnTheCarryingEditionIsAResumePointNotWatched() {
        Result result = merge(null, new Plex(0, 40 * MIN, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.watched()).isFalse();
        assertThat(result.watchedAt()).isNull();
        assertThat(result.positionMs()).isEqualTo(40 * MIN);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED);
        assertThat(result.newlyWatched()).isFalse();
        assertThat(result.newResumePoint()).isTrue();
    }

    @Test
    void anOffsetOnAnEditionThatDoesNotCarryItIsIgnored() {
        Result result = merge(null, new Plex(0, 40 * MIN, VIEWED, UPDATED), false);

        assertThat(result.change()).isEqualTo(Change.NONE);
    }

    @Test
    void onlyTheCarryingEditionGetsTheResumePointWhileEveryEditionGetsTheView() {
        Plex plex = new Plex(1, 40 * MIN, VIEWED, UPDATED);

        Result first = merge(null, plex, true);
        Result second = merge(null, plex, false);

        assertThat(first.positionMs()).isEqualTo(40 * MIN);
        assertThat(first.watched()).isTrue();
        assertThat(second.positionMs()).isZero();
        assertThat(second.watched()).isTrue();
    }

    @Test
    void anOffsetJustBelowTheWatchedThresholdIsAResumePoint() {
        Result result = merge(null, new Plex(0, 92 * MIN - 1, VIEWED, UPDATED), true);

        assertThat(result.watched()).isFalse();
        assertThat(result.positionMs()).isEqualTo(92 * MIN - 1);
    }

    @Test
    void anOffsetExactlyAtTheThresholdBecomesWatchedWithPositionZero() {
        Result result = merge(null, new Plex(0, 92 * MIN, VIEWED, UPDATED), true);

        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(VIEWED);
        assertThat(result.positionMs()).isZero();
        assertThat(result.newlyWatched()).isTrue();
        assertThat(result.newResumePoint()).isFalse();
    }

    @Test
    void aNearEndOffsetHonoursTheEditionsCreditsMarker() {
        Cut marked = new Cut(100 * MIN, 80 * MIN);

        Result below = PlexWatchMerge.merge(null, new Plex(0, 80 * MIN - 1, VIEWED, UPDATED), true, marked, NOW);
        Result at = PlexWatchMerge.merge(null, new Plex(0, 80 * MIN, VIEWED, UPDATED), true, marked, NOW);

        assertThat(below.watched()).isFalse();
        assertThat(at.watched()).isTrue();
    }

    @Test
    void anOffsetOnAWatchedFilmKeepsTheRewatchPositionUnlessItIsNearTheEnd() {
        Result rewatch = merge(null, new Plex(2, 10 * MIN, VIEWED, UPDATED), true);
        Result finished = merge(null, new Plex(2, 95 * MIN, VIEWED, UPDATED), true);

        assertThat(rewatch.watched()).isTrue();
        assertThat(rewatch.positionMs()).isEqualTo(10 * MIN);
        assertThat(finished.watched()).isTrue();
        assertThat(finished.positionMs()).isZero();
    }

    @Test
    void withNoLastViewedTimeThePlayTimeIsWhenPlexLastChangedTheFilm() {
        Result result = merge(null, new Plex(1, 0, null, UPDATED), true);

        assertThat(result.lastPlayedAt()).isEqualTo(UPDATED);
        assertThat(result.watchedAt()).isEqualTo(UPDATED);
    }

    @Test
    void withNeitherTimestampThePlayTimeIsTheImportTime() {
        Result result = merge(null, new Plex(0, 5 * MIN, null, null), true);

        assertThat(result.lastPlayedAt()).isEqualTo(NOW);
    }

    @Test
    void withNeitherTimestampAnEditionThatAlreadyHasAPlayTimeKeepsItSoARerunChangesNothing() {
        Existing existing = new Existing(5 * MIN, false, null, VIEWED);

        Result result = merge(existing, new Plex(0, 5 * MIN, null, null), true);

        assertThat(result.change()).isEqualTo(Change.UNCHANGED);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED);
    }

    @Test
    void withNeitherTimestampAnEditionWithNoPlayTimeGetsTheImportTime() {
        Existing existing = new Existing(0, false, null, null);

        Result result = merge(existing, new Plex(0, 5 * MIN, null, null), true);

        assertThat(result.lastPlayedAt()).isEqualTo(NOW);
        assertThat(result.change()).isEqualTo(Change.UPDATED);
    }

    @Test
    void anEditionAlreadyWatchedStaysWatchedAndKeepsItsWatchedTime() {
        Instant watchedAt = NOW.minus(Duration.ofDays(30));
        Existing existing = new Existing(0, true, watchedAt, NOW.minus(Duration.ofDays(30)));

        Result result = merge(existing, new Plex(1, 0, VIEWED, UPDATED), true);

        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(watchedAt);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED);
        assertThat(result.newlyWatched()).isFalse();
    }

    @Test
    void anEditionAlreadyWatchedIsNeverDowngradedByAPlexResumePoint() {
        Existing existing = new Existing(0, true, NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(40)));

        Result result = merge(existing, new Plex(0, 20 * MIN, VIEWED, UPDATED), true);

        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(NOW.minus(Duration.ofDays(40)));
        assertThat(result.positionMs()).isEqualTo(20 * MIN);
        assertThat(result.newlyWatched()).isFalse();
        assertThat(result.newResumePoint()).isTrue();
    }

    @Test
    void aWatchedEditionWithNoPlayTimeStaysWatchedAndTakesPlexsTime() {
        Existing existing = new Existing(0, true, null, null);

        Result result = merge(existing, new Plex(1, 0, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(VIEWED);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED);
        assertThat(result.newlyWatched()).isFalse();
    }

    @Test
    void aStateNewerThanPlexsIsKeptExactlyAsItIsEvenWhenPlexSaysWatched() {
        Existing existing = new Existing(12 * MIN, false, null, VIEWED.plusMillis(1));

        Result result = merge(existing, new Plex(1, 0, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.KEPT_NEWER);
        assertThat(result.watched()).isFalse();
        assertThat(result.positionMs()).isEqualTo(12 * MIN);
        assertThat(result.lastPlayedAt()).isEqualTo(VIEWED.plusMillis(1));
        assertThat(result.newlyWatched()).isFalse();
        assertThat(result.newResumePoint()).isFalse();
    }

    @Test
    void aWatchedStateNewerThanPlexsIsKeptWithItsWatchedTime() {
        Instant watchedAt = VIEWED.plusSeconds(60);
        Existing existing = new Existing(0, true, watchedAt, watchedAt);

        Result result = merge(existing, new Plex(1, 0, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.KEPT_NEWER);
        assertThat(result.watched()).isTrue();
        assertThat(result.watchedAt()).isEqualTo(watchedAt);
    }

    @Test
    void aStateExactlyAsOldAsPlexsIsNotNewerSoPlexApplies() {
        Existing existing = new Existing(0, false, null, VIEWED);

        Result result = merge(existing, new Plex(1, 0, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.watched()).isTrue();
    }

    @Test
    void aStateOlderThanPlexsIsOverwrittenByPlexsResumePoint() {
        Existing existing = new Existing(3 * MIN, false, null, VIEWED.minusMillis(1));

        Result result = merge(existing, new Plex(0, 30 * MIN, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.positionMs()).isEqualTo(30 * MIN);
        assertThat(result.newResumePoint()).isTrue();
    }

    @Test
    void anEditionWithNoPlayTimeIsOverwrittenByPlex() {
        Existing existing = new Existing(3 * MIN, false, null, null);

        Result result = merge(existing, new Plex(0, 30 * MIN, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.positionMs()).isEqualTo(30 * MIN);
    }

    @Test
    void runningTheSameMergeAgainChangesNothing() {
        Plex plex = new Plex(1, 30 * MIN, VIEWED, UPDATED);
        Result first = merge(null, plex, true);
        Existing stored = new Existing(first.positionMs(), first.watched(), first.watchedAt(),
            first.lastPlayedAt());

        Result second = merge(stored, plex, true);

        assertThat(second.change()).isEqualTo(Change.UNCHANGED);
        assertThat(second.newlyWatched()).isFalse();
        assertThat(second.newResumePoint()).isFalse();
        assertThat(second.watched()).isTrue();
        assertThat(second.positionMs()).isEqualTo(30 * MIN);
    }

    @Test
    void anUnchangedResumePointIsNotCountedAgain() {
        Existing existing = new Existing(30 * MIN, false, null, VIEWED);

        Result result = merge(existing, new Plex(0, 30 * MIN, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UNCHANGED);
        assertThat(result.newResumePoint()).isFalse();
    }

    @Test
    void aChangedResumePointIsCountedAgain() {
        Existing existing = new Existing(20 * MIN, false, null, VIEWED);

        Result result = merge(existing, new Plex(0, 30 * MIN, VIEWED, UPDATED), true);

        assertThat(result.change()).isEqualTo(Change.UPDATED);
        assertThat(result.newResumePoint()).isTrue();
    }

    @Test
    void anEditionWithNoRuntimeNeverBecomesWatchedFromAnOffset() {
        Result result = PlexWatchMerge.merge(null, new Plex(0, 500 * MIN, VIEWED, UPDATED), true, new Cut(0, null),
            NOW);

        assertThat(result.watched()).isFalse();
        assertThat(result.positionMs()).isEqualTo(500 * MIN);
    }
}
