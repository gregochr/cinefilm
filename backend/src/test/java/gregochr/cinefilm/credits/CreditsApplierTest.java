package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static gregochr.cinefilm.credits.CreditsAutoTestData.MANIFEST_DATE;
import static gregochr.cinefilm.credits.CreditsAutoTestData.crawl;
import static gregochr.cinefilm.credits.CreditsAutoTestData.denyFile;
import static gregochr.cinefilm.credits.CreditsAutoTestData.tmdbOn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.credits.CreditsApplier.PassResult;
import gregochr.cinefilm.credits.learn.Side;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.EditionFilmRow;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.library.EditionsArranged;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.library.ScanScheduler;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.plex.PlexCreditsImportFinished;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.search.LibraryChanged;
import gregochr.cinefilm.tmdb.Sleeper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;

/** One pass of the credits apply: the lock wait, what is written, and what is counted. */
@ExtendWith(MockitoExtension.class)
class CreditsApplierTest {

    private static final long MODIFIED = 1_700_000_000_000L;
    private static final String FINGERPRINT = "4000000000:" + MODIFIED;

    @Mock
    private EditionRepository editions;
    @Mock
    private PlexCreditsMarkerRepository markers;
    @Mock
    private PlaybackStateRepository states;
    @Mock
    private PlatformTransactionManager transactions;
    @Mock
    private CreditsDenyFileReader denyReader;
    @Mock
    private FilmRepository films;
    @Mock
    private CreditsDetectionRepository detections;

