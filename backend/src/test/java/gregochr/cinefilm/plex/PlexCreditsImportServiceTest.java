package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.plex.PlexCreditsDtos.LastCreditsImport;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsProgress;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsReport;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsStatus;
import gregochr.cinefilm.plex.PlexCreditsDtos.SkipCount;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexItemMarkers;
import gregochr.cinefilm.plex.PlexModels.PlexMarker;
import gregochr.cinefilm.plex.PlexModels.PlexMedia;
import gregochr.cinefilm.plex.PlexModels.PlexMediaTiming;
import gregochr.cinefilm.tmdb.Sleeper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/** The credits import's orchestration, with Plex, the library and the database faked. */
class PlexCreditsImportServiceTest {

    private static final String PREFIX = "/Volumes/Movies/";
    private static final long HOUR2 = 7_200_000L;
    private static final long MARKER = 6_600_000L;
    private static final long MODIFIED = 1_700_000_000_000L;

    private final PlexApi plex = mock(PlexApi.class);
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private final MediaFileRepository files = mock(MediaFileRepository.class);
    private final PlexCreditsMarkerRepository markers = mock(PlexCreditsMarkerRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final LibraryLock lock = new LibraryLock();
    private final List<Duration> sleeps = new ArrayList<>();
    private Sleeper sleeper = sleeps::add;
    private PlexCreditsImportService service;

    @BeforeEach
    void setUp() {
        when(plex.configured()).thenReturn(true);
        when(plex.movieSections()).thenReturn(List.of("1"));
        service = service(Runnable::run);
    }

    private PlexCreditsImportService service(Executor executor) {
        return new PlexCreditsImportService(plex, ingest, files, markers, lock, executor, CLOCK, d -> sleeper.sleep(d),
            PREFIX.substring(0, PREFIX.length() - 1), transactions);
    }

    private static PlexFilm film(String ratingKey, String title, String... paths) {
        return new PlexFilm(ratingKey, title, null, 1995, null, null, null, null, null, null, null, 0, 0, null,
            Arrays.stream(paths).map(p -> new PlexMedia(List.of(PREFIX + p))).toList());
    }

    private void plexFilms(PlexFilm... films) {
        when(plex.films("1")).thenReturn(List.of(films));
    }

    private void library(String... paths) {
        Map<String, Known> known = new LinkedHashMap<>();
        for (int i = 0; i < paths.length; i++) {
            known.put(paths[i], new Known(i + 1L, paths[i], 1L, NOW, Presence.PRESENT, Playability.PLAYABLE,
                "yuv420p"));
        }
        when(ingest.known()).thenReturn(known);
    }

    /** The file as the database holds it: id {@code index + 1} of {@link #library}, 2 hours long. */
    private static MediaFile file(long id, String path, long modified, Long durationMs) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setPath(path);
        file.setSizeBytes(1_000L + id);
        file.setModifiedAt(Instant.ofEpochMilli(modified));
        file.setDurationMs(durationMs);
        return file;
    }

    /** The database answers the lookup of exactly these files' ids with these files. */
    private void database(MediaFile... current) {
        when(files.findAllById(Arrays.stream(current).map(MediaFile::getId).toList())).thenReturn(List.of(current));
    }

    private static PlexItemMarkers item(PlexMediaTiming... media) {
        return new PlexItemMarkers(HOUR2, List.of(media), List.of(new PlexMarker("credits", MARKER, true)));
    }

    private static PlexMediaTiming timing(String path, long durationMs) {
        return new PlexMediaTiming(durationMs, List.of(PREFIX + path), 1);
    }

    private PlexCreditsReport report() {
        LastCreditsImport last = service.status().lastImport();
        assertThat(last).isNotNull();
        assertThat(last.error()).isNull();
        return last.report();
    }

