package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.SessionFakes.FakeFiles;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import gregochr.cinefilm.transcode.SessionFakes.FakeStamps;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import gregochr.cinefilm.transcode.SessionSpec.PartSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The session manager on a film split across two files, against the same fakes as {@link SessionManagerTest}. Each
 * part is 60 segments of 4 s (240 s), so part 0 is segments 0 to 59 and part 1 is segments 60 to 119; the player's
 * look-ahead is 180 s (45 segments) and a segment is waited for up to 10 ahead of the encoder's newest.
 */
class SessionManagerPartsTest {

    private static final Path HLS = Path.of("/cache/hls");
    private static final Path PART_A = Path.of("/library/Towers pt1.m4v");
    private static final Path PART_B = Path.of("/library/Towers pt2.m4v");
    private static final CutList LOCAL = CutList.fromKeyframes(
        IntStream.range(0, 60).mapToObj(i -> i * 4000L).toList(), 240_000);
    private static final CutList FILM = CutList.join(List.of(LOCAL, LOCAL));
    private static final List<Rung> RUNGS = Ladder.rungs(1080, 24_000_000, true, EncoderMode.VAAPI).subList(0, 2);
    private static final Duration IDLE = Duration.ofMinutes(2);
    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final Path PART_C = Path.of("/library/Towers pt3.m4v");
    private static final CutList FILM_3 = CutList.join(List.of(LOCAL, LOCAL, LOCAL));
    private static final int B_FIRST = 60;
    private static final int B_LAST = 119;

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

    private static SessionSpec spec(int startSegment) {
        return new SessionSpec(7, "Towers", List.of(new PartSource(PART_A, 1, 25.0), new PartSource(PART_B, 0, 24.0)),
            FILM, 1920, 1080, RUNGS, RUNGS.get(0), null, startSegment, true);
    }

    private String createThreeParts(int startSegment) {
        return manager.create(new SessionSpec(7, "Towers", List.of(new PartSource(PART_A, 1, 25.0),
            new PartSource(PART_B, 0, 24.0), new PartSource(PART_C, 0, 24.0)), FILM_3, 1920, 1080, RUNGS,
            RUNGS.get(0), null, startSegment, true)).id();
    }

    private static Path seg(String rung, int n) {
        return HLS.resolve(id(1)).resolve(rung).resolve("seg-" + n + ".ts");
    }

    private void write(String rung, int from, int to) {
        for (int n = from; n <= to; n++) {
            files.add(seg(rung, n));
        }
    }

    private void writeAll(int from, int to) {
        for (Rung rung : RUNGS) {
            write(rung.id(), from, to);
        }
    }

    private String create(int startSegment) {
        return manager.create(spec(startSegment)).id();
    }

    private TranscodePlan plan(int index) {
        return transcoder.plans.get(index);
    }

    @Test
    void aRunOfTheFirstPartEncodesThatFileOnItsOwnTimelineAndPadsItsAudio() {
        create(5);

        TranscodePlan plan = plan(0);
        assertThat(plan.source()).isEqualTo(PART_A);
        assertThat(plan.audioIndex()).isEqualTo(1);
        assertThat(plan.fps()).isEqualTo(25.0);
        assertThat(plan.cuts()).isSameAs(LOCAL);
        assertThat(plan.startSegment()).isEqualTo(5);
        assertThat(plan.partStartMs()).isZero();
        assertThat(plan.firstSegment()).isZero();
        assertThat(plan.startNumber()).isEqualTo(5);
        assertThat(plan.padAudio()).isTrue();
    }

    @Test
    void aSessionStartedInTheSecondPartEncodesThatFileFromItsLocalSegmentNumberedFilmWide() {
        create(70);

        TranscodePlan plan = plan(0);
        assertThat(plan.source()).isEqualTo(PART_B);
        assertThat(plan.audioIndex()).isZero();
        assertThat(plan.fps()).isEqualTo(24.0);
        assertThat(plan.startSegment()).isEqualTo(10);
        assertThat(plan.startMs()).isEqualTo(40_000);
        assertThat(plan.partStartMs()).isEqualTo(240_000);
        assertThat(plan.firstSegment()).isEqualTo(B_FIRST);
        assertThat(plan.startNumber()).isEqualTo(70);
        assertThat(plan.padAudio()).isFalse();
    }