    private boolean autoEnabled;
    private boolean autoCards = true;
    private CreditsDenyListKeeper keeper;
    private final Logger applierLog = (Logger) LoggerFactory.getLogger(CreditsApplier.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final LibraryLock lock = new LibraryLock();
    private final List<Duration> sleeps = new ArrayList<>();
    private Sleeper sleeper = sleeps::add;

    private Executor executor = Runnable::run;

    @BeforeEach
    void setUp() {
        keeper = new CreditsDenyListKeeper(denyReader);
        logged.start();
        applierLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        applierLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private CreditsApplier applier() {
        return applier(true);
    }

    private CreditsApplier applier(boolean enabled) {
        return new CreditsApplier(editions, markers, states, lock, d -> sleeper.sleep(d), transactions, executor,
            keeper, new CreditsAutoReader(editions, films, detections, autoEnabled, autoCards), enabled);
    }

    /** An edition of one 100-minute file (id = edition id = file id) with its current marker. */
    private static Edition edition(long id, Long marker, CreditsMarkerSource source) {
        Edition edition = new Edition();
        edition.setId(id);
        edition.setCreditsMarkerMs(marker);
        edition.setCreditsMarkerSource(source);
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setEdition(edition);
        file.setKind(MediaKind.ORIGINAL);
        file.setDurationMs(6_000_000L);
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(Instant.ofEpochMilli(MODIFIED));
        edition.getMediaFiles().add(file);
        return edition;
    }

    private static PlexCreditsMarker row(long fileId, String fingerprint, long startMs) {
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(fileId);
        row.setFingerprint(fingerprint);
        row.setStartMs(startMs);
        row.setImportedAt(NOW);
        return row;
    }

    /** The database holds these editions; the pass asks for their last parts' rows by exactly these file ids. */
    private void library(List<Edition> all, List<Long> lastFileIds, PlexCreditsMarker... rows) {
        List<Long> ids = all.stream().map(Edition::getId).toList();
        when(editions.findAllIds()).thenReturn(ids);
        when(editions.findWithFilesByIdIn(ids)).thenReturn(all);
        when(markers.findAllById(lastFileIds)).thenReturn(List.of(rows));
    }

    /** Adds an original part to an edition (appended, so the caller controls the order in the list). */
    private static void part(Edition edition, long fileId, int partNumber, long durationMs) {
        MediaFile file = new MediaFile();
        file.setId(fileId);
        file.setEdition(edition);
        file.setKind(MediaKind.ORIGINAL);
        file.setPartNumber(partNumber);
        file.setDurationMs(durationMs);
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(Instant.ofEpochMilli(MODIFIED));
        edition.getMediaFiles().add(file);
    }

    @Test
    void aBusyLockIsWaitedForThenThePassRunsOnceItIsFree() {
        lock.tryAcquire(LibraryLock.SCAN);
        sleeper = d -> {
            sleeps.add(d);
            if (sleeps.size() == 3) {
                lock.release();
            }
        };
        when(editions.findAllIds()).thenReturn(List.of());

        PassResult result = applier().runPass();

        assertThat(result.lockTaken()).isTrue();
        assertThat(sleeps).hasSize(3).containsOnly(CreditsApplier.LOCK_WAIT);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aLockThatStaysBusyIsGivenUpOnAfterTheBoundedAttemptsAndNothingIsRead() {
        lock.tryAcquire(LibraryLock.SCAN);

        PassResult result = applier().runPass();

        assertThat(result.lockTaken()).isFalse();
        assertThat(sleeps).hasSize(CreditsApplier.LOCK_ATTEMPTS);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        verifyNoMoreInteractions(editions, markers, states);
    }

    @Test
    void anInterruptedWaitGivesUpAfterOneSleepAndKeepsTheInterruptFlag() {
        lock.tryAcquire(LibraryLock.SCAN);
        sleeper = d -> {
            sleeps.add(d);
            throw new InterruptedException("stop");
        };

        try {
            assertThat(applier().runPass().lockTaken()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(sleeps).hasSize(1);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        verifyNoInteractions(editions, markers, states);
    }

    @Test
    void theLockIsReleasedEvenWhenAReadFails() {
        when(editions.findAllIds()).thenThrow(new IllegalStateException("db down"));
        CreditsApplier applier = applier();

        assertThatThrownBy(applier::runPass).hasMessage("db down");

        assertThat(lock.holder()).isNull();
    }

    @Test
    void onlyEditionsWhoseMarkerOrSourceChangesAreWritten() {
        Edition setPlex = edition(1, null, null);
        Edition staleCleared = edition(2, 5_000_000L, CreditsMarkerSource.AUTO);
        Edition unchanged = edition(3, 5_000_000L, CreditsMarkerSource.PLEX);
        Edition sourceOnly = edition(4, 5_000_000L, null);
        Edition untouchedNone = edition(5, null, null);
        library(List.of(setPlex, staleCleared, unchanged, sourceOnly, untouchedNone), List.of(1L, 2L, 3L, 4L, 5L),
            row(1, FINGERPRINT, 5_000_000), row(2, "other", 5_000_000), row(3, FINGERPRINT, 5_000_000),
            row(4, FINGERPRINT, 5_000_000));
        when(states.countInProgressAtOrPastMarker(List.of(1L, 2L, 4L))).thenReturn(0L);

        PassResult result = applier().runPass();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Edition>> saved = ArgumentCaptor.forClass(List.class);
        verify(editions).saveAll(saved.capture());
        assertThat(saved.getValue()).containsExactly(setPlex, staleCleared, sourceOnly);
        assertThat(setPlex.getCreditsMarkerMs()).isEqualTo(5_000_000L);
        assertThat(setPlex.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(staleCleared.getCreditsMarkerMs()).isNull();
        assertThat(staleCleared.getCreditsMarkerSource()).isNull();
        assertThat(sourceOnly.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(unchanged.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(result).isEqualTo(new PassResult(true, 3, 0, 3, 1, 0, 2, 0));
    }

    @Test
    void anOwnerMarkerIsSkippedAndCountedWhateverPlexSays() {
        Edition owner = edition(1, 5_500_000L, CreditsMarkerSource.OWNER);
        library(List.of(owner), List.of(1L), row(1, FINGERPRINT, 5_000_000));

        PassResult result = applier().runPass();

        verify(editions).findAllIds();
        verify(editions).findWithFilesByIdIn(List.of(1L));
        verifyNoMoreInteractions(editions);
        assertThat(owner.getCreditsMarkerMs()).isEqualTo(5_500_000L);
        assertThat(owner.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.OWNER);
        assertThat(result).isEqualTo(new PassResult(true, 0, 0, 0, 0, 1, 1, 0));
    }

    @Test
    void anEditionWithNoOriginalFileHasNoRowLookedUpAndKeepsNone() {
        Edition bare = new Edition();
        bare.setId(1L);
        when(editions.findAllIds()).thenReturn(List.of(1L));
        when(editions.findWithFilesByIdIn(List.of(1L))).thenReturn(List.of(bare));

        PassResult result = applier().runPass();

        verifyNoMoreInteractions(markers);
        assertThat(result).isEqualTo(new PassResult(true, 0, 0, 0, 0, 0, 1, 0));
    }

    @Test
    void theInProgressCountIsTheStatesAtOrPastTheChangedEditionsNewMarkers() {
        Edition setPlex = edition(1, null, null);
        Edition other = edition(2, null, null);
        library(List.of(setPlex, other), List.of(1L, 2L), row(1, FINGERPRINT, 5_000_000));
        when(states.countInProgressAtOrPastMarker(List.of(1L))).thenReturn(4L);

        PassResult result = applier().runPass();

        assertThat(result.inProgressPastMarker()).isEqualTo(4L);
        assertThat(result.plexSet()).isEqualTo(1);
    }

    @Test
    void aTwoPartEditionIsAppliedFromTheLastPartsRowAtItsEditionTime() {
        Edition twoParts = new Edition();
        twoParts.setId(7L);
        part(twoParts, 11, 2, 2_000_000L);
        part(twoParts, 10, 1, 4_000_000L);
        library(List.of(twoParts), List.of(11L), row(11, FINGERPRINT, 1_500_000));
        when(states.countInProgressAtOrPastMarker(List.of(7L))).thenReturn(0L);

        PassResult result = applier().runPass();

        assertThat(twoParts.getCreditsMarkerMs()).isEqualTo(5_500_000L);
        assertThat(twoParts.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(result).isEqualTo(new PassResult(true, 1, 0, 1, 0, 0, 0, 0));
    }

    @Test
    void editionsAreReadInBatchesWithOneReadEachNotOnePerEdition() {
        int size = CreditsApplier.BATCH_SIZE;
        List<Long> ids = LongStream.rangeClosed(1, size + 1).boxed().toList();
        when(editions.findAllIds()).thenReturn(ids);
        when(editions.findWithFilesByIdIn(ids.subList(0, size))).thenReturn(List.of());
        when(editions.findWithFilesByIdIn(ids.subList(size, size + 1))).thenReturn(List.of());

        PassResult result = applier().runPass();

        verify(editions).findWithFilesByIdIn(ids.subList(0, size));
        verify(editions).findWithFilesByIdIn(ids.subList(size, size + 1));
        assertThat(result.unchanged()).isEqualTo(size + 1);
    }

    /** Every edition the pass reads, in order: each is a fresh edition 1 with no marker. */
    private final List<Edition> served = new ArrayList<>();

    /**
     * One edition with a Plex row that matches its file. Each read serves a fresh unmarked edition, so what each pass
     * did shows in its edition's state; {@code onRead} gets the 1-based number of the read, before it is answered.
     */
    private void markedLibrary(IntConsumer onRead) {
        when(editions.findAllIds()).thenReturn(List.of(1L));
        when(editions.findWithFilesByIdIn(List.of(1L))).thenAnswer(invocation -> {
            Edition fresh = edition(1, null, null);
            served.add(fresh);
            onRead.accept(served.size());
            return List.of(fresh);
        });
        when(markers.findAllById(List.of(1L))).thenReturn(List.of(row(1, FINGERPRINT, 5_000_000)));
    }

    private void markedLibrary() {
        markedLibrary(n -> { });
    }

    private static void assertApplied(Edition edition) {
        assertThat(edition.getCreditsMarkerMs()).isEqualTo(5_000_000L);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
    }

    /** The pass number {@code count} editions were served for, all applied and saved. */
    private void assertServedAndApplied(int count) {
        assertThat(served).hasSize(count);
        served.forEach(CreditsApplierTest::assertApplied);
        if (count > 0) {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Edition>> saved = ArgumentCaptor.forClass(List.class);
            verify(editions, atLeastOnce()).saveAll(saved.capture());
            assertThat(saved.getAllValues().stream().flatMap(List::stream)).containsExactlyElementsOf(served);
        }
    }

    @Test
    void aRequestAppliesTheMarkerOnTheExecutorNotInline() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        executor = queued::set;
        markedLibrary();

        applier().requestPass();

        assertThat(served).isEmpty();
        queued.get().run();
        assertServedAndApplied(1);
    }

    @Test
    void aRefusedPassAppliesNothingAndDoesNotSpinAndTheNextRequestAppliesTheMarker() {
        AtomicInteger executed = new AtomicInteger();
        executor = command -> {
            executed.incrementAndGet();
            command.run();
        };
        lock.tryAcquire(LibraryLock.SCAN);
        markedLibrary();
        CreditsApplier applier = applier();

        applier.requestPass();

        assertThat(executed).hasValue(1);
        assertThat(sleeps).hasSize(CreditsApplier.LOCK_ATTEMPTS);
        assertThat(served).isEmpty();
        lock.release();

        applier.requestPass();

        assertThat(executed).hasValue(2);
        assertServedAndApplied(1);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aRequestDuringAPassGivesExactlyOneMorePassHoweverManyArrive() {
        CreditsApplier applier = applier();
        markedLibrary(n -> {
            if (n == 1) {
                applier.requestPass();
                applier.requestPass();
                applier.requestPass();
            }
        });

        applier.requestPass();

        assertServedAndApplied(2);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aRequestAfterAFinishedPassGivesANewPass() {
        markedLibrary();
        CreditsApplier applier = applier();

        applier.requestPass();
        applier.requestPass();

        assertServedAndApplied(2);
    }

    @Test
    void aPassThatFailsIsOwedSoTheNudgeAppliesTheMarkerAndASecondNudgeDoesNothing() {
        markedLibrary(n -> {
            if (n == 1) {
                throw new IllegalStateException("db down");
            }
        });
        CreditsApplier applier = applier();

        applier.requestPass();

        assertThat(served).hasSize(1);
        assertThat(served.get(0).getCreditsMarkerMs()).as("the failed pass applied nothing").isNull();
        assertThat(lock.holder()).isNull();

        applier.retryIfRefused();
        applier.retryIfRefused();

        assertThat(served).hasSize(2);
        assertApplied(served.get(1));
    }

    @Test
    void aRequestAfterARefusalButBeforeTheWorkerEndsIsNotLost() {
        lock.tryAcquire(LibraryLock.SCAN);
        CreditsApplier applier = applier();
        AtomicInteger hooks = new AtomicInteger();
        applier.beforeWorkerEnds = () -> {
            if (hooks.incrementAndGet() == 1) {
                lock.release();
                applier.requestPass();
            }
        };
        markedLibrary();

        applier.requestPass();

        assertServedAndApplied(1);
        assertThat(hooks).hasValue(2);
    }

    @Test
    void aRefusedExecutorFreesTheWorkerSoALaterRequestCanStart() {
        AtomicInteger offered = new AtomicInteger();
        executor = command -> {
            if (offered.incrementAndGet() == 1) {
                throw new RejectedExecutionException("closed");
            }
            command.run();
        };
        markedLibrary();
        CreditsApplier applier = applier();

        assertThatThrownBy(applier::requestPass).isInstanceOf(RejectedExecutionException.class);
        assertThat(served).isEmpty();

        applier.requestPass();

        assertServedAndApplied(1);
    }

    @Test
    void eachTriggerAppliesTheMarker() {
        markedLibrary();
        CreditsApplier applier = applier();

        applier.onStartup();
        applier.onScanFinished(new ScanFinished("nightly"));
        applier.onPlexImportFinished(new PlexImportFinished());
        applier.onPlexCreditsImportFinished(new PlexCreditsImportFinished());
        applier.onEditionsArranged(new EditionsArranged());

        assertServedAndApplied(5);
    }

    @Test
    void aTriggerQueuesTheWorkAndNeverRunsAPassInline() {
        AtomicInteger queued = new AtomicInteger();
        executor = command -> queued.incrementAndGet();
        CreditsApplier applier = applier();

        applier.onScanFinished(new ScanFinished("admin"));
        applier.onEditionsArranged(new EditionsArranged());

        assertThat(queued).as("the second request found the worker already taken").hasValue(1);
        verifyNoInteractions(editions, markers, states);
    }

    @Test
    void withThePropertyOffNoTriggerNorNudgeStartsAnything() {
        AtomicInteger queued = new AtomicInteger();
        executor = command -> queued.incrementAndGet();
        CreditsApplier applier = applier(false);

        applier.onStartup();
        applier.onScanFinished(new ScanFinished("startup"));
        applier.onPlexImportFinished(new PlexImportFinished());
        applier.onPlexCreditsImportFinished(new PlexCreditsImportFinished());
        applier.onEditionsArranged(new EditionsArranged());
        applier.requestPass();
        applier.retryIfRefused();

        assertThat(queued).hasValue(0);
        verifyNoInteractions(editions, markers, states);
    }

    @Test
    void withThePropertyOffThePassItselfStillAppliesTheMarker() {
        markedLibrary();

        PassResult result = applier(false).runPass();

        assertThat(result.lockTaken()).isTrue();
        assertServedAndApplied(1);
    }

    @Test
    void aRequestThatArrivesWhileThePassWaitsForTheLockGivesOneMorePassWhenItGivesUp() {
        lock.tryAcquire(LibraryLock.SCAN);
        CreditsApplier applier = applier();
        AtomicInteger sleepsSeen = new AtomicInteger();
        sleeper = d -> {
            int n = sleepsSeen.incrementAndGet();
            if (n == 1) {
                applier.requestPass();
            }
            if (n == CreditsApplier.LOCK_ATTEMPTS) {
                lock.release();
            }
        };
        markedLibrary();

        applier.requestPass();

        assertServedAndApplied(1);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aRequestDuringTheWaitIsOnlyHonouredOnce() {
        lock.tryAcquire(LibraryLock.SCAN);
        CreditsApplier applier = applier();
        AtomicInteger sleepsSeen = new AtomicInteger();
        sleeper = d -> {
            if (sleepsSeen.incrementAndGet() == 1) {
                applier.requestPass();
            }
        };

        applier.requestPass();

        assertThat(sleepsSeen).hasValue(2 * CreditsApplier.LOCK_ATTEMPTS);
        verifyNoInteractions(editions);
    }

    @Test
    void theNudgeAppliesTheMarkerOfARefusedPassOnceAndNotAgain() {
        AtomicInteger executed = new AtomicInteger();
        executor = command -> {
            executed.incrementAndGet();
            command.run();
        };
        lock.tryAcquire(LibraryLock.SCAN);
        CreditsApplier applier = applier();
        applier.requestPass();
        lock.release();
        markedLibrary();

        applier.retryIfRefused();
        applier.retryIfRefused();

        assertThat(executed).hasValue(2);
        assertServedAndApplied(1);
    }

    @Test
    void theNudgeStartsNothingWhenNoPassWasRefused() {
        AtomicInteger queued = new AtomicInteger();
        executor = command -> {
            queued.incrementAndGet();
            command.run();
        };
        markedLibrary();
        CreditsApplier applier = applier();

        applier.retryIfRefused();
        assertThat(queued).hasValue(0);

        applier.requestPass();
        applier.retryIfRefused();

        assertThat(queued).as("only the request itself").hasValue(1);
        assertServedAndApplied(1);
    }

    @Test
    void aNudgeDuringAPassAfterAnEarlierRefusalDoesNotGiveASecondPass() {
        lock.tryAcquire(LibraryLock.SCAN);
        CreditsApplier applier = applier();
        applier.requestPass();
        lock.release();
        markedLibrary(n -> applier.retryIfRefused());

        applier.requestPass();

        assertServedAndApplied(1);
    }

    @Test
    void theTriggersAreEventListenersAndStartUpListensForApplicationReady() throws NoSuchMethodException {
        assertThat(CreditsApplier.class.getMethod("onStartup").getAnnotation(EventListener.class).value())
            .containsExactly(ApplicationReadyEvent.class);
        assertThat(CreditsApplier.class.getMethod("onScanFinished", ScanFinished.class)
            .isAnnotationPresent(EventListener.class)).isTrue();
        assertThat(CreditsApplier.class.getMethod("onPlexImportFinished", PlexImportFinished.class)
            .isAnnotationPresent(EventListener.class)).isTrue();
        assertThat(CreditsApplier.class.getMethod("onPlexCreditsImportFinished", PlexCreditsImportFinished.class)
            .isAnnotationPresent(EventListener.class)).isTrue();
        assertThat(CreditsApplier.class.getMethod("onEditionsArranged", EditionsArranged.class)
            .isAnnotationPresent(EventListener.class)).isTrue();
    }

    @Test
    void theStartupListenerRunsAfterTheScanSchedulersSoTheFirstScanTakesTheLockFirst() throws NoSuchMethodException {
        int credits = CreditsApplier.class.getMethod("onStartup").getAnnotation(Order.class).value();
        int scan = ScanScheduler.class.getMethod("onStartup").getAnnotation(Order.class).value();

        assertThat(credits).isEqualTo(CreditsApplier.STARTUP_ORDER);
        assertThat(scan).isLessThan(credits);
    }

    @Test
    void noMethodListensForLibraryChanged() {
        assertThat(Arrays.stream(CreditsApplier.class.getDeclaredMethods())
            .flatMap(method -> Arrays.stream(method.getParameterTypes())))
            .doesNotContain(LibraryChanged.class);
        assertThat(Arrays.stream(CreditsApplier.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(EventListener.class))
            .filter(java.util.Objects::nonNull)
            .flatMap(listener -> java.util.stream.Stream.concat(Arrays.stream(listener.value()),
                Arrays.stream(listener.classes()))))
            .doesNotContain(LibraryChanged.class);
    }

    // ---- AUTO: the deny list in force, the switch, and the log ----

    private static final String PASS_LINE = "Credits apply pass: 0 with a Plex marker, %d with an automatic marker, "
        + "%d changed, %d cleared, 0 owner markers kept, %d unchanged; 0 saved positions in progress are at or past "
        + "their new marker";

    private static String passLine(int auto, int changed, int cleared, int unchanged) {
        return PASS_LINE.formatted(auto, changed, cleared, unchanged);
    }

    /** A film row for edition {@code id} (its film is {@code id + 100}) matched to a development-side TMDB id. */
    private static EditionFilmRow filmRow(long id) {
        return new EditionFilmRow(id, id + 100, tmdbOn(Side.DEV, (int) id), "Film " + id, 2000, null, null);
    }

    /**
     * The database holds these editions, each with a version 2 crawl at 5,000,000 ms of its 100-minute file; the
     * pass reads their detection rows and film rows (only when the list and the switch let step 3 apply).
     */
    private void autoLibrary(Edition... all) {
        List<Long> ids = Arrays.stream(all).map(Edition::getId).toList();
        library(List.of(all), ids);
        when(detections.findAllById(ids)).thenReturn(ids.stream().map(id -> crawl(id, FINGERPRINT, 5_000_000))
            .toList());
        when(editions.findFilmRowsByIdIn(ids)).thenReturn(ids.stream().map(CreditsApplierTest::filmRow).toList());
    }

    @Test
    void anAutoMarkerIsSetTwentySecondsAfterTheDetectionAndASecondPassStillLogsTheFullAutoCount() {
        autoEnabled = true;
        Edition edition = edition(1, null, null);
        autoLibrary(edition);
        when(denyReader.read()).thenReturn(Optional.of(denyFile("file 999")));
        CreditsApplier applier = applier();

        PassResult first = applier.runPass();
        PassResult second = applier.runPass();

        assertThat(edition.getCreditsMarkerMs()).isEqualTo(5_020_000L);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.AUTO);
        assertThat(first).isEqualTo(new PassResult(true, 0, 1, 1, 0, 0, 0, 0));
        assertThat(second).isEqualTo(new PassResult(true, 0, 1, 0, 0, 0, 1, 0));
        assertThat(messages()).containsExactly(
            "Credits deny file: read (manifest " + MANIFEST_DATE + ", 1 entries)", passLine(1, 1, 0, 0),
            "Credits deny file: read (manifest " + MANIFEST_DATE + ", 1 entries)", passLine(1, 0, 0, 1));
    }

    @Test
    void theLogCarriesTheHeadersDateAndCountButNoEntryAndNoId() {
        autoEnabled = true;
        when(editions.findAllIds()).thenReturn(List.of());
        when(denyReader.read()).thenReturn(Optional.of(
            denyFile("unit ctmdb:777777", "file 424242", "edition 313131", "film 535353")));

        applier().runPass();

        assertThat(messages()).containsExactly(
            "Credits deny file: read (manifest " + MANIFEST_DATE + ", 4 entries)", passLine(0, 0, 0, 0));
    }

    @Test
    void whenTheSwitchIsOffTheFileIsStillReadAndKeptInForceAndTheLogSaysWhyAutoIsOff() {
        when(editions.findAllIds()).thenReturn(List.of());
        when(denyReader.read()).thenReturn(Optional.of(denyFile("file 999")));

        applier().runPass();

        assertThat(messages()).containsExactly(
            "Credits deny file: read (manifest " + MANIFEST_DATE + ", 1 entries)",
            "Credits AUTO is off: the switch is off", passLine(0, 0, 0, 0));
        assertThat(keeper.current().list().files()).containsExactly(999L);
    }

    @Test
    void withTheSwitchOnAndNoReadableFileTheLogSaysThereIsNoDenyListInForce() {
        autoEnabled = true;
        when(editions.findAllIds()).thenReturn(List.of());

        applier().runPass();

        assertThat(messages()).containsExactly(
            "Credits deny file: could not be read and no earlier copy exists, so no deny list is in force",
            "Credits AUTO is off: no deny list is in force", passLine(0, 0, 0, 0));
    }

    @Test
    void withTheSwitchOffAndNoReadableFileTheLogSaysBothReasons() {
        when(editions.findAllIds()).thenReturn(List.of());

        applier().runPass();

        assertThat(messages()).containsExactly(
            "Credits deny file: could not be read and no earlier copy exists, so no deny list is in force",
            "Credits AUTO is off: the switch is off and no deny list is in force", passLine(0, 0, 0, 0));
    }

    @Test
    void aBadReadAtStartUpClearsAnAutoMarkerAndNothingIsSetUntilAReadableFileIsBack() {
        autoEnabled = true;
        Edition marked = edition(1, 5_020_000L, CreditsMarkerSource.AUTO);
        library(List.of(marked), List.of(1L));

        PassResult result = applier().runPass();

        assertThat(marked.getCreditsMarkerMs()).isNull();
        assertThat(marked.getCreditsMarkerSource()).isNull();
        assertThat(result).isEqualTo(new PassResult(true, 0, 0, 1, 1, 0, 0, 0));
        assertThat(messages()).containsExactly(
            "Credits deny file: could not be read and no earlier copy exists, so no deny list is in force",
            "Credits AUTO is off: no deny list is in force", passLine(0, 1, 1, 0));
    }

    @Test
    void aGoodReadThenABadOneAppliesTheFirstCopyInFullAndLogsTheFallBack() {
        autoEnabled = true;
        Edition allowed = edition(1, null, null);
        Edition listed = edition(2, 5_020_000L, CreditsMarkerSource.AUTO);
        autoLibrary(allowed, listed);
        when(denyReader.read()).thenReturn(Optional.of(denyFile("edition 2", "file 999")), Optional.empty());
        CreditsApplier applier = applier();

        applier.runPass();
        PassResult second = applier.runPass();

        assertThat(allowed.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.AUTO);
        assertThat(listed.getCreditsMarkerMs()).as("denied by the first copy's edition entry").isNull();
        assertThat(second).isEqualTo(new PassResult(true, 0, 1, 0, 0, 0, 2, 0));
        assertThat(messages()).containsExactly(
            "Credits deny file: read (manifest " + MANIFEST_DATE + ", 2 entries)", passLine(1, 2, 1, 0),
            "Credits deny file: could not be read, so the copy read earlier stays in force (manifest "
                + MANIFEST_DATE + ", 2 entries)", passLine(1, 0, 0, 2));
    }

    @Test
    void aBadReadThenAGoodOneUsesTheNewCopy() {
        autoEnabled = true;
        Edition edition = edition(1, null, null);
        autoLibrary(edition);
        when(denyReader.read()).thenReturn(Optional.empty(), Optional.of(denyFile("file 999")));
        CreditsApplier applier = applier();

        PassResult first = applier.runPass();

        assertThat(first.autoSet()).isZero();
        assertThat(edition.getCreditsMarkerSource()).isNull();

        PassResult second = applier.runPass();

        assertThat(second.autoSet()).isEqualTo(1);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.AUTO);
        assertThat(messages()).containsExactly(
            "Credits deny file: could not be read and no earlier copy exists, so no deny list is in force",
            "Credits AUTO is off: no deny list is in force", passLine(0, 0, 0, 1),
            "Credits deny file: read (manifest " + MANIFEST_DATE + ", 1 entries)", passLine(1, 1, 0, 0));
    }

    @Test
    void aListedFilmsCurrentUnitKeyDeniesAnEditionOfAnotherFilmThatSharesIt() {
        autoEnabled = true;
        Edition edition = edition(1, null, null);
        autoLibrary(edition);
        when(denyReader.read()).thenReturn(Optional.of(denyFile("film 55")));
        when(films.findUnitRowsByIdIn(List.of(55L))).thenReturn(List.of(
            new FilmRepository.UnitRow(55L, filmRow(1).filmTmdbId(), "Other", 2001, null, null)));

        applier().runPass();

        assertThat(edition.getCreditsMarkerSource()).isNull();
    }
}