    private List<PlexCreditsMarker> savedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PlexCreditsMarker>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(markers).saveAll(captor.capture());
        List<PlexCreditsMarker> rows = new ArrayList<>();
        captor.getValue().forEach(rows::add);
        return rows;
    }

    @Test
    void startingWithoutPlexConfiguredIs404AndStartsNothing() {
        when(plex.configured()).thenReturn(false);

        assertThatThrownBy(service::start).isInstanceOf(NotFoundException.class).hasMessage("Plex is not configured");
        assertThat(service.isRunning()).isFalse();
        verifyNoInteractions(markers);
    }

    @Test
    void startingWhileOneRunsIs409AndTheFirstKeepsRunning() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        PlexCreditsImportService slow = service(queued::set);

        slow.start();

        assertThat(slow.isRunning()).isTrue();
        assertThat(slow.status()).isEqualTo(new PlexCreditsStatus(true, new PlexCreditsProgress(0, 0), null));
        assertThatThrownBy(slow::start).isInstanceOf(ConflictException.class)
            .hasMessage("A credits import is already running.");
        assertThat(slow.isRunning()).isTrue();
        assertThat(queued.get()).isNotNull();
    }

    @Test
    void startingWhileThePlexImportHoldsTheLibraryIs409AndAsksNothingOfPlex() {
        lock.tryAcquire(LibraryLock.IMPORT);

        assertThatThrownBy(service::start).isInstanceOf(ConflictException.class)
            .hasMessage("Import credits after the Plex import finishes.");

        assertThat(service.isRunning()).isFalse();
        assertThat(lock.heldBy(LibraryLock.IMPORT)).isTrue();
        verifyNoInteractions(markers);
        verify(plex, never()).movieSections();
    }

    @Test
    void theLockTimeoutNamesTheLastHolderItSaw() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));
        lock.tryAcquire(LibraryLock.EDIT);
        sleeper = d -> {
            sleeps.add(d);
            if (sleeps.size() == 1) {
                lock.release();
                lock.tryAcquire(LibraryLock.MATCH);
            }
        };

        service.start();

        assertThat(service.status().lastImport().error()).contains("locked by \"match\"");
    }

    @Test
    void aRefusedExecutorFreesTheRunningFlag() {
        PlexCreditsImportService stopped = service(command -> {
            throw new RejectedExecutionException("shutting down");
        });

        assertThatThrownBy(stopped::start).isInstanceOf(RejectedExecutionException.class);
        assertThat(stopped.isRunning()).isFalse();
    }

    @Test
    void aFinishedImportIsNotRunningAndKeepsItsReportAndTimes() {
        plexFilms();
        library();

        service.start();

        PlexCreditsStatus status = service.status();
        assertThat(status.running()).isFalse();
        assertThat(status.progress()).isNull();
        assertThat(status.lastImport().startedAt()).isEqualTo(NOW);
        assertThat(status.lastImport().finishedAt()).isEqualTo(NOW);
        assertThat(report()).isEqualTo(new PlexCreditsReport(0, 0, 0, 0, List.of()));
        verifyNoInteractions(markers);
    }

    @Test
    void everyCopyOfAMatchedFilmGetsItsOwnRowWithItsCurrentFingerprintAndTheReportCountsEverything() {
        PlexFilm heat = film("11", "Heat", "Heat.m4v", "Heat.mp4");
        PlexFilm split = film("12", "Split", "Split - pt1.m4v");
        PlexFilm noMarker = film("13", "Quiet", "Quiet.m4v");
        PlexFilm gone = film("14", "Gone", "Gone.m4v");
        plexFilms(heat, split, noMarker, gone);
        library("Heat.m4v", "Heat.mp4", "Split - pt1.m4v", "Quiet.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2), file(2, "Heat.mp4", MODIFIED, HOUR2 + 1_500),
            file(3, "Split - pt1.m4v", MODIFIED, HOUR2), file(4, "Quiet.m4v", MODIFIED, HOUR2));
        database(file(1, "Heat.m4v", MODIFIED, HOUR2), file(2, "Heat.mp4", MODIFIED, HOUR2 + 1_500));
        // Plex lists the longer mp4 first: copies pair by path, never by position.
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.mp4", HOUR2 + 1_500), timing("Heat.m4v", HOUR2)));
        when(plex.itemMarkers("12")).thenReturn(new PlexItemMarkers(HOUR2,
            List.of(new PlexMediaTiming(HOUR2, List.of(PREFIX + "Split - pt1.m4v", PREFIX + "Split - pt2.m4v"), 2)),
            List.of(new PlexMarker("credits", MARKER, true))));
        when(plex.itemMarkers("13")).thenReturn(new PlexItemMarkers(HOUR2, List.of(timing("Quiet.m4v", HOUR2)),
            List.of()));

        service.start();

        List<PlexCreditsMarker> rows = savedRows();
        assertThat(rows).extracting(PlexCreditsMarker::getMediaFileId).containsExactly(1L, 2L);
        assertThat(rows).extracting(PlexCreditsMarker::getStartMs).containsExactly(MARKER, MARKER + 1_500);
        assertThat(rows).extracting(PlexCreditsMarker::getFingerprint)
            .containsExactly("1001:" + MODIFIED, "1002:" + MODIFIED);
        assertThat(rows).extracting(PlexCreditsMarker::getImportedAt).containsOnly(NOW);
        assertThat(report()).isEqualTo(new PlexCreditsReport(4, 3, 2, 0, List.of(
            new SkipCount("MULTI_PART", "Films split into parts", 1),
            new SkipCount("NO_CREDITS_MARKER", "Films with no credits marker in Plex", 1))));
    }

    @Test
    void aCopyThatMatchesNoVersionIsUnmatchedWhileItsSiblingIsStored() {
        plexFilms(film("11", "Heat", "Heat.m4v", "Heat.mp4"));
        library("Heat.m4v", "Heat.mp4");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2), file(2, "Heat.mp4", MODIFIED, HOUR2));
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        // Plex was rescanned between the two calls: the metadata no longer lists the mp4.
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));

        service.start();

        assertThat(savedRows()).extracting(PlexCreditsMarker::getMediaFileId).containsExactly(1L);
        assertThat(report()).isEqualTo(new PlexCreditsReport(1, 1, 1, 1, List.of()));
    }

    @Test
    void aFileClaimedByAnEarlierPlexFilmIsNotAskedAboutAgain() {
        plexFilms(film("11", "Heat", "Heat.m4v"), film("12", "Heat Again", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));

        service.start();

        verify(plex, times(1)).itemMarkers("11");
        verify(plex, never()).itemMarkers("12");
        assertThat(report().matchedFilms()).isEqualTo(1);
    }

    @Test
    void aFileOnDiskThatNoPlexFilmListsIsNeverAskedAbout() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v", "Other.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));

        service.start();

        assertThat(savedRows()).extracting(PlexCreditsMarker::getMediaFileId).containsExactly(1L);
        assertThat(report().plexFilms()).isEqualTo(1);
    }

    @Test
    void runningAgainReplacesTheRowAndWritesOnlyWhatPlexLists() {
        PlexCreditsMarker old = new PlexCreditsMarker();
        old.setMediaFileId(1L);
        old.setFingerprint("old");
        old.setStartMs(1L);
        old.setImportedAt(NOW.minusSeconds(3600));
        when(markers.findAllById(List.of(1L))).thenReturn(List.of(old));
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));

        service.start();

        List<PlexCreditsMarker> rows = savedRows();
        assertThat(rows).containsExactly(old);
        assertThat(old.getFingerprint()).isEqualTo("1001:" + MODIFIED);
        assertThat(old.getStartMs()).isEqualTo(MARKER);
        assertThat(old.getImportedAt()).isEqualTo(NOW);
    }

    @Test
    void aFileThatChangedWhilePlexWasBeingAskedIsNotStoredAndIsCounted() {
        plexFilms(film("11", "Heat", "Heat.m4v", "Heat.mp4"));
        library("Heat.m4v", "Heat.mp4");
        when(files.findAllById(List.of(1L, 2L))).thenReturn(
            List.of(file(1, "Heat.m4v", MODIFIED, HOUR2), file(2, "Heat.mp4", MODIFIED, HOUR2)),
            List.of(file(1, "Heat.m4v", MODIFIED, HOUR2), file(2, "Heat.mp4", MODIFIED + 1, HOUR2)));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2), timing("Heat.mp4", HOUR2)));

        service.start();

        assertThat(savedRows()).extracting(PlexCreditsMarker::getMediaFileId).containsExactly(1L);
        assertThat(report()).isEqualTo(new PlexCreditsReport(1, 1, 1, 0,
            List.of(new SkipCount("FILE_CHANGED", "Files that changed during the import", 1))));
    }

    @Test
    void aFileGoneFromTheDatabaseBeforePlexIsAskedIsCountedAndItsSiblingStillStored() {
        plexFilms(film("11", "Heat", "Heat.m4v", "Heat.mp4"));
        library("Heat.m4v", "Heat.mp4");
        when(files.findAllById(List.of(1L, 2L))).thenReturn(List.of(file(1, "Heat.m4v", MODIFIED, HOUR2)));
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2), timing("Heat.mp4", HOUR2)));

        service.start();

        assertThat(savedRows()).extracting(PlexCreditsMarker::getMediaFileId).containsExactly(1L);
        assertThat(report().skipped()).containsExactly(
            new SkipCount("FILE_CHANGED", "Files that changed during the import", 1));
    }

    @Test
    void aFileDeletedWhilePlexWasBeingAskedIsNotStoredAndIsCounted() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        when(files.findAllById(List.of(1L))).thenReturn(List.of(file(1, "Heat.m4v", MODIFIED, HOUR2)), List.of());
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));

        service.start();

        assertThat(savedRows()).isEmpty();
        assertThat(report().imported()).isZero();
        assertThat(report().skipped()).extracting(SkipCount::reason).containsExactly("FILE_CHANGED");
    }

    @Test
    void plexIsAskedWithoutTheLibraryLockAndTheLockIsOnlyHeldForTheWrite() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        AtomicReference<String> holderWhileAsking = new AtomicReference<>("untouched");
        when(plex.itemMarkers("11")).thenAnswer(call -> {
            holderWhileAsking.set(lock.holder());
            return item(timing("Heat.m4v", HOUR2));
        });
        AtomicReference<String> holderWhileWriting = new AtomicReference<>();
        when(markers.saveAll(anyList())).thenAnswer(call -> {
            holderWhileWriting.set(lock.holder());
            return call.getArgument(0);
        });

        service.start();

        assertThat(holderWhileAsking.get()).isNull();
        assertThat(holderWhileWriting.get()).isEqualTo(LibraryLock.PLEX_CREDITS);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void theWriteWaitsForAScanToFinishThenStores() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));
        lock.tryAcquire(LibraryLock.SCAN);
        sleeper = d -> {
            sleeps.add(d);
            if (sleeps.size() == 3) {
                lock.release();
            }
        };

        service.start();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThat(report().imported()).isEqualTo(1);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aLibraryThatStaysBusyForTenMinutesStopsTheImportWithoutStoringAnything() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));
        lock.tryAcquire(LibraryLock.SCAN);

        service.start();

        LastCreditsImport last = service.status().lastImport();
        assertThat(last.error()).isEqualTo("The library stayed locked by \"scan\" for 10 minutes; nothing was "
            + "stored, run the import again");
        assertThat(last.report()).isNull();
        assertThat(sleeps).hasSize(PlexCreditsImportService.LOCK_ATTEMPTS);
        assertThat(lock.heldBy(LibraryLock.SCAN)).isTrue();
        verifyNoInteractions(markers);
    }

    @Test
    void anInterruptWhileWaitingForTheLockStopsTheImport() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        library("Heat.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11")).thenReturn(item(timing("Heat.m4v", HOUR2)));
        lock.tryAcquire(LibraryLock.SCAN);
        sleeper = d -> {
            throw new InterruptedException();
        };

        service.start();

        assertThat(service.status().lastImport().error()).startsWith("Interrupted while waiting");
        assertThat(Thread.interrupted()).isTrue();
        verifyNoInteractions(markers);
    }

    @Test
    void anItemPlexCannotAnswerForIsCountedAndTheImportGoesOn() {
        plexFilms(film("11", "Bad", "Bad.m4v"), film("12", "Heat", "Heat.m4v"));
        library("Bad.m4v", "Heat.m4v");
        database(file(1, "Bad.m4v", MODIFIED, HOUR2), file(2, "Heat.m4v", MODIFIED, HOUR2));
        database(file(2, "Heat.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers("11"))
            .thenThrow(new PlexException("Plex answered status 500 to plex item markers", null));
        when(plex.itemMarkers("12")).thenReturn(item(timing("Heat.m4v", HOUR2)));

        service.start();

        assertThat(savedRows()).extracting(PlexCreditsMarker::getMediaFileId).containsExactly(2L);
        assertThat(report().skipped()).containsExactly(
            new SkipCount("PLEX_UNAVAILABLE", "Films Plex did not answer for", 1));
    }

    @Test
    void aFilmWithNoRatingKeyIsCountedAsUnanswered() {
        plexFilms(film(null, "Keyless", "Keyless.m4v"));
        library("Keyless.m4v");
        database(file(1, "Keyless.m4v", MODIFIED, HOUR2));
        when(plex.itemMarkers(null)).thenThrow(new PlexException("Plex rating key is not a number", null));

        service.start();

        assertThat(report().skipped()).extracting(SkipCount::reason).containsExactly("PLEX_UNAVAILABLE");
    }

    @Test
    void fiveItemsInARowFailingMeansPlexIsDownAndNothingIsStored() {
        PlexFilm[] films = new PlexFilm[6];
        String[] paths = new String[6];
        MediaFile[] current = new MediaFile[6];
        for (int i = 0; i < 6; i++) {
            films[i] = film("" + (20 + i), "F" + i, "F" + i + ".m4v");
            paths[i] = "F" + i + ".m4v";
            current[i] = file(i + 1L, paths[i], MODIFIED, HOUR2);
            when(plex.itemMarkers("" + (20 + i))).thenThrow(new PlexException("Plex is not reachable", null));
        }
        plexFilms(films);
        library(paths);
        database(current);

        service.start();

        LastCreditsImport last = service.status().lastImport();
        assertThat(last.error()).isEqualTo("Plex stopped answering after 5 films in a row; nothing was stored");
        assertThat(last.report()).isNull();
        verify(plex, never()).itemMarkers("25");
        verifyNoInteractions(markers);
    }

    @Test
    void aSuccessBetweenFailuresStartsTheCountAgain() {
        PlexFilm[] films = new PlexFilm[9];
        String[] paths = new String[9];
        MediaFile[] current = new MediaFile[9];
        for (int i = 0; i < 9; i++) {
            films[i] = film("" + (30 + i), "F" + i, "F" + i + ".m4v");
            paths[i] = "F" + i + ".m4v";
            current[i] = file(i + 1L, paths[i], MODIFIED, HOUR2);
            if (i == 4) {
                when(plex.itemMarkers("34")).thenReturn(item(timing("F4.m4v", HOUR2)));
            } else {
                when(plex.itemMarkers("" + (30 + i))).thenThrow(new PlexException("Plex is not reachable", null));
            }
        }
        plexFilms(films);
        library(paths);
        database(current);
        database(current[4]);

        service.start();

        assertThat(report().imported()).isEqualTo(1);
        assertThat(report().skipped()).containsExactly(new SkipCount("PLEX_UNAVAILABLE",
            "Films Plex did not answer for", 8));
    }

    @Test
    void plexBeingUnreadableForTheListEndsTheRunWithItsMessage() {
        when(plex.films("1")).thenThrow(new PlexException("Plex answered status 401 to plex films", null));

        service.start();

        assertThat(service.status().lastImport().error()).isEqualTo("Plex answered status 401 to plex films");
        assertThat(service.status().lastImport().report()).isNull();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void anUnexpectedFailureEndsTheRunAndNamesOnlyTheExceptionClass() {
        plexFilms(film("11", "Heat", "Heat.m4v"));
        when(ingest.known()).thenThrow(new IllegalStateException("secret detail"));

        service.start();

        assertThat(service.status().lastImport().error()).isEqualTo("Import failed: IllegalStateException");
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void progressCountsFilmsAskedAboutWhileTheImportRuns() {
        plexFilms(film("11", "Heat", "Heat.m4v"), film("12", "Alien", "Alien.m4v"));
        library("Heat.m4v", "Alien.m4v");
        database(file(1, "Heat.m4v", MODIFIED, HOUR2), file(2, "Alien.m4v", MODIFIED, HOUR2));
        List<PlexCreditsProgress> seen = new ArrayList<>();
        when(plex.itemMarkers("11")).thenAnswer(call -> {
            seen.add(service.status().progress());
            return item(timing("Heat.m4v", HOUR2));
        });
        when(plex.itemMarkers("12")).thenAnswer(call -> {
            seen.add(service.status().progress());
            return item(timing("Alien.m4v", HOUR2));
        });

        service.start();

        assertThat(seen).containsExactly(new PlexCreditsProgress(0, 2), new PlexCreditsProgress(1, 2));
    }
}
