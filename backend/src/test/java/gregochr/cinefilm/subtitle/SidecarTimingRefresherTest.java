package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.subtitle.EmbeddedCueReader.ReadException;
import gregochr.cinefilm.subtitle.EmbeddedCueReader.Reading;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.ReferenceRead;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.transcode.SessionManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * The background timing check: one film at a time, reads before fits, nothing run while playback is encoding or the
 * library is locked, a failed read left alone for a while, and a run that never throws. Runs on an executor the test
 * controls.
 */
@ExtendWith(MockitoExtension.class)
class SidecarTimingRefresherTest {

    private static final Path FILE = Path.of("/library/Taken.m4v");
    private static final String FINISHED =
        "Subtitle timing checks finished: %d files read, %d fitted (%d offsets set), %d failed";
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();
    private static final List<Long> TRACK = IntStream.range(0, 120).mapToObj(i -> 1_000L + i * 7_000L).toList();

    @Mock
    private SidecarTimingService service;

    @Mock
    private EmbeddedCueReader reader;

    @Mock
    private LibraryRoot root;

    @Mock
    private SessionManager sessions;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(SidecarTimingRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Instant now = NOW;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private SidecarTimingRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SidecarTimingRefresher(service, reader, root, sessions, clock, queued::add, true);
        logged.start();
        refresherLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ReferenceRead read(long fileId) {
        return new ReferenceRead(fileId, "Taken.m4v", "1000:1");
    }

    private static FitJob job(long filmId, long sidecarId) {
        return new FitJob(filmId, sidecarId, "id" + sidecarId, "key" + sidecarId, STARTS, TRACK, null);
    }

    private static Reading usable() {
        return new Reading(EmbeddedCueStarts.Status.USABLE, 2, 3, "subrip", TRACK, null);
    }

    /** Starts a run and executes what was queued, as the executor's thread would. */
    private void startAndRun() {
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    private void filmWithOneReadAndOneJob() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L)));
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.of(FILE));
        when(reader.read(FILE)).thenReturn(usable());
        when(service.saveReference(read(70L), usable(), NOW)).thenReturn(SaveOutcome.SAVED);
        when(service.fitJobs(7L)).thenReturn(List.of(job(7L, 5L)));
    }

    @Test
    void aFilmIsReadThenFittedThenSavedAndTheRunSaysSo() {
        filmWithOneReadAndOneJob();
        when(service.save(any(FitJob.class), any(TimingFit.Result.class), any(Instant.class)))
            .thenReturn(SaveOutcome.APPLIED);

        startAndRun();

        InOrder order = inOrder(service, reader);
        order.verify(service).dueFilmIds();
        order.verify(service).readsFor(7L);
        order.verify(reader).read(FILE);
        order.verify(service).saveReference(read(70L), usable(), NOW);
        order.verify(service).fitJobs(7L);
        order.verify(service).save(job(7L, 5L), TimingFit.fit(STARTS, TRACK), NOW);
        assertThat(messages()).containsExactly(FINISHED.formatted(1, 1, 1, 0));
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aFileWithNoUsableTrackIsSavedAsNoReferenceWithoutFitting() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of());
        FitJob job = new FitJob(7L, 5L, "id", "key", STARTS, List.of(), "The file has no subtitle track");
        when(service.fitJobs(7L)).thenReturn(List.of(job));
        when(service.save(job, TimingFit.noReference(12, "The file has no subtitle track"), NOW))
            .thenReturn(SaveOutcome.SAVED);

        startAndRun();

        assertThat(messages()).containsExactly(FINISHED.formatted(0, 1, 0, 0));
        verifyNoInteractions(reader);
    }

    @Test
    void aFitThatChangedMeanwhileIsNotCountedAndTheNextFilmIsStillDone() {
        when(service.dueFilmIds()).thenReturn(List.of(7L, 8L));
        when(service.readsFor(7L)).thenReturn(List.of());
        when(service.readsFor(8L)).thenReturn(List.of());
        when(service.fitJobs(7L)).thenReturn(List.of(job(7L, 5L)));
        when(service.fitJobs(8L)).thenReturn(List.of(job(8L, 6L)));
        when(service.save(job(7L, 5L), TimingFit.fit(STARTS, TRACK), NOW)).thenReturn(SaveOutcome.DROPPED);
        when(service.save(job(8L, 6L), TimingFit.fit(STARTS, TRACK), NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        assertThat(messages()).containsExactly(FINISHED.formatted(0, 1, 0, 0));
    }

    @Test
    void aReadThatChangedMeanwhileIsNotCounted() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L)));
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.of(FILE));
        when(reader.read(FILE)).thenReturn(usable());
        when(service.saveReference(read(70L), usable(), NOW)).thenReturn(SaveOutcome.DROPPED);
        when(service.fitJobs(7L)).thenReturn(List.of());

        startAndRun();

        assertThat(messages()).containsExactly(FINISHED.formatted(0, 0, 0, 0));
    }

    @Test
    void nothingIsDoneWhileAFilmIsBeingTranscodedAndTheRunIsLeftForLater() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(sessions.isEncoding()).thenReturn(true);

        startAndRun();

        verify(service, never()).readsFor(any());
        verifyNoInteractions(reader);
        assertThat(messages()).containsExactly("Subtitle timing checks wait: a film is being transcoded",
            FINISHED.formatted(0, 0, 0, 0));

        // The hourly nudge starts a run because the last one waited; once playback is done it works.
        queued.clear();
        when(sessions.isEncoding()).thenReturn(false);
        when(service.readsFor(7L)).thenReturn(List.of());
        when(service.fitJobs(7L)).thenReturn(List.of());
        refresher.startIfDeferred();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(service).readsFor(7L);
        queued.clear();
        refresher.startIfDeferred();
        assertThat(queued).isEmpty();
    }

    @Test
    void aTranscodeThatStartsBetweenTwoReadsStopsTheRunBeforeTheSecond() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L), read(71L)));
        when(sessions.isEncoding()).thenReturn(false, false, true);
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.of(FILE));
        when(reader.read(FILE)).thenReturn(usable());
        when(service.saveReference(read(70L), usable(), NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        verify(reader, times(1)).read(FILE);
        verify(service, never()).fitJobs(any());
        assertThat(messages()).contains("Subtitle timing checks wait: a film is being transcoded");
    }

    @Test
    void aTakenLibraryLockStopsTheRunAndLeavesTheRestForLater() {
        when(service.dueFilmIds()).thenReturn(List.of(7L, 8L));
        when(service.readsFor(7L)).thenReturn(List.of());
        when(service.fitJobs(7L)).thenReturn(List.of(job(7L, 5L), job(7L, 6L)));
        when(service.save(job(7L, 5L), TimingFit.fit(STARTS, TRACK), NOW)).thenReturn(SaveOutcome.BUSY);

        startAndRun();

        verify(service, never()).readsFor(8L);
        verify(service, never()).save(job(7L, 6L), TimingFit.fit(STARTS, TRACK), NOW);
        assertThat(messages()).containsExactly("Subtitle timing checks wait: a scan or an edit is using the library",
            FINISHED.formatted(0, 0, 0, 0));
        queued.clear();
        refresher.startIfDeferred();
        assertThat(queued).hasSize(1);
    }

    @Test
    void aReadThatCannotBeSavedBecauseTheLibraryIsLockedStopsTheRunForLater() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L)));
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.of(FILE));
        when(reader.read(FILE)).thenReturn(usable());
        when(service.saveReference(read(70L), usable(), NOW)).thenReturn(SaveOutcome.BUSY);

        startAndRun();

        verify(service, never()).fitJobs(any());
        assertThat(messages()).containsExactly("Subtitle timing checks wait: a scan or an edit is using the library",
            FINISHED.formatted(0, 0, 0, 0));
        queued.clear();
        refresher.startIfDeferred();
        assertThat(queued).hasSize(1);
    }

    @Test
    void aReadThatFailsIsCountedNotStoredAndLeftAloneForSixHours() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L)));
        when(service.fitJobs(7L)).thenReturn(List.of());
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.of(FILE));
        when(reader.read(FILE)).thenThrow(new ReadException("ffprobe timed out reading the subtitle packets"));

        startAndRun();

        assertThat(messages()).containsExactly(
            "Could not read the embedded subtitles of film file 70: ffprobe timed out reading the subtitle packets",
            FINISHED.formatted(0, 0, 0, 1));
        verify(service, never()).saveReference(any(), any(), any());

        // Within the six hours the file is not asked for again; after them it is.
        queued.clear();
        now = NOW.plus(Duration.ofHours(5));
        startAndRun();
        verify(reader, times(1)).read(FILE);
        queued.clear();
        now = NOW.plus(Duration.ofHours(6));
        startAndRun();
        verify(reader, times(2)).read(FILE);
    }

    @Test
    void aFileThatReadsAgainAfterAFailureIsNoLongerLeftAlone() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L)));
        when(service.fitJobs(7L)).thenReturn(List.of());
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.of(FILE));
        when(reader.read(FILE)).thenThrow(new ReadException("x")).thenReturn(usable(), usable());
        when(service.saveReference(read(70L), usable(), NOW.plus(Duration.ofHours(7)))).thenReturn(SaveOutcome.SAVED);
        when(service.saveReference(read(70L), usable(), NOW.plus(Duration.ofHours(7).plusMinutes(1))))
            .thenReturn(SaveOutcome.SAVED);

        startAndRun();
        queued.clear();
        now = NOW.plus(Duration.ofHours(7));
        startAndRun();
        queued.clear();
        now = NOW.plus(Duration.ofHours(7).plusMinutes(1));
        startAndRun();

        verify(reader, times(3)).read(FILE);
    }

    @Test
    void aFilmFileThatCannotBeFoundIsAFailedReadAndTheReaderIsNotAsked() {
        when(service.dueFilmIds()).thenReturn(List.of(7L));
        when(service.readsFor(7L)).thenReturn(List.of(read(70L)));
        when(service.fitJobs(7L)).thenReturn(List.of());
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.empty());

        startAndRun();

        assertThat(messages()).containsExactly(
            "Could not read the embedded subtitles of film file 70: The film file cannot be found",
            FINISHED.formatted(0, 0, 0, 1));
        verifyNoInteractions(reader);
    }

    @Test
    void aFilmThatFailsIsCountedAndTheNextFilmIsStillDone() {
        when(service.dueFilmIds()).thenReturn(List.of(7L, 8L));
        when(service.readsFor(7L)).thenThrow(new IllegalStateException("boom"));
        when(service.readsFor(8L)).thenReturn(List.of());
        when(service.fitJobs(8L)).thenReturn(List.of(job(8L, 6L)));
        when(service.save(job(8L, 6L), TimingFit.fit(STARTS, TRACK), NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        assertThat(messages()).containsExactly("Could not check the subtitle timing of film 7: IllegalStateException",
            FINISHED.formatted(0, 1, 0, 1));
    }

    @Test
    void aRunThatCannotEvenListTheDueFilmsLogsAnErrorAndDoesNotThrow() {
        when(service.dueFilmIds()).thenThrow(new IllegalStateException("database is down"));

        startAndRun();

        assertThat(logged.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Subtitle timing checks failed");
            assertThat(event.getLevel().toString()).isEqualTo("ERROR");
        });
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aRequestDuringARunMakesItGoRoundOnceMoreAndSeveralMakeOneMore() {
        boolean[] asked = {false};
        when(service.dueFilmIds()).thenAnswer(call -> {
            if (!asked[0]) {
                asked[0] = true;
                // Three requests while the first pass is going: none starts a second run, all ask for one more pass.
                assertThat(refresher.startDue()).isFalse();
                assertThat(refresher.startDue()).isFalse();
                assertThat(refresher.startDue()).isFalse();
            }
            return List.of();
        });

        startAndRun();

        verify(service, times(2)).dueFilmIds();
        assertThat(queued).hasSize(1);
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void whileARunIsGoingARequestDoesNotStartAnother() {
        assertThat(refresher.startDue()).isTrue();

        assertThat(refresher.isRunning()).isTrue();
        assertThat(refresher.startDue()).isFalse();
        assertThat(queued).hasSize(1);
    }

    @Test
    void anExecutorThatRefusesTheRunGivesTheRunUpAndSaysSo() {
        SidecarTimingRefresher refusing = new SidecarTimingRefresher(service, reader, root, sessions, clock,
            task -> {
                throw new RejectedExecutionException("full");
            }, true);

        assertThatThrownBy(refusing::startDue).isInstanceOf(RejectedExecutionException.class);

        assertThat(refusing.isRunning()).isFalse();
    }

    @Test
    void whenItIsSwitchedOffNothingStartsAndStartUpSaysWhy() {
        SidecarTimingRefresher off = new SidecarTimingRefresher(service, reader, root, sessions, clock, queued::add,
            false);

        assertThat(off.startDue()).isFalse();
        off.startIfDeferred();
        off.onScanFinished(new ScanFinished("admin"));
        off.onStartup();

        assertThat(queued).isEmpty();
        assertThat(messages()).containsExactly(
            "Subtitle timing checks are off (cinefilm.subtitles.timing-check.enabled)");
        verifyNoInteractions(service, reader);
    }

    @Test
    void aScanAndStartUpAskingBeforeTheRunBeginsMakeOnePass() {
        refresher.onScanFinished(new ScanFinished("nightly"));
        assertThat(queued).hasSize(1);
        // The run is queued but not begun, so start-up joins it rather than starting a second, and says nothing.
        refresher.onStartup();
        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
        queued.get(0).run();
        verify(service, times(1)).dueFilmIds();
    }

    @Test
    void startupIsAnApplicationReadyListener() throws NoSuchMethodException {
        assertThat(SidecarTimingRefresher.class.getMethod("onStartup")
            .getAnnotation(org.springframework.context.event.EventListener.class).value())
            .containsExactly(ApplicationReadyEvent.class);
    }
}
