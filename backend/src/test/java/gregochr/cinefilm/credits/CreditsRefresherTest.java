package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsDetectionService.SaveOutcome;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.playback.PlaybackStateRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * The background credits run: one file at a time, nothing run while playback needs the machine (an encoder going, or
 * progress reported in the last five minutes: checked before each file and before every ffmpeg call), a failed file
 * left alone for six hours unless it changes, a request during a run making it go round once more, and a run that never
 * throws. Runs on an executor the test controls.
 */
@ExtendWith(MockitoExtension.class)
class CreditsRefresherTest {

    private static final Path FILE = Path.of("/library/Taken.m4v");
    private static final DueFile TAKEN = new DueFile(7L, "Taken.m4v", "1000:1", 6_000_000L, "Taken");
    private static final DueFile HEAT = new DueFile(8L, "Heat.m4v", "2000:1", 9_000_000L, "Heat");
    private static final CreditsResult FOUND = new CreditsResult(CreditsVerdict.FOUND, 3_723_000L, List.of("Crawl"));
    private static final CreditsResult NOT_FOUND = new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("None"));
    private static final Duration SIX_HOURS = Duration.ofHours(6);

    @Mock
    private CreditsDetectionService service;

    @Mock
    private CreditsFrameReader reader;

    @Mock
    private LibraryRoot root;

    @Mock
    private SessionManager sessions;

    @Mock
    private PlaybackStateRepository playback;

    @Captor
    private ArgumentCaptor<Runnable> checkpoint;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(CreditsRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Instant now = NOW;
    private boolean encoding;
    private Instant lastPlayed;
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
    private CreditsRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new CreditsRefresher(service, reader, root, sessions, playback, clock, queued::add, true);
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

    /** Starts a run and executes what was queued, as the executor's thread would. */
    private void startAndRun() {
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        queued.clear();
    }

    private void runAgain() {
        startAndRun();
    }

    private void onDisk(DueFile due, Path file) {
        when(root.resolveFile(due.path())).thenReturn(Optional.of(file));
    }

    private void detects(DueFile due, Path file, CreditsResult result) {
        onDisk(due, file);
        when(service.detect(eq(due), eq(file), any(Runnable.class))).thenReturn(result);
    }

    // ---- The normal run ------------------------------------------------------------------------------------------

    @Test
    void aDueFileIsAnalysedThenSavedAndTheResultIsLoggedWithTitleVerdictOffsetAndTime() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        onDisk(TAKEN, FILE);
        when(service.detect(eq(TAKEN), eq(FILE), any(Runnable.class))).thenAnswer(call -> {
            now = now.plusSeconds(75);
            return FOUND;
        });
        when(service.save(TAKEN, FOUND, NOW.plusSeconds(75))).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        assertThat(messages()).containsExactly("Credits of Taken: FOUND, at 1:02:03, took 75 s",
            "Credits detection finished: 1 files analysed, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aResultWithNoOffsetIsLoggedAsSuch() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        detects(TAKEN, FILE, NOT_FOUND);
        when(service.save(TAKEN, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        assertThat(messages()).containsExactly("Credits of Taken: NOT_FOUND, no offset, took 0 s",
            "Credits detection finished: 1 files analysed, 0 failed");
    }

    @Test
    void filesAreAnalysedOneAtATimeInTheOrderTheyAreDue() {
        when(service.dueFiles()).thenReturn(List.of(HEAT, TAKEN));
        detects(HEAT, Path.of("/library/Heat.m4v"), NOT_FOUND);
        detects(TAKEN, FILE, FOUND);
        when(service.save(HEAT, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);
        when(service.save(TAKEN, FOUND, NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        InOrder order = inOrder(service);
        order.verify(service).dueFiles();
        order.verify(service).detect(eq(HEAT), eq(Path.of("/library/Heat.m4v")), checkpoint.capture());
        order.verify(service).save(HEAT, NOT_FOUND, NOW);
        order.verify(service).detect(eq(TAKEN), eq(FILE), checkpoint.capture());
        order.verify(service).save(TAKEN, FOUND, NOW);
    }

    @Test
    void aFileThatChangedWhileItWasAnalysedIsNotStoredAndNotCountedAsFailedOrLeftAlone() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        detects(TAKEN, FILE, FOUND);
        when(service.save(TAKEN, FOUND, NOW)).thenReturn(SaveOutcome.DROPPED);

        startAndRun();

        assertThat(messages()).containsExactly("Credits of Taken not stored: the file changed while it was analysed",
            "Credits detection finished: 1 files analysed, 0 failed");
        runAgain();
        verify(service, times(2)).detect(eq(TAKEN), eq(FILE), checkpoint.capture());
    }

    @Test
    void nothingDueMeansNothingAnalysed() {
        when(service.dueFiles()).thenReturn(List.of());

        startAndRun();

        assertThat(messages()).containsExactly("Credits detection finished: 0 files analysed, 0 failed");
        verifyNoInteractions(reader, root, sessions, playback);
    }

    // ---- Playback comes first ------------------------------------------------------------------------------------

    @Test
    void nothingIsAnalysedWhileAFilmIsBeingTranscodedAndTheRunIsLeftForLater() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        when(sessions.isEncoding()).thenReturn(true);

        startAndRun();

        verify(service).dueFiles();
        verifyNoMoreInteractions(service);
        assertThat(messages()).containsExactly("Credits detection waits: a film is being transcoded",
            "Credits detection finished: 0 files analysed, 0 failed");

        // The hourly nudge starts a run because the last one waited; once playback is done it works.
        when(sessions.isEncoding()).thenReturn(false);
        detects(TAKEN, FILE, NOT_FOUND);
        when(service.save(TAKEN, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);
        refresher.startIfDeferred();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(service).detect(eq(TAKEN), eq(FILE), checkpoint.capture());
        queued.clear();
        refresher.startIfDeferred();
        assertThat(queued).isEmpty();
    }

    @Test
    void nothingIsAnalysedWhileProgressWasReportedInTheLastFiveMinutes() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        when(playback.findLatestLastPlayedAt()).thenReturn(NOW.minus(Duration.ofMinutes(5)).plusMillis(1));

        startAndRun();

        assertThat(messages()).containsExactly("Credits detection waits: a film was played in the last 5 minutes",
            "Credits detection finished: 0 files analysed, 0 failed");
        verify(service).dueFiles();
        verifyNoMoreInteractions(service);
    }

    @Test
    void progressReportedExactlyFiveMinutesAgoNoLongerHoldsTheRunBack() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        when(playback.findLatestLastPlayedAt()).thenReturn(NOW.minus(Duration.ofMinutes(5)));
        detects(TAKEN, FILE, NOT_FOUND);
        when(service.save(TAKEN, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        verify(service).save(TAKEN, NOT_FOUND, NOW);
    }

    @Test
    void aFilmThatWasNeverPlayedDoesNotHoldTheRunBack() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        when(playback.findLatestLastPlayedAt()).thenReturn(null);
        detects(TAKEN, FILE, NOT_FOUND);
        when(service.save(TAKEN, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        verify(service).save(TAKEN, NOT_FOUND, NOW);
    }

    @Test
    void playbackIsCheckedBeforeEachFileSoAFilmStartedMeanwhileStopsTheRunAfterTheFirst() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN, HEAT));
        detects(TAKEN, FILE, FOUND);
        when(sessions.isEncoding()).thenAnswer(call -> encoding);
        when(service.save(TAKEN, FOUND, NOW)).thenAnswer(call -> {
            encoding = true;
            return SaveOutcome.SAVED;
        });

        startAndRun();

        verify(service, never()).detect(eq(HEAT), any(Path.class), any(Runnable.class));
        assertThat(messages()).containsExactly("Credits of Taken: FOUND, at 1:02:03, took 0 s",
            "Credits detection waits: a film is being transcoded",
            "Credits detection finished: 1 files analysed, 0 failed");
    }

    @Test
    void playbackIsCheckedBeforeEveryFfmpegCallThroughTheCheckpointGivenToTheAnalysis() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        onDisk(TAKEN, FILE);
        when(sessions.isEncoding()).thenAnswer(call -> encoding);
        when(service.detect(eq(TAKEN), eq(FILE), any(Runnable.class))).thenAnswer(call -> {
            Runnable check = call.getArgument(2);
            check.run();
            // A film starts transcoding in the middle of the file: the next ffmpeg call must not happen.
            encoding = true;
            check.run();
            return FOUND;
        });

        startAndRun();

        verify(service).detect(eq(TAKEN), eq(FILE), checkpoint.capture());
        assertThat(messages()).containsExactly("Credits detection waits: a film is being transcoded",
            "Credits detection finished: 0 files analysed, 0 failed");
    }

    @Test
    void progressReportedInTheMiddleOfAFileStopsItWithoutMarkingItFailedAndItIsAnalysedAgainLater() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        onDisk(TAKEN, FILE);
        when(playback.findLatestLastPlayedAt()).thenAnswer(call -> lastPlayed);
        when(service.detect(eq(TAKEN), eq(FILE), any(Runnable.class))).thenAnswer(call -> {
            lastPlayed = NOW;
            Runnable check = call.getArgument(2);
            check.run();
            return FOUND;
        }).thenReturn(NOT_FOUND);
        when(service.save(TAKEN, NOT_FOUND, NOW.plus(Duration.ofMinutes(6)))).thenReturn(SaveOutcome.SAVED);

        startAndRun();
        assertThat(messages()).contains("Credits detection waits: a film was played in the last 5 minutes");
        now = now.plus(Duration.ofMinutes(6));
        refresher.startIfDeferred();
        queued.get(0).run();

        verify(service).save(TAKEN, NOT_FOUND, NOW.plus(Duration.ofMinutes(6)));
    }

    @Test
    void aScanOrEditHoldingTheLibraryLockWhenTheResultIsSavedStopsTheRunForLater() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN, HEAT)).thenReturn(List.of(TAKEN));
        detects(TAKEN, FILE, FOUND);
        when(service.save(TAKEN, FOUND, NOW)).thenReturn(SaveOutcome.BUSY).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        verify(service, never()).detect(eq(HEAT), any(Path.class), any(Runnable.class));
        assertThat(messages()).containsExactly("Credits detection waits: a scan or an edit is using the library",
            "Credits detection finished: 0 files analysed, 0 failed");
        // Not a failure: the next run analyses the same file again at once.
        refresher.startIfDeferred();
        queued.get(0).run();
        verify(service, times(2)).save(TAKEN, FOUND, NOW);
    }

    // ---- Failures ------------------------------------------------------------------------------------------------

    private void failsToRead(DueFile due, Path file) {
        onDisk(due, file);
        when(service.detect(eq(due), eq(file), any(Runnable.class)))
            .thenThrow(new CreditsFrameReader.ReadException("ffmpeg timed out reading the frame at 5400.000"));
    }

    @Test
    void aFileThatFailsIsCountedNotStoredAndLeftAloneForSixHours() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        failsToRead(TAKEN, FILE);

        startAndRun();

        assertThat(messages()).containsExactly(
            "Could not analyse the credits of Taken (file 7): ffmpeg timed out reading the frame at 5400.000",
            "Credits detection finished: 0 files analysed, 1 failed");

        // Just inside the back-off nothing is tried; at exactly six hours it is tried again.
        now = NOW.plus(SIX_HOURS).minusMillis(1);
        runAgain();
        verify(service, times(1)).detect(eq(TAKEN), eq(FILE), checkpoint.capture());
        now = NOW.plus(SIX_HOURS);
        runAgain();
        verify(service, times(2)).detect(eq(TAKEN), eq(FILE), checkpoint.capture());
    }

    @Test
    void aFileThatChangedSinceItFailedIsAnalysedAtOnce() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        failsToRead(TAKEN, FILE);
        startAndRun();

        DueFile changed = new DueFile(7L, "Taken.m4v", "1000:2", 6_000_000L, "Taken");
        when(service.dueFiles()).thenReturn(List.of(changed));
        detects(changed, FILE, NOT_FOUND);
        when(service.save(changed, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);
        runAgain();

        verify(service).save(changed, NOT_FOUND, NOW);
    }

    @Test
    void aFileThatCannotBeFoundOnDiskIsAFailureAndTheAnalysisIsNotAsked() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        when(root.resolveFile("Taken.m4v")).thenReturn(Optional.empty());

        startAndRun();

        assertThat(messages()).containsExactly(
            "Could not analyse the credits of Taken (file 7): The film file cannot be found",
            "Credits detection finished: 0 files analysed, 1 failed");
        verify(service).dueFiles();
        verifyNoMoreInteractions(service);
    }

    @Test
    void aFailureWithNoMessageIsLoggedByItsClassName() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN));
        onDisk(TAKEN, FILE);
        when(service.detect(eq(TAKEN), eq(FILE), any(Runnable.class))).thenThrow(new IllegalArgumentException());

        startAndRun();

        assertThat(messages()).contains(
            "Could not analyse the credits of Taken (file 7): IllegalArgumentException");
    }

    @Test
    void aFileThatFailsDoesNotStopTheNextOne() {
        when(service.dueFiles()).thenReturn(List.of(TAKEN, HEAT));
        failsToRead(TAKEN, FILE);
        detects(HEAT, Path.of("/library/Heat.m4v"), NOT_FOUND);
        when(service.save(HEAT, NOT_FOUND, NOW)).thenReturn(SaveOutcome.SAVED);

        startAndRun();

        verify(service).save(HEAT, NOT_FOUND, NOW);
        assertThat(messages()).endsWith("Credits detection finished: 1 files analysed, 1 failed");
    }

    @Test
    void aRunThatCannotEvenListTheDueFilesLogsAnErrorAndDoesNotThrow() {
        when(service.dueFiles()).thenThrow(new IllegalStateException("database is down"));

        startAndRun();

        assertThat(logged.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Credits detection failed");
            assertThat(event.getLevel().toString()).isEqualTo("ERROR");
        });
        assertThat(refresher.isRunning()).isFalse();
    }

    // ---- Triggers and the run's life -----------------------------------------------------------------------------

    @Test
    void aRequestDuringARunMakesItGoRoundOnceMoreAndSeveralMakeOneMore() {
        boolean[] asked = {false};
        when(service.dueFiles()).thenAnswer(call -> {
            if (!asked[0]) {
                asked[0] = true;
                // Three requests (a scan finishing, say) while the first pass is going: none starts a second run,
                // all ask for one more pass.
                assertThat(refresher.startDue()).isFalse();
                refresher.onScanFinished(new ScanFinished("nightly"));
                assertThat(refresher.startDue()).isFalse();
            }
            return List.of();
        });

        startAndRun();

        verify(service, times(2)).dueFiles();
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
        CreditsRefresher refusing = new CreditsRefresher(service, reader, root, sessions, playback, clock,
            task -> {
                throw new RejectedExecutionException("full");
            }, true);

        assertThatThrownBy(refusing::startDue).isInstanceOf(RejectedExecutionException.class);

        assertThat(refusing.isRunning()).isFalse();
    }

    @Test
    void whenItIsSwitchedOffNothingStartsAndStartUpSaysWhy() {
        CreditsRefresher off = new CreditsRefresher(service, reader, root, sessions, playback, clock, queued::add,
            false);

        assertThat(off.startDue()).isFalse();
        off.startIfDeferred();
        off.onScanFinished(new ScanFinished("admin"));
        off.onStartup();

        assertThat(queued).isEmpty();
        assertThat(messages()).containsExactly("Credits detection is off (cinefilm.credits.enabled)");
        verifyNoInteractions(service, reader);
    }

    @Test
    void startUpClearsWhatAKilledProcessLeftBehindThenStartsARun() {
        refresher.onStartup();

        verify(reader).deleteLeftovers();
        assertThat(queued).hasSize(1);
    }

    @Test
    void aScanAndStartUpAskingBeforeTheRunBeginsMakeOnePass() {
        when(service.dueFiles()).thenReturn(List.of());
        refresher.onScanFinished(new ScanFinished("nightly"));
        assertThat(queued).hasSize(1);
        // The run is queued but not begun, so start-up joins it rather than starting a second.
        refresher.onStartup();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(service, times(1)).dueFiles();
    }

    @Test
    void startupIsAnApplicationReadyListener() throws NoSuchMethodException {
        assertThat(CreditsRefresher.class.getMethod("onStartup")
            .getAnnotation(org.springframework.context.event.EventListener.class).value())
            .containsExactly(ApplicationReadyEvent.class);
    }
}