    @Test
    void aCleanExitBeforeTheLastPartChainsIntoTheNextPartAtItsFirstSegment() {
        create(30);
        writeAll(30, 59);
        writeAll(B_FIRST, 62); // left from an earlier run: the chain starts over, it does not trust them

        transcoder.lastListener().onExit(0, "");

        assertThat(transcoder.plans).hasSize(2);
        TranscodePlan chained = plan(1);
        assertThat(chained.source()).isEqualTo(PART_B);
        assertThat(chained.startSegment()).isZero();
        assertThat(chained.firstSegment()).isEqualTo(B_FIRST);
        assertThat(chained.startNumber()).isEqualTo(B_FIRST);
        assertThat(chained.partStartMs()).isEqualTo(240_000);
        assertThat(chained.padAudio()).isFalse();
        assertThat(transcoder.encoders.get(0).stops).isZero();
        assertThat(files.existing).doesNotContain(seg("original", B_FIRST), seg("720p", 62));
        assertThat(files.existing).contains(seg("original", 59));
    }

    @Test
    void aCleanExitOfTheLastPartStartsNothing() {
        create(100);

        transcoder.lastListener().onExit(0, "");

        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void theLastSegmentOfAPartIsServedAfterTheChainStartedWithoutRestartingAnything() {
        String session = create(30);
        writeAll(30, 59);
        transcoder.lastListener().onExit(0, "");
        assertThat(transcoder.plans).hasSize(2);

        assertThat(manager.segment(session, "original", 59)).isEqualTo(seg("original", 59));
        assertThat(manager.segment(session, "720p", 58)).isEqualTo(seg("720p", 58));

        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.encoders.get(1).stops).isZero();
        assertThat(waiter.timeouts).isEmpty();
    }

