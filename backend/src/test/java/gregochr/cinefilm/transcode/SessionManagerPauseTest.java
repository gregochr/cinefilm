package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.FileStamps.Stamp;
import gregochr.cinefilm.transcode.SessionFakes.FakeFiles;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import gregochr.cinefilm.transcode.SessionFakes.FakeStamps;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Sessions that went quiet are remembered and rebuilt under the same id, against the fakes of
 * {@link SessionManagerTest} (100 segments of 4 s, a session expires after 10 minutes, its encoder stops after 2).
 * Every ending that must stay final is shown to answer 404 and to start no encoder.
 */
class SessionManagerPauseTest {

    private static final Path HLS = Path.of("/cache/hls");
    private static final Path SOURCE = Path.of("/library/Heat.m4v");
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), 400_000);
    private static final List<Rung> RUNGS = Ladder.rungs(1080, 24_000_000, true, EncoderMode.VAAPI).subList(0, 2);
    private static final Duration IDLE = Duration.ofMinutes(2);
    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration ONE_MS = Duration.ofMillis(1);
    private static final String UNREADABLE = "The film's file cannot be read right now";

    private final FakeTranscoder transcoder = new FakeTranscoder();
    private final FakeFiles files = new FakeFiles();
    private final ScriptedWaiter waiter = new ScriptedWaiter();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final MutableClock clock = new MutableClock();
    private final FakeStamps stamps = new FakeStamps();
    private int counter;
    private final SessionManager manager = new SessionManager(transcoder, files, waiter, scheduler, clock,
        new SessionSettings(IDLE, EXPIRY, 1000, 2, "/dev/dri/renderD129"), stamps, HLS,
        () -> new UUID(0, ++counter));

    private static String id(int n) {
        return new UUID(0, n).toString();
    }

    private static SessionSpec spec(long edition) {
        return new SessionSpec(edition, "Heat", SOURCE, 1, CUTS, 25.0, 1920, 1080, RUNGS, RUNGS.get(0), null, 0,
            true);
    }

    private static Path seg(int session, String rung, int n) {
        return HLS.resolve(id(session)).resolve(rung).resolve("seg-" + n + ".ts");
    }

    private void write(int session, String rung, int from, int to) {
        for (int n = from; n <= to; n++) {
            files.add(seg(session, rung, n));
        }
    }

    private String create(long edition) {
        return manager.create(spec(edition)).id();
    }

    /** Ten quiet minutes and a sweep: every session idle that long is deleted (and remembered). */
    private void expire() {
        clock.advance(EXPIRY);
        manager.sweep();
    }

    private SessionDtos.SessionRow row() {
        return manager.sessions().get(0);
    }

    private int stops() {
        return transcoder.encoders.stream().mapToInt(e -> e.stops).sum();
    }

    private List<String> liveIds() {
        return manager.sessions().stream().map(SessionDtos.SessionRow::id).toList();
    }

    /** Asks for the session both ways and sees a plain 404 and no encoder started. */
    private void assertFinal(String id) {
        int plans = transcoder.plans.size();
        assertThatThrownBy(() -> manager.view(id)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
        assertThatThrownBy(() -> manager.segment(id, "original", 0)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
        assertThat(transcoder.plans).hasSize(plans);
    }

    /** A session quiet for ten minutes and then rebuilt by a playlist request: live, with no encoder started. */
    private String rebuiltWithoutAnEncoder() {
        String id = create(7);
        expire();
        manager.view(id);
        assertThat(stops()).isEqualTo(1);
        return id;
    }

    private static <T> T onAnotherThread(Supplier<T> call) {
        try {
            return CompletableFuture.supplyAsync(call).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("the racing call did not complete: it was blocked or it failed", e);
        }
    }

    @Test
    void anIdleExpiredSessionIsRebuiltUnderTheSameIdAndASegmentRequestStartsOneRunThere() {
        String id = create(7);
        clock.advance(Duration.ofMinutes(1));
        manager.view(id);
        expire();
        assertThat(manager.sessions()).isEmpty();
        waiter.whileWaiting = () -> write(1, "original", 40, 41);

        Path file = manager.segment(id, "original", 40);

        assertThat(file).isEqualTo(seg(1, "original", 40));
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(40);
        assertThat(transcoder.lastPlan().outputDir()).isEqualTo(HLS.resolve(id(1)));
        assertThat(transcoder.lastPlan().source()).isEqualTo(SOURCE);
        assertThat(waiter.timeouts).containsExactly(Duration.ofSeconds(6));
        assertThat(row()).isEqualTo(new SessionDtos.SessionRow(id(1), "Heat", 7, "original", null, 1, TestData.NOW,
            false));
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)), HLS.resolve(id(1)));
    }

    @Test
    void aRebuiltSessionWhoseEncoderMakesNothingIn6sAnswers503AndTheEncoderKeepsGoing() {
        String id = create(7);
        expire();

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class)
            .hasMessage("The segment is not ready yet");

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.encoders.get(1).stops).isZero();
        assertThat(liveIds()).containsExactly(id(1));
    }

    @Test
    void theRebuiltSessionDescribesItselfExactlyAsTheOriginalDid() {
        SessionView original = manager.create(spec(7));
        expire();

        assertThat(manager.view(original.id())).isEqualTo(original);
    }

    @Test
    void aPlaylistRequestRebuildsWithoutStartingAnEncoderAndKeepsTheStartTimeAndLastRung() {
        String id = create(7);
        write(1, "720p", 0, 3);
        manager.segment(id, "720p", 0);
        clock.advance(Duration.ofMinutes(1));
        expire();

        manager.view(id);

        assertThat(transcoder.plans).hasSize(1);
        assertThat(row()).isEqualTo(new SessionDtos.SessionRow(id(1), "Heat", 7, "720p", null, 0, TestData.NOW,
            false));

        assertThatThrownBy(() -> manager.segment(id, "original", 3)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(3);
        assertThat(row().rung()).isEqualTo("original");
    }

    @Test
    void sweepingARebuiltSessionWithNoEncoderStopsNothingAndThrowsNothing() {
        String id = rebuiltWithoutAnEncoder();
        clock.advance(IDLE);

        manager.sweep();

        assertThat(liveIds()).containsExactly(id);
        assertThat(stops()).isEqualTo(1);
    }

    @Test
    void endingARebuiltSessionWithNoEncoderStopsNothingAndLeavesItGone() {
        String id = rebuiltWithoutAnEncoder();

        manager.end(id);

        assertThat(manager.sessions()).isEmpty();
        assertThat(stops()).isEqualTo(1);
        assertFinal(id);
    }

    @Test
    void shuttingDownWithARebuiltSessionWithNoEncoderStopsNothing() {
        rebuiltWithoutAnEncoder();

        manager.shutdown();

        assertThat(stops()).isEqualTo(1);
    }

    @Test
    void aRebuiltSessionWithNoEncoderPushedOutAtTheCapStopsNothing() {
        String id = rebuiltWithoutAnEncoder();
        clock.advance(Duration.ofSeconds(10));
        create(8);
        clock.advance(Duration.ofSeconds(10));
        create(9);
        clock.advance(Duration.ofSeconds(10));
        create(10);

        assertThat(liveIds()).containsExactly(id(2), id(3), id(4));
        assertThat(stops()).isEqualTo(1);
        assertFinal(id);
    }

    @Test
    void theRebuildCountsAsARequestSoTheNextSweepKeepsItAndTenQuietMinutesLaterItExpiresAndIsRebuiltAgain() {
        String id = create(7);
        expire();
        manager.view(id);

        clock.advance(EXPIRY.minus(ONE_MS));
        manager.sweep();
        assertThat(liveIds()).containsExactly(id(1));

        clock.advance(ONE_MS);
        manager.sweep();
        assertThat(manager.sessions()).isEmpty();

        assertThat(manager.view(id).id()).isEqualTo(id(1));
        assertThat(liveIds()).containsExactly(id(1));
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aSessionIsRebuiltOneMillisecondBeforeADayAfterItsLastRequest() {
        String id = create(7);
        expire();
        clock.advance(DAY.minus(EXPIRY).minus(ONE_MS));

        assertThat(manager.view(id).id()).isEqualTo(id(1));

        assertThat(liveIds()).containsExactly(id(1));
    }

    @Test
    void aSessionIsNotRebuiltExactlyADayAfterItsLastRequestAndStartsNoEncoder() {
        String id = create(7);
        expire();
        clock.advance(DAY.minus(EXPIRY));

        assertFinal(id);

        assertThat(transcoder.plans).hasSize(1);
        assertThat(stamps.reads).hasSize(1);
    }

    @Test
    void aReadThatCrossesTheDayMakesTheRebuildA404AndForgetsTheSession() {
        String id = create(7);
        expire();
        clock.advance(DAY.minus(EXPIRY).minus(ONE_MS));
        stamps.hook = () -> clock.advance(ONE_MS);

        assertThatThrownBy(() -> manager.view(id)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");

        assertThat(manager.sessions()).isEmpty();
        assertThat(transcoder.plans).hasSize(1);
        assertFinal(id);
    }

    @Test
    void withEightRememberedTheEldestIsStillRebuilt() {
        for (int k = 1; k <= 8; k++) {
            create(k);
            expire();
        }

        assertThat(manager.view(id(1)).id()).isEqualTo(id(1));
        assertThat(manager.view(id(8)).id()).isEqualTo(id(8));
    }

    @Test
    void withNineRememberedTheEldestIsGoneAndTheSecondAndTheNinthAreRebuilt() {
        for (int k = 1; k <= 9; k++) {
            create(k);
            expire();
        }

        assertFinal(id(1));
        assertThat(manager.view(id(2)).id()).isEqualTo(id(2));
        assertThat(manager.view(id(9)).id()).isEqualTo(id(9));
        assertThat(liveIds()).containsExactly(id(2), id(9));
    }

    @Test
    void aSessionForgottenForBeingADayOldFreesItsPlaceAmongTheEight() {
        create(1);
        clock.advance(Duration.ofMinutes(1));
        create(2);
        clock.advance(Duration.ofMinutes(99));
        manager.view(id(1));
        expire(); // 1 is remembered first although 2 was last asked for earlier
        for (int k = 3; k <= 8; k++) {
            create(k);
            expire();
        }
        Instant secondIsADayOld = TestData.NOW.plus(Duration.ofMinutes(1)).plus(DAY);
        clock.advance(Duration.between(clock.instant(), secondIsADayOld));
        assertFinal(id(2));

        create(9);
        expire();

        assertThat(manager.view(id(1)).id()).isEqualTo(id(1));
    }

    @Test
    void aSessionEvictedWhileIdleToBringHlsUnderItsCapIsRebuilt() {
        String id = create(7);
        clock.advance(IDLE);
        files.sizes.put(HLS, 1500L);
        files.sizes.put(HLS.resolve(id(1)), 600L);

        manager.sweep();

        assertThat(manager.sessions()).isEmpty();
        assertThat(manager.view(id).id()).isEqualTo(id(1));
        assertThat(liveIds()).containsExactly(id(1));
    }

    @Test
    void aSessionEndedWhileLiveIsFinal() {
        String id = create(7);

        manager.end(id);

        assertFinal(id);
    }

    @Test
    void aSessionEndedWhileRememberedIsFinalAndEndStaysIdempotent() {
        String id = create(7);
        expire();

        manager.end(id);
        manager.end(id);

        assertFinal(id);
        assertThat(stamps.reads).hasSize(1);
    }

    @Test
    void aSessionReplacedWhileLiveIsFinal() {
        String id = create(7);

        create(7);

        assertFinal(id);
    }

    @Test
    void aNewSessionForTheSameEditionForgetsARememberedOneAndKeepsAnotherEditions() {
        String first = create(7);
        String other = create(8);
        expire();

        String third = create(7);

        assertThat(third).isEqualTo(id(3));
        assertFinal(first);
        assertThat(manager.view(other).id()).isEqualTo(id(2));
        assertThat(liveIds()).containsExactly(id(3), id(2));
    }

    @Test
    void aSessionPushedOutByAFourthSessionIsFinal() {
        String first = create(1);
        clock.advance(Duration.ofSeconds(10));
        create(2);
        clock.advance(Duration.ofSeconds(10));
        create(3);
        clock.advance(Duration.ofSeconds(10));

        create(4);

        assertFinal(first);
        assertThat(liveIds()).containsExactly(id(2), id(3), id(4));
    }

    @Test
    void aSessionPushedOutByARebuildAtTheCapIsFinalAndTheRebuiltOneIsLive() {
        String first = create(1);
        expire();
        String second = create(2);
        clock.advance(Duration.ofSeconds(10));
        create(3);
        clock.advance(Duration.ofSeconds(10));
        create(4);

        assertThat(manager.view(first).id()).isEqualTo(id(1));

        assertThat(liveIds()).containsExactly(id(3), id(4), id(1));
        assertFinal(second);
    }

    @Test
    void aSessionThatWasUnavailableWhenItWentQuietIsNeverRebuilt() {
        String id = create(7);
        transcoder.lastListener().onExit(1, "one");
        transcoder.lastListener().onExit(1, "two");
        assertThat(row().unavailable()).isTrue();
        expire();

        assertFinal(id);
    }

    @Test
    void aSessionWhoseFileCouldNotBeReadWhenItWasMadeIsNeverRebuilt() {
        stamps.unreadable.add(SOURCE);
        String id = create(7);
        stamps.unreadable.clear();
        expire();

        assertFinal(id);
    }

    @Test
    void aSessionIsFinalAfterShutdown() {
        String id = create(7);
        expire();

        manager.shutdown();

        assertFinal(id);
    }

    @Test
    void aRebuiltSessionPushedOutByThreeNewOnesIsFinal() {
        String id = create(7);
        expire();
        manager.view(id);
        clock.advance(Duration.ofSeconds(10));
        create(8);
        clock.advance(Duration.ofSeconds(10));
        create(9);
        clock.advance(Duration.ofSeconds(10));
        create(10);

        assertFinal(id);
    }

    @Test
    void aRebuiltSessionLaterReplacedByANewOneForTheEditionIsFinal() {
        String id = create(7);
        expire();
        manager.view(id);

        create(7);

        assertFinal(id);
    }

    @Test
    void aChangedSizeIs404ForgetsTheSessionAndRestoringTheStampDoesNotBringItBack() {
        String id = create(7);
        expire();
        stamps.overrides.put(SOURCE, new Stamp(1001, 5000));

        assertFinal(id);

        stamps.overrides.clear();
        assertFinal(id);
    }

    @Test
    void aChangedModificationTimeIs404ForgetsTheSessionAndRestoringTheStampDoesNotBringItBack() {
        String id = create(7);
        expire();
        stamps.overrides.put(SOURCE, new Stamp(1000, 5001));

        assertFinal(id);

        stamps.overrides.clear();
        assertFinal(id);
    }

    @Test
    void aMissingFileIs404ForgetsTheSessionAndTheFileComingBackDoesNotBringItBack() {
        String id = create(7);
        expire();
        stamps.missing.add(SOURCE);

        assertFinal(id);

        stamps.missing.clear();
        assertFinal(id);
    }

    @Test
    void aFileThatCannotBeReadJustNowIs503KeepsTheSessionRememberedAndIsRebuiltOnceItReads() {
        String id = create(7);
        expire();
        stamps.unreadable.add(SOURCE);

        assertThatThrownBy(() -> manager.view(id)).isInstanceOf(SegmentNotReadyException.class)
            .hasMessage(UNREADABLE);
        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class)
            .hasMessage(UNREADABLE);

        assertThat(manager.sessions()).isEmpty();
        assertThat(transcoder.plans).hasSize(1);
        assertThat(waiter.timeouts).isEmpty();
        stamps.unreadable.clear();
        assertThat(manager.view(id).id()).isEqualTo(id(1));
        assertThat(liveIds()).containsExactly(id(1));
    }

    @Test
    void aRebuildStartsFromAnEmptyFolderSoLeftoverSegmentsAreNotServed() {
        String id = create(7);
        expire();
        write(1, "original", 40, 45);

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(files.existing).isEmpty();
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(40);
    }

    @Test
    void aRebuiltSessionWhoseFirstStartFailsIs404EndsForGoodAndIsNotRememberedAgain() {
        String id = create(7);
        expire();
        transcoder.failure = new IOException("no ffmpeg");

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");

        assertThat(manager.sessions()).isEmpty();
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)), HLS.resolve(id(1)), HLS.resolve(id(1)));
        transcoder.failure = null;
        assertFinal(id);
    }

    @Test
    void aRebuiltSessionWhoseLaterRestartFailsIsUnavailableAsALiveOneIs() {
        String id = create(7);
        expire();
        waiter.whileWaiting = () -> write(1, "original", 40, 41);
        manager.segment(id, "original", 40);
        transcoder.failure = new IOException("gone");

        assertThatThrownBy(() -> manager.segment(id, "original", 80)).isInstanceOf(SessionUnavailableException.class)
            .hasMessage("Transcoding failed for this session");

        assertThat(row().unavailable()).isTrue();
        assertThat(liveIds()).containsExactly(id(1));
    }

    @Test
    void aRebuiltSessionHasAFreshCrashBudget() {
        String id = create(7);
        write(1, "original", 0, 3);
        transcoder.lastListener().onExit(1, "first");
        assertThat(transcoder.plans).hasSize(2);
        expire();
        waiter.whileWaiting = () -> write(1, "original", 40, 41);
        manager.segment(id, "original", 40);
        assertThat(transcoder.plans).hasSize(3);

        transcoder.lastListener().onExit(1, "after the rebuild");

        assertThat(transcoder.plans).hasSize(4);
        assertThat(row().unavailable()).isFalse();

        transcoder.lastListener().onExit(1, "again");

        assertThat(transcoder.plans).hasSize(4);
        assertThat(row().unavailable()).isTrue();
    }

    @Test
    void theWaitCountsFromArrivalSoAReadThatTook2sLeaves4s() {
        String id = create(7);
        expire();
        stamps.hook = () -> clock.advance(Duration.ofSeconds(2));

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(waiter.timeouts).containsExactly(Duration.ofSeconds(4));
    }

    @Test
    void aReadThatTookOneMillisecondLessThan6sLeavesOneMillisecond() {
        String id = create(7);
        expire();
        stamps.hook = () -> clock.advance(Duration.ofSeconds(6).minus(ONE_MS));

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(waiter.timeouts).containsExactly(ONE_MS);
    }

    @Test
    void aReadThatTookExactly6sLeavesNothing() {
        String id = create(7);
        expire();
        stamps.hook = () -> clock.advance(Duration.ofSeconds(6));

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(waiter.timeouts).containsExactly(Duration.ZERO);
    }

    @Test
    void aReadThatTook7sLeavesNothingNotANegativeWaitAndTheAnswerIs503() {
        String id = create(7);
        expire();
        stamps.hook = () -> clock.advance(Duration.ofSeconds(7));

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(SegmentNotReadyException.class)
            .hasMessage("The segment is not ready yet");

        assertThat(waiter.timeouts).containsExactly(Duration.ZERO);
        assertThat(transcoder.plans).hasSize(2);
    }

    @Test
    void aRebuiltSessionCountsTowardsTheCapSoAFourthNewSessionPushesOutTheLeastRecentlyUsed() {
        String first = create(1);
        expire();
        String second = create(2);
        clock.advance(Duration.ofSeconds(10));
        create(3);
        clock.advance(Duration.ofSeconds(10));
        manager.view(first);
        clock.advance(Duration.ofSeconds(10));

        create(4);

        assertThat(liveIds()).containsExactly(id(3), id(1), id(4));
        assertFinal(second);
    }

    @Test
    void aRequestForAnIdNeverMadeReadsNoFile() {
        assertFinal(id(9));

        assertThat(stamps.reads).isEmpty();
    }

    @Test
    void reviveReturnsAtOnceForASessionThatIsAlreadyLive() {
        String id = create(7);
        int reads = stamps.reads.size();

        manager.revive(id);

        assertThat(stamps.reads).hasSize(reads);
        assertThat(liveIds()).containsExactly(id);
    }

    @Test
    void aSecondRebuildOfTheSameIdDuringTheReadLeavesOneLiveSessionAndOneNewRun() {
        String id = create(7);
        expire();
        waiter.whileWaiting = () -> write(1, "original", 40, 41);
        stamps.hook = () -> onAnotherThread(() -> manager.segment(id, "original", 40));

        Path file = manager.segment(id, "original", 40);

        assertThat(file).isEqualTo(seg(1, "original", 40));
        assertThat(liveIds()).containsExactly(id(1));
        assertThat(transcoder.plans).hasSize(2);
        assertThat(stamps.reads).hasSize(3);
    }

    @Test
    void anEndDuringTheReadLeaves404AndNoSession() {
        String id = create(7);
        expire();
        stamps.hook = () -> onAnotherThread(() -> {
            manager.end(id);
            return id;
        });

        assertThatThrownBy(() -> manager.view(id)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");

        assertThat(manager.sessions()).isEmpty();
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aNewSessionForTheEditionDuringTheReadLeaves404ForTheRememberedOne() {
        String id = create(7);
        expire();
        stamps.hook = () -> onAnotherThread(() -> create(7));

        assertThatThrownBy(() -> manager.segment(id, "original", 40)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");

        assertThat(liveIds()).containsExactly(id(2));
        assertThat(transcoder.plans).hasSize(2);
    }

    @Test
    void aViewOfAnotherLiveSessionDuringCreatesStampReadCompletes() {
        String other = create(1);
        List<String> seen = new ArrayList<>();
        stamps.hook = () -> seen.add(onAnotherThread(() -> manager.view(other).id()));

        String second = create(2);

        assertThat(seen).containsExactly(id(1));
        assertThat(second).isEqualTo(id(2));
        assertThat(liveIds()).containsExactly(id(1), id(2));
    }
}
