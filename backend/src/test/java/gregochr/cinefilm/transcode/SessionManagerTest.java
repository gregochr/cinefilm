package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.SessionFakes.FakeFiles;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import gregochr.cinefilm.transcode.SessionFakes.FakeStamps;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The session manager against fakes: a hand-moved clock, a scripted waiter, a disk that is a set of paths and an
 * encoder that does nothing until the test reports progress or an exit. The film has 100 segments of 4 s, so segment
 * {@code n} starts at {@code 4000 * n} ms; the playhead limits are 3 minutes ahead (frontier 45 is allowed, 46 is
 * not) and 2 minutes behind.
 */
class SessionManagerTest {

    private static final Path HLS = Path.of("/cache/hls");
    private static final Path SOURCE = Path.of("/library/Heat.m4v");
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), 400_000);
    private static final List<Rung> RUNGS = Ladder.rungs(1080, 24_000_000, true, EncoderMode.VAAPI).subList(0, 2);
    private static final Duration IDLE = Duration.ofMinutes(2);
    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final Duration ONE_MS = Duration.ofMillis(1);
    private static final String DEVICE = "/dev/dri/renderD129";

    private final FakeTranscoder transcoder = new FakeTranscoder();
    private final FakeFiles files = new FakeFiles();
    private final ScriptedWaiter waiter = new ScriptedWaiter();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final MutableClock clock = new MutableClock();
    private int counter;
    private final SessionManager manager = new SessionManager(transcoder, files, waiter, scheduler, clock,
        new SessionSettings(IDLE, EXPIRY, 1000, 2, DEVICE), new FakeStamps(), HLS, () -> new UUID(0, ++counter));

    private static String id(int n) {
        return new UUID(0, n).toString();
    }

    private static SessionSpec spec(long edition, int startSegment) {
        return new SessionSpec(edition, "Heat", SOURCE, 1, CUTS, 25.0, 1920, 1080, RUNGS, RUNGS.get(0), null,
            startSegment, true);
    }

    private static Path seg(int session, String rung, int n) {
        return HLS.resolve(id(session)).resolve(rung).resolve("seg-" + n + ".ts");
    }

    private void write(int session, String rung, int from, int to) {
        for (int n = from; n <= to; n++) {
            files.add(seg(session, rung, n));
        }
    }

    private String create(long edition, int startSegment) {
        return manager.create(spec(edition, startSegment)).id();
    }

    private SessionDtos.SessionRow row() {
        return manager.sessions().get(0);
    }

    @Test
    void createStartsOneEncoderAtTheStartSegmentInTheSessionFolder() {
        SessionView view = manager.create(spec(7, 5));

        assertThat(view.id()).isEqualTo(id(1));
        assertThat(view.rungs()).isEqualTo(RUNGS);
        assertThat(view.startRung()).isEqualTo(RUNGS.get(0));
        assertThat(view.pinned()).isNull();
        assertThat(view.cuts()).isSameAs(CUTS);
        assertThat(view.width()).isEqualTo(1920);
        assertThat(view.height()).isEqualTo(1080);
        assertThat(transcoder.plans).hasSize(1);
        TranscodePlan plan = transcoder.lastPlan();
        assertThat(plan.source()).isEqualTo(SOURCE);
        assertThat(plan.outputDir()).isEqualTo(HLS.resolve(id(1)));
        assertThat(plan.rungs()).isEqualTo(RUNGS);
        assertThat(plan.audioIndex()).isEqualTo(1);
        assertThat(plan.startSegment()).isEqualTo(5);
        assertThat(plan.mode()).isEqualTo(EncoderMode.VAAPI);
        assertThat(plan.threads()).isEqualTo(2);
        assertThat(plan.fps()).isEqualTo(25.0);
        assertThat(plan.hardwareDecode()).isTrue();
        assertThat(plan.device()).isEqualTo(DEVICE);
        assertThat(row()).isEqualTo(new SessionDtos.SessionRow(id(1), "Heat", 7, "original", null, 0,
            TestData.NOW, false));
    }

    @Test
    void aFailingEncoderStartLeavesNoSessionAndNoFolder() {
        transcoder.failure = new IOException("no ffmpeg");

        assertThatThrownBy(() -> manager.create(spec(7, 0))).isInstanceOf(SessionStartException.class)
            .hasCauseInstanceOf(IOException.class).hasMessage("The encoder could not be started");

        assertThat(manager.sessions()).isEmpty();
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)));
    }

    @Test
    void aNewSessionForTheSameEditionReplacesTheOldOne() {
        create(7, 0);
        write(1, "original", 0, 3);

        String second = create(7, 0);

        assertThat(second).isEqualTo(id(2));
        assertThat(manager.sessions()).extracting(SessionDtos.SessionRow::id).containsExactly(id(2));
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)));
        assertThatThrownBy(() -> manager.view(id(1))).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
    }

    @Test
    void theFourthSessionReplacesTheLeastRecentlyUsedNeverRefused() {
        create(1, 0);
        clock.advance(Duration.ofSeconds(10));
        create(2, 0);
        clock.advance(Duration.ofSeconds(10));
        create(3, 0);
        clock.advance(Duration.ofSeconds(10));
        manager.view(id(1));

        String fourth = create(4, 0);

        assertThat(fourth).isEqualTo(id(4));
        assertThat(manager.sessions()).extracting(SessionDtos.SessionRow::id)
            .containsExactly(id(1), id(3), id(4));
        assertThat(transcoder.encoders.get(1).stops).isEqualTo(1);
        assertThat(transcoder.encoders.get(0).stops).isZero();
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(2)));
    }

    @Test
    void aCompleteSegmentIsServedAtOnceWithoutWaiting() {
        String id = create(7, 0);
        write(1, "original", 0, 1);

        assertThat(manager.segment(id, "original", 0)).isEqualTo(seg(1, "original", 0));

        assertThat(waiter.timeouts).isEmpty();
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aSegmentTheEncoderIsStillWritingIsWaitedForThenServed() {
        String id = create(7, 0);
        write(1, "original", 0, 0);
        waiter.whileWaiting = () -> write(1, "original", 1, 2);

        assertThat(manager.segment(id, "original", 1)).isEqualTo(seg(1, "original", 1));

        assertThat(waiter.timeouts).containsExactly(Duration.ofSeconds(6));
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aSegmentThatNeverArrivesIs503NotYetNeverA404AndTheEncoderKeepsGoing() {
        String id = create(7, 0);

        assertThatThrownBy(() -> manager.segment(id, "original", 3)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(waiter.timeouts).containsExactly(Duration.ofSeconds(6));
        assertThat(transcoder.plans).hasSize(1);
        assertThat(transcoder.encoders.get(0).stops).isZero();
    }

    @Test
    void aSegmentUpToTenAheadOfTheEncoderIsWaitedForButElevenAheadRestartsIt() {
        String id = create(7, 0);

        assertThatThrownBy(() -> manager.segment(id, "original", 10)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(transcoder.plans).hasSize(1);

        assertThatThrownBy(() -> manager.segment(id, "original", 11)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(11);
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(transcoder.encoders.get(1).stops).isZero();
    }

    @Test
    void theEncodersProgressIsReadFromTheFilesItHasStartedWriting() {
        String id = create(7, 0);
        write(1, "original", 0, 3);

        assertThatThrownBy(() -> manager.segment(id, "original", 13)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(transcoder.plans).hasSize(1);

        assertThatThrownBy(() -> manager.segment(id, "original", 14)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(14);
    }

    @Test
    void seekingBehindTheEncodersStartRestartsItThereAndClearsEverythingFromThatSegmentOn() {
        String id = create(7, 20);
        write(1, "original", 20, 22);
        write(1, "original", 7, 7);
        write(1, "720p", 21, 21);

        assertThatThrownBy(() -> manager.segment(id, "original", 5)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(5);
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(files.existing).isEmpty();
    }

    @Test
    void anEncoderThatWasStoppedIsRestartedWhenTheNextSegmentIsNeeded() {
        String id = create(7, 0);
        clock.advance(IDLE);
        manager.sweep();
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);

        assertThatThrownBy(() -> manager.segment(id, "original", 3)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(3);
    }

    @Test
    void anEncoderKilledMidSegmentLeavesNoHalfWrittenFileThatALaterRestartCouldMistakeForComplete() {
        String id = create(7, 0);
        write(1, "original", 0, 9);
        write(1, "720p", 0, 8);

        clock.advance(IDLE);
        manager.sweep();

        assertThat(files.existing).contains(seg(1, "original", 8), seg(1, "720p", 7))
            .doesNotContain(seg(1, "original", 9), seg(1, "720p", 8));
        assertThatThrownBy(() -> manager.segment(id, "original", 9)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(9);
    }

    @Test
    void theEncoderIsKilledOnceItIsMoreThanThreeMinutesPastThePlayheadAndRestartedWhenNeeded() {
        String id = create(7, 0);
        write(1, "original", 0, 45);
        manager.sweep();
        assertThat(transcoder.encoders.get(0).stops).isZero();

        write(1, "original", 46, 46);
        manager.sweep();

        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(files.existing).contains(seg(1, "original", 45)).doesNotContain(seg(1, "original", 46));
        assertThat(row().speed()).isNull();
        assertThat(row().segmentsAhead()).isZero();

        assertThatThrownBy(() -> manager.segment(id, "original", 47)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(47);
    }

    @Test
    void progressReportsKillARunawayEncoderWithoutWaitingForTheNextSweep() {
        create(7, 0);
        write(1, "original", 0, 46);

        transcoder.lastListener().onProgress(new EncoderProgress(184_000, 8.0));

        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
    }

    @Test
    void progressCarriesTheSpeedAndTheSegmentsAheadOfThePlayer() {
        create(7, 0);
        write(1, "original", 0, 4);

        transcoder.lastListener().onProgress(new EncoderProgress(16_000, 2.5));
        assertThat(row().speed()).isEqualTo(2.5);
        assertThat(row().segmentsAhead()).isEqualTo(4);

        transcoder.lastListener().onProgress(new EncoderProgress(16_000, 0));
        assertThat(row().speed()).isNull();
    }

    @Test
    void progressFromAStoppedRunIsIgnored() {
        String id = create(7, 0);
        EncoderListener first = transcoder.lastListener();
        assertThatThrownBy(() -> manager.segment(id, "original", 50)).isInstanceOf(SegmentNotReadyException.class);
        write(1, "original", 50, 53);

        first.onProgress(new EncoderProgress(1000, 9.0));

        assertThat(row().speed()).isNull();
    }

    @Test
    void segmentsMoreThanTwoMinutesBehindThePlayheadAreDeletedInEveryRung() {
        String id = create(7, 0);
        write(1, "original", 0, 61);
        write(1, "720p", 0, 61);
        assertThat(manager.segment(id, "original", 60)).isEqualTo(seg(1, "original", 60));

        manager.sweep();

        for (String rung : List.of("original", "720p")) {
            assertThat(files.existing).doesNotContain(seg(1, rung, 0), seg(1, rung, 28), seg(1, rung, 29))
                .contains(seg(1, rung, 30), seg(1, rung, 31), seg(1, rung, 61));
        }
    }

    @Test
    void seekingBackMoreThanTwoMinutesMovesThePlayheadButASmallStepBackDoesNot() {
        String id = create(7, 0);
        write(1, "original", 0, 61);
        manager.segment(id, "original", 60);
        assertThat(row().segmentsAhead()).isEqualTo(1);

        manager.segment(id, "original", 30);
        assertThat(row().segmentsAhead()).isEqualTo(1);

        manager.segment(id, "original", 29);
        assertThat(row().segmentsAhead()).isEqualTo(32);
    }

    @Test
    void theEncoderStopsAfterTwoMinutesWithoutARequestButNotOneMillisecondBefore() {
        create(7, 0);

        clock.advance(IDLE.minus(ONE_MS));
        manager.sweep();
        assertThat(transcoder.encoders.get(0).stops).isZero();

        clock.advance(ONE_MS);
        manager.sweep();
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(manager.sessions()).hasSize(1);
    }

    @Test
    void aRequestKeepsTheSessionAlive() {
        String id = create(7, 0);
        clock.advance(IDLE.minus(ONE_MS));
        manager.view(id);
        clock.advance(IDLE.minus(ONE_MS));
        manager.sweep();
        assertThat(transcoder.encoders.get(0).stops).isZero();
        assertThat(manager.sessions()).hasSize(1);
    }

    @Test
    void theSessionIsDeletedAfterTenMinutesAndOnceEndedLaterRequestsGet404() {
        String id = create(7, 0);
        write(1, "original", 0, 3);

        clock.advance(EXPIRY.minus(ONE_MS));
        manager.sweep();
        assertThat(manager.sessions()).hasSize(1);

        clock.advance(ONE_MS);
        manager.sweep();

        assertThat(manager.sessions()).isEmpty();
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)));
        manager.end(id);
        assertThatThrownBy(() -> manager.segment(id, "original", 0)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
        assertThatThrownBy(() -> manager.view(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void endStopsTheEncoderDeletesTheFolderAndIsIdempotent() {
        String id = create(7, 0);

        manager.end(id);
        manager.end(id);
        manager.end(id(99));

        assertThat(manager.sessions()).isEmpty();
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)));
    }

    @Test
    void startupDeletesEveryLeftoverSessionFolderAndSchedulesUpkeepEveryTwoSeconds() {
        files.directories.addAll(List.of(HLS.resolve("old-a"), HLS.resolve("old-b")));

        manager.start();

        assertThat(files.deletedTrees).containsExactly(HLS.resolve("old-a"), HLS.resolve("old-b"));
        assertThat(scheduler.period).isEqualTo(Duration.ofSeconds(2));
        create(7, 0);
        clock.advance(EXPIRY);
        scheduler.task.run();
        assertThat(manager.sessions()).isEmpty();
    }

    @Test
    void shutdownCancelsUpkeepAndStopsEveryRunningEncoderOnly() {
        manager.start();
        create(1, 0);
        create(2, 0);
        transcoder.listeners.get(1).onExit(0, "");

        manager.shutdown();

        assertThat(scheduler.cancelled).isTrue();
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(transcoder.encoders.get(1).stops).isZero();
    }

    @Test
    void shutdownWithoutStartIsHarmless() {
        manager.shutdown();

        assertThat(scheduler.cancelled).isFalse();
    }

    @Test
    void overTheCapOnlyIdleSessionsAreEvictedLeastRecentlyUsedFirstUntilUnderIt() {
        create(1, 0);
        clock.advance(Duration.ofMinutes(1));
        create(2, 0);
        clock.advance(Duration.ofMinutes(1));
        create(3, 0);
        clock.advance(Duration.ofMinutes(2));
        manager.view(id(3));
        files.sizes.put(HLS, 1500L);
        files.sizes.put(HLS.resolve(id(1)), 600L);
        files.sizes.put(HLS.resolve(id(2)), 600L);

        manager.sweep();

        assertThat(manager.sessions()).extracting(SessionDtos.SessionRow::id).containsExactly(id(2), id(3));
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)));
    }

    @Test
    void evictionKeepsGoingThroughIdleSessionsButNeverTouchesAnActiveOne() {
        create(1, 0);
        clock.advance(Duration.ofMinutes(1));
        create(2, 0);
        clock.advance(Duration.ofMinutes(1));
        create(3, 0);
        clock.advance(Duration.ofMinutes(2));
        manager.view(id(3));
        files.sizes.put(HLS, 2500L);
        files.sizes.put(HLS.resolve(id(1)), 600L);
        files.sizes.put(HLS.resolve(id(2)), 600L);

        manager.sweep();

        assertThat(manager.sessions()).extracting(SessionDtos.SessionRow::id).containsExactly(id(3));
        assertThat(transcoder.encoders.get(2).stops).isZero();
    }

    @Test
    void anActiveSessionIsNotEvictedEvenWhenHlsIsOverTheCapAndAtTheCapNothingIsEvicted() {
        create(1, 0);
        clock.advance(IDLE.minus(ONE_MS));
        files.sizes.put(HLS, 5000L);
        files.sizes.put(HLS.resolve(id(1)), 5000L);

        manager.sweep();
        assertThat(manager.sessions()).hasSize(1);

        clock.advance(ONE_MS);
        files.sizes.put(HLS, 1000L);
        manager.sweep();
        assertThat(manager.sessions()).hasSize(1);

        files.sizes.put(HLS, 1001L);
        manager.sweep();
        assertThat(manager.sessions()).isEmpty();
    }

    @Test
    void hlsBytesIsTheSizeOfTheHlsFolder() {
        files.sizes.put(HLS, 1_288_490_188L);

        assertThat(manager.hlsBytes()).isEqualTo(1_288_490_188L);
    }

    @Test
    void aCrashedEncoderIsRestartedOnceAtTheNextNeededSegmentAfterDeletingItsHalfWrittenFile() {
        create(7, 0);
        write(1, "original", 0, 3);
        EncoderListener crashed = transcoder.lastListener();

        crashed.onExit(1, "Conversion failed");

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(3);
        assertThat(transcoder.encoders.get(0).stops).isZero();
        assertThat(files.existing).contains(seg(1, "original", 2)).doesNotContain(seg(1, "original", 3));
        assertThat(row().unavailable()).isFalse();
    }

    @Test
    void aSecondCrashMakesTheSessionUnavailableButCompleteSegmentsStillPlay() {
        String id = create(7, 0);
        write(1, "original", 0, 3);
        transcoder.lastListener().onExit(1, "first");

        transcoder.lastListener().onExit(1, "second");

        assertThat(transcoder.plans).hasSize(2);
        assertThat(row().unavailable()).isTrue();
        assertThat(manager.segment(id, "original", 1)).isEqualTo(seg(1, "original", 1));
        assertThatThrownBy(() -> manager.segment(id, "original", 20)).isInstanceOf(SessionUnavailableException.class)
            .hasMessage("Transcoding failed for this session");
        assertThat(waiter.timeouts).isEmpty();
        assertThat(transcoder.plans).hasSize(2);
    }

    @Test
    void aCrashWhoseRestartCannotStartMakesTheSessionUnavailable() {
        create(7, 0);
        transcoder.failure = new IOException("gone");

        transcoder.lastListener().onExit(1, "crash");

        assertThat(transcoder.plans).hasSize(1);
        assertThat(row().unavailable()).isTrue();
    }

    @Test
    void aSeekRestartThatCannotStartMakesTheSessionUnavailableWithoutWaiting() {
        String id = create(7, 0);
        transcoder.failure = new IOException("gone");

        assertThatThrownBy(() -> manager.segment(id, "original", 50)).isInstanceOf(SessionUnavailableException.class);

        assertThat(row().unavailable()).isTrue();
        assertThat(waiter.timeouts).isEmpty();
    }

    @Test
    void theExitOfAnEncoderTheManagerStoppedItselfIsNotACrash() {
        String id = create(7, 0);
        EncoderListener first = transcoder.lastListener();
        assertThatThrownBy(() -> manager.segment(id, "original", 50)).isInstanceOf(SegmentNotReadyException.class);

        first.onExit(143, "terminated");
        assertThat(transcoder.plans).hasSize(2);

        transcoder.lastListener().onExit(1, "real crash");
        assertThat(transcoder.plans).hasSize(3);
        assertThat(row().unavailable()).isFalse();
    }

    @Test
    void aRunThatFinishedCleanlyServesEverySegmentItWroteIncludingTheLastOne() {
        String id = create(7, 0);
        write(1, "original", 99, 99);
        write(1, "original", 50, 50);
        transcoder.lastListener().onExit(0, "");

        assertThat(manager.segment(id, "original", 99)).isEqualTo(seg(1, "original", 99));
        assertThat(manager.segment(id, "original", 50)).isEqualTo(seg(1, "original", 50));
        assertThat(waiter.timeouts).isEmpty();
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aSegmentDeletedAfterTheRunFinishedRestartsTheEncoderThere() {
        String id = create(7, 0);
        transcoder.lastListener().onExit(0, "");

        assertThatThrownBy(() -> manager.segment(id, "original", 5)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(5);
    }

    @Test
    void aFileBeforeTheFinishedRunsStartIsOnlyCompleteWhenTheNextOneExists() {
        String id = create(7, 50);
        write(1, "original", 10, 10);
        transcoder.lastListener().onExit(0, "");

        assertThatThrownBy(() -> manager.segment(id, "original", 10)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(10);
    }

    @Test
    void aSegmentFileThatTheRunningEncoderIsStillWritingIsNeverServed() {
        String id = create(7, 0);
        write(1, "original", 0, 1);

        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(waiter.timeouts).containsExactly(Duration.ofSeconds(6));
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void theEncodersNewestSegmentNeverPassesTheLastSegmentOfTheFilm() {
        create(7, 98);
        write(1, "original", 99, 99);

        assertThat(row().segmentsAhead()).isEqualTo(1);
    }

    @Test
    void aCrashInTheLastSegmentDeletesItAndRestartsThere() {
        create(7, 97);
        write(1, "original", 97, 99);

        transcoder.lastListener().onExit(1, "crash");

        assertThat(files.existing).contains(seg(1, "original", 98)).doesNotContain(seg(1, "original", 99));
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(99);
    }

    @Test
    void aSegmentRequestForAnUnknownSessionRungOrSegmentIs404() {
        String id = create(7, 0);

        assertThatThrownBy(() -> manager.segment(id(9), "original", 0)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
        assertThatThrownBy(() -> manager.segment(id, "360p", 0)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such segment");
        assertThatThrownBy(() -> manager.segment(id, "original", 100)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such segment");
        assertThatThrownBy(() -> manager.segment(id, "original", -1)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such segment");
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void theLastSegmentIsRequestableAndTheRungIsRecordedForAdmin() {
        String id = create(7, 0);
        write(1, "720p", 99, 99);
        transcoder.lastListener().onExit(0, "");

        manager.segment(id, "720p", 99);

        assertThat(row().rung()).isEqualTo("720p");
    }

    @Test
    void theWaitConditionIsFalseUntilTheSegmentIsCompleteThenTrue() {
        String id = create(7, 0);
        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(SegmentNotReadyException.class);
        BooleanSupplier condition = waiter.condition;
        assertThat(condition.getAsBoolean()).isFalse();

        write(1, "original", 1, 2);

        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void theWaitConditionEndsWhenTheEncoderIsNoLongerRunning() {
        String id = create(7, 0);
        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(SegmentNotReadyException.class);
        BooleanSupplier condition = waiter.condition;
        assertThat(condition.getAsBoolean()).isFalse();

        transcoder.lastListener().onExit(0, "");

        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void theWaitConditionEndsWhenTheSessionIsGone() {
        String id = create(7, 0);
        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(SegmentNotReadyException.class);
        BooleanSupplier condition = waiter.condition;

        manager.end(id);

        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void theWaitConditionEndsWhenTheSessionBecomesUnavailable() {
        String id = create(7, 0);
        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(SegmentNotReadyException.class);
        BooleanSupplier condition = waiter.condition;

        transcoder.lastListener().onExit(1, "one");
        assertThat(condition.getAsBoolean()).isFalse();
        transcoder.lastListener().onExit(1, "two");

        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void aSessionEndedWhileARequestWaitsAnswers404() {
        String id = create(7, 0);
        waiter.whileWaiting = () -> manager.end(id);

        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
    }

    @Test
    void aSessionThatFailsWhileARequestWaitsAnswersUnavailableNotNotReady() {
        String id = create(7, 0);
        waiter.whileWaiting = () -> {
            transcoder.lastListener().onExit(1, "one");
            transcoder.lastListener().onExit(1, "two");
        };

        assertThatThrownBy(() -> manager.segment(id, "original", 1)).isInstanceOf(SessionUnavailableException.class);
    }

    @Test
    void viewDescribesThePinnedSessionForThePlaylists() {
        SessionSpec pinned = new SessionSpec(7, "Heat", SOURCE, 0, CUTS, null, 1920, 1080,
            List.of(RUNGS.get(1)), RUNGS.get(1), "720p", 0, false);

        SessionView view = manager.create(pinned);

        assertThat(view.rungs()).containsExactly(RUNGS.get(1));
        assertThat(view.startRung()).isEqualTo(RUNGS.get(1));
        assertThat(view.pinned()).isEqualTo("720p");
        assertThat(transcoder.lastPlan().fps()).isNull();
        assertThat(transcoder.lastPlan().hardwareDecode()).isFalse();
        assertThat(transcoder.lastPlan().device()).isEqualTo(DEVICE);
    }

    @Test
    void viewReturnsTheDescriptionOfTheSessionItWasCreatedWith() {
        SessionView created = manager.create(spec(7, 0));

        assertThat(manager.view(created.id())).isEqualTo(created);
    }

    @Test
    void evictionStopsAsSoonAsHlsIsExactlyAtTheCap() {
        create(1, 0);
        clock.advance(Duration.ofMinutes(1));
        create(2, 0);
        clock.advance(Duration.ofMinutes(1));
        create(3, 0);
        clock.advance(Duration.ofMinutes(2));
        manager.view(id(3));
        files.sizes.put(HLS, 1600L);
        files.sizes.put(HLS.resolve(id(1)), 600L);
        files.sizes.put(HLS.resolve(id(2)), 600L);

        manager.sweep();

        assertThat(manager.sessions()).extracting(SessionDtos.SessionRow::id).containsExactly(id(2), id(3));
        assertThat(files.deletedTrees).containsExactly(HLS.resolve(id(1)));
    }

    @Test
    void theFileJustBeforeASegmentDoesNotMakeItComplete() {
        String id = create(7, 0);
        write(1, "original", 4, 5);

        assertThatThrownBy(() -> manager.segment(id, "original", 5)).isInstanceOf(SegmentNotReadyException.class);
    }

    @Test
    void aFilePastTheLastSegmentDoesNotMakeTheLastSegmentComplete() {
        String id = create(7, 0);
        write(1, "original", 99, 100);

        assertThatThrownBy(() -> manager.segment(id, "original", 99)).isInstanceOf(SegmentNotReadyException.class);
    }

    @Test
    void aFinishedRunServesItsOwnFirstSegmentAlone() {
        String id = create(7, 5);
        write(1, "original", 5, 5);
        transcoder.lastListener().onExit(0, "");

        assertThat(manager.segment(id, "original", 5)).isEqualTo(seg(1, "original", 5));
    }

    @Test
    void theSegmentTheEncoderStartedAtIsWaitedForNotRestarted() {
        String id = create(7, 5);

        assertThatThrownBy(() -> manager.segment(id, "original", 5)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(1);
        assertThat(transcoder.encoders.get(0).stops).isZero();
    }

    @Test
    void theEncodersNewestSegmentStopsAtTheLastSegmentEvenWhenFilesExistBeyondIt() {
        create(7, 0);
        write(1, "original", 0, 101);

        assertThat(row().segmentsAhead()).isEqualTo(99);
    }

    @Test
    void aKilledRunDeletesExactlyTheLastSegmentOfTheFilmAndNothingBeyondIt() {
        create(7, 0);
        write(1, "original", 0, 101);
        write(1, "720p", 0, 101);

        clock.advance(IDLE);
        manager.sweep();

        for (String rung : List.of("original", "720p")) {
            assertThat(files.existing).contains(seg(1, rung, 98), seg(1, rung, 100), seg(1, rung, 101))
                .doesNotContain(seg(1, rung, 99));
        }
    }

    @Test
    void progressFromARunTheManagerStoppedTouchesNoFiles() {
        String id = create(7, 0);
        EncoderListener first = transcoder.lastListener();
        assertThatThrownBy(() -> manager.segment(id, "original", 50)).isInstanceOf(SegmentNotReadyException.class);
        int before = files.existsCalls;

        first.onProgress(new EncoderProgress(1000, 9.0));

        assertThat(files.existsCalls).isEqualTo(before);
    }
}