    @Test
    void aPartsLastSegmentIsNotCompleteJustBecauseTheNextPartsFirstFileExists() {
        String session = create(55);
        write("original", 55, 59);
        write("original", B_FIRST, B_FIRST); // from an earlier run: says nothing about part 0's last segment

        assertThatThrownBy(() -> manager.segment(session, "original", 59)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(waiter.timeouts).hasSize(1);
        assertThat(transcoder.plans).hasSize(1);
        assertThat(manager.segment(session, "original", 58)).isEqualTo(seg("original", 58));
    }

    @Test
    void theEncodersNewestSegmentStopsAtTheEndOfItsPartEvenWhenTheNextPartsFilesExist() {
        create(30);
        writeAll(30, 66);

        transcoder.lastListener().onProgress(new EncoderProgress(240_000, 8.0));

        assertThat(manager.sessions().get(0).segmentsAhead()).isEqualTo(29); // 59 less 30, not 66 less 30
    }

    @Test
    void aRequestInTheNextPartJustBeforeTheRunEndsWaitsForTheChainInsteadOfRestarting() {
        String session = create(0);
        writeAll(0, 55);

        assertThatThrownBy(() -> manager.segment(session, "original", 62)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(1);
        assertThat(transcoder.encoders.get(0).stops).isZero();
        assertThat(waiter.timeouts).hasSize(1);
    }

    @Test
    void theWaitSeesTheChainStartAndTheNextPartsSegmentArrive() {
        String session = create(0);
        writeAll(0, 59);
        waiter.whileWaiting = () -> {
            transcoder.lastListener().onExit(0, "");
            writeAll(B_FIRST, 63);
        };

        assertThat(manager.segment(session, "original", 62)).isEqualTo(seg("original", 62));

        assertThat(transcoder.plans).hasSize(2);
        assertThat(plan(1).startNumber()).isEqualTo(B_FIRST);
    }

    @Test
    void aSeekAcrossTheJoinRestartsInTheOtherPartAtTheRequestedSegment() {
        String session = create(0);
        writeAll(0, 20);

        assertThatThrownBy(() -> manager.segment(session, "original", 90)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(transcoder.plans).hasSize(2);
        TranscodePlan restarted = plan(1);
        assertThat(restarted.source()).isEqualTo(PART_B);
        assertThat(restarted.startSegment()).isEqualTo(30);
        assertThat(restarted.startNumber()).isEqualTo(90);
        assertThat(restarted.partStartMs()).isEqualTo(240_000);
        assertThat(files.existing).contains(seg("original", 19)); // 20 was the dead run's half-written one
    }

    @Test
    void aSeekAcrossTwoJoinsRestartsInThePartThatHasTheSegmentWithoutWaitingForTheChain() {
        String session = createThreeParts(0);
        writeAll(0, 10);

        assertThatThrownBy(() -> manager.segment(session, "original", 150))
            .isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        TranscodePlan restarted = plan(1);
        assertThat(restarted.source()).isEqualTo(PART_C);
        assertThat(restarted.startSegment()).isEqualTo(30);
        assertThat(restarted.firstSegment()).isEqualTo(120);
        assertThat(restarted.partStartMs()).isEqualTo(480_000);
        assertThat(restarted.padAudio()).isFalse();
    }

    @Test
    void theChainWalksThroughAMiddlePartThatPadsItsAudioToTheLastOneThatDoesNot() {
        String session = createThreeParts(30);
        writeAll(30, 59);
        transcoder.lastListener().onExit(0, "");
        assertThat(plan(1).source()).isEqualTo(PART_B);
        assertThat(plan(1).padAudio()).isTrue();
        assertThat(plan(1).partStartMs()).isEqualTo(240_000);
        writeAll(B_FIRST, B_LAST);
        manager.segment(session, "original", 100); // the player has caught up with the next join

        transcoder.lastListener().onExit(0, "");

        assertThat(transcoder.plans).hasSize(3);
        assertThat(plan(2).source()).isEqualTo(PART_C);
        assertThat(plan(2).startNumber()).isEqualTo(120);
        assertThat(plan(2).partStartMs()).isEqualTo(480_000);
        assertThat(plan(2).padAudio()).isFalse();
    }

    @Test
    void aSeekBackAcrossTheJoinRestartsInThePreviousPartAndLeavesTheNextPartsFilesAlone() {
        String session = create(100);
        writeAll(100, 105);
        writeAll(B_FIRST, 110);

        assertThatThrownBy(() -> manager.segment(session, "original", 10)).isInstanceOf(SegmentNotReadyException.class);

        assertThat(transcoder.plans).hasSize(2);
        TranscodePlan restarted = plan(1);
        assertThat(restarted.source()).isEqualTo(PART_A);
        assertThat(restarted.startSegment()).isEqualTo(10);
        assertThat(restarted.padAudio()).isTrue();
        assertThat(files.existing).contains(seg("original", B_FIRST), seg("720p", 109));
    }

    @Test
    void aRestartInAPartDeletesOnlyThatPartsFilesFromTheRestartOnward() {
        String session = create(30);
        writeAll(0, 59);
        transcoder.lastListener().onExit(0, "");
        writeAll(B_FIRST, 90);

        assertThatThrownBy(() -> manager.segment(session, "original", 105))
            .isInstanceOf(SegmentNotReadyException.class);

        assertThat(plan(2).startNumber()).isEqualTo(105);
        assertThat(files.existing).contains(seg("original", 59), seg("original", 89), seg("720p", 0));
        assertThat(files.existing).doesNotContain(seg("original", 90), seg("original", 105));
    }

    @Test
    void killingTheRunDuringThePreviousPartLeavesTheNextPartsSegmentsOnDiskAndDeletesOnlyItsHalfWrittenOne() {
        String session = create(0);
        writeAll(0, 59);
        writeAll(B_FIRST, 70);

        clock.advance(IDLE);
        manager.sweep();

        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        assertThat(files.existing).doesNotContain(seg("original", 59), seg("720p", 59));
        assertThat(files.existing).contains(seg("original", 58), seg("original", B_FIRST), seg("720p", 70));
        // The killed run never finished part 0, so its last segment is gone and is made again on request.
        assertThatThrownBy(() -> manager.segment(session, "original", 59)).isInstanceOf(SegmentNotReadyException.class);
        assertThat(plan(1).startSegment()).isEqualTo(59);
        assertThat(plan(1).source()).isEqualTo(PART_A);
    }

    @Test
    void aCrashInTheSecondPartCleansOnlyThatPartAndRestartsThereThenGivesUpOnTheSecondCrash() {
        create(70);
        writeAll(70, 75);
        writeAll(0, 59);

        transcoder.lastListener().onExit(1, "crash");

        assertThat(transcoder.plans).hasSize(2);
        assertThat(plan(1).source()).isEqualTo(PART_B);
        assertThat(plan(1).startNumber()).isEqualTo(75);
        assertThat(files.existing).contains(seg("original", 59), seg("original", 74));
        assertThat(files.existing).doesNotContain(seg("original", 75));

        transcoder.lastListener().onExit(1, "crash again");

        assertThat(transcoder.plans).hasSize(2);
        assertThat(manager.sessions().get(0).unavailable()).isTrue();
    }

    @Test
    void whenThePlayerIsFarBehindTheNextPartIsNotStartedUntilThePlayerCatchesUp() {
        String session = create(0);
        writeAll(0, 59);

        transcoder.lastListener().onExit(0, "");

        assertThat(transcoder.plans).hasSize(1); // part 1 starts 240 s ahead of the player, more than 180 s

        assertThat(manager.segment(session, "original", 20)).isEqualTo(seg("original", 20)); // 160 s behind the join
        manager.sweep();

        assertThat(transcoder.plans).hasSize(2);
        assertThat(plan(1).startNumber()).isEqualTo(B_FIRST);
    }

    @Test
    void theNextPartIsStartedWhenTheJoinIsExactlyOneLookAheadAwayAndNotBefore() {
        String session = create(0);
        writeAll(0, 59);
        transcoder.lastListener().onExit(0, "");

        manager.segment(session, "original", 14); // the join is 184 s away: too far
        manager.sweep();
        assertThat(transcoder.plans).hasSize(1);

        manager.segment(session, "original", 15); // exactly 180 s away
        manager.sweep();
        assertThat(transcoder.plans).hasSize(2);
    }

    @Test
    void aSessionThatWentQuietWhileTheChainWaitedIsNotChainedByTheSweep() {
        create(0);
        writeAll(0, 59);
        transcoder.lastListener().onExit(0, "");
        assertThat(transcoder.plans).hasSize(1);

        clock.advance(IDLE);
        manager.sweep();

        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aKilledRunIsNotChainedByALaterSweepEvenWhenThePlayerIsNearTheJoin() {
        String session = create(30);
        writeAll(30, 59);
        clock.advance(IDLE);
        manager.sweep(); // idle: the run is stopped
        assertThat(transcoder.encoders.get(0).stops).isEqualTo(1);
        manager.segment(session, "original", 31); // the player is back, and 31 is still on disk

        manager.sweep();

        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aSessionMarkedUnavailableByAFailedChainIsNotChainedAgain() {
        create(40);
        writeAll(40, 59);
        transcoder.failure = new IOException("no ffmpeg");
        transcoder.lastListener().onExit(0, "");
        transcoder.failure = null;

        manager.sweep();

        assertThat(transcoder.plans).hasSize(1);
        assertThat(manager.sessions().get(0).unavailable()).isTrue();
    }

    @Test
    void aChainThatCannotStartMarksTheSessionUnavailable() {
        create(40);
        writeAll(40, 59);
        transcoder.failure = new IOException("no ffmpeg");

        transcoder.lastListener().onExit(0, "");

        assertThat(manager.sessions().get(0).unavailable()).isTrue();
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aSegmentOfTheSecondPartIsCompleteOnceTheNextOneExistsAndTheLastOneOnceThatRunFinished() {
        String session = create(100);
        write("original", 100, 119);

        assertThat(manager.segment(session, "original", 118)).isEqualTo(seg("original", 118));
        assertThatThrownBy(() -> manager.segment(session, "original", 119))
            .isInstanceOf(SegmentNotReadyException.class);

        transcoder.lastListener().onExit(0, "");

        assertThat(manager.segment(session, "original", 119)).isEqualTo(seg("original", 119));
        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aRunOfTheFirstPartThatFinishedDoesNotMakeTheSecondPartsSegmentsComplete() {
        String session = create(0);
        writeAll(0, 59);
        transcoder.lastListener().onExit(0, "");
        write("original", 100, 100);

        assertThatThrownBy(() -> manager.segment(session, "original", 100))
            .isInstanceOf(SegmentNotReadyException.class);
    }

    @Test
    void theViewCarriesTheJoinedCutListSoThePlaylistHasTheDiscontinuity() {
        SessionView view = manager.create(spec(0));

        assertThat(view.cuts()).isSameAs(FILM);
        assertThat(PlaylistWriter.media(view.cuts()))
            .contains("seg-59.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4.000,\nseg-60.ts");
        assertThat(view.cuts().segmentCount()).isEqualTo(B_LAST + 1);
    }

    private void expire() {
        clock.advance(EXPIRY);
        manager.sweep();
    }

    @Test
    void everyPartIsStampedInOrderWhenTheSessionIsMadeAndAgainWhenItIsRebuilt() {
        String session = create(0);
        assertThat(stamps.reads).containsExactly(PART_A, PART_B);
        expire();

        manager.view(session);

        assertThat(stamps.reads).containsExactly(PART_A, PART_B, PART_A, PART_B);
    }

    @Test
    void aSplitFilmRebuiltByAPlaylistRequestNearTheJoinStartsNothingEvenWhenSwept() {
        String session = create(30); // 120 s before the join: within the 180 s look-ahead
        expire();

        manager.view(session);
        manager.sweep();

        assertThat(transcoder.plans).hasSize(1);
        assertThat(manager.sessions()).hasSize(1);
    }

    @Test
    void aSplitFilmRebuiltByASegmentRequestInTheSecondPartRestartsThereWithFilmWideNumbering() {
        String session = create(0);
        expire();
        waiter.whileWaiting = () -> writeAll(75, 76);

        assertThat(manager.segment(session, "original", 75)).isEqualTo(seg("original", 75));

        assertThat(transcoder.plans).hasSize(2);
        TranscodePlan restarted = plan(1);
        assertThat(restarted.source()).isEqualTo(PART_B);
        assertThat(restarted.audioIndex()).isZero();
        assertThat(restarted.fps()).isEqualTo(24.0);
        assertThat(restarted.startSegment()).isEqualTo(15);
        assertThat(restarted.startNumber()).isEqualTo(75);
        assertThat(restarted.partStartMs()).isEqualTo(240_000);
    }

    @Test
    void aChangeToTheSecondPartsFileAloneMakesTheRebuildA404() {
        String session = create(0);
        expire();
        stamps.overrides.put(PART_B, new FileStamps.Stamp(1001, 5000));

        assertThatThrownBy(() -> manager.view(session)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");

        assertThat(transcoder.plans).hasSize(1);
        assertThat(manager.sessions()).isEmpty();
    }

    @Test
    void anUnreadableFirstPartWithAnUnchangedSecondIs503AndKeepsTheSessionRemembered() {
        String session = create(0);
        expire();
        stamps.unreadable.add(PART_A);

        assertThatThrownBy(() -> manager.view(session)).isInstanceOf(SegmentNotReadyException.class)
            .hasMessage("The film's file cannot be read right now");

        stamps.unreadable.clear();
        assertThat(manager.view(session).id()).isEqualTo(session);
    }

    @Test
    void anUnreadableFirstPartDoesNotHideAChangeToTheSecond() {
        String session = create(0);
        expire();
        stamps.unreadable.add(PART_A);
        stamps.overrides.put(PART_B, new FileStamps.Stamp(1000, 5001));

        assertThatThrownBy(() -> manager.view(session)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");

        stamps.unreadable.clear();
        stamps.overrides.clear();
        assertThatThrownBy(() -> manager.view(session)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
    }

    @Test
    void aMissingFirstPartIs404EvenWhenTheSecondIsUnreadable() {
        String session = create(0);
        expire();
        stamps.missing.add(PART_A);
        stamps.unreadable.add(PART_B);

        assertThatThrownBy(() -> manager.view(session)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such session");
    }
}
