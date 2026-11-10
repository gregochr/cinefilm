package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.library.ScanRunRepository;
import gregochr.cinefilm.plex.PlexDtos.LastImport;
import gregochr.cinefilm.plex.PlexDtos.PlexImportReport;
import gregochr.cinefilm.plex.PlexDtos.PlexImportStatus;
import gregochr.cinefilm.plex.PlexDtos.PlexProgress;
import gregochr.cinefilm.plex.PlexFilmWriter.Applied;
import gregochr.cinefilm.plex.PlexFilmWriter.FilmWrite;
import gregochr.cinefilm.plex.PlexFilmWriter.Preflight;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexImage;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

/** The import's orchestration, with Plex, TMDB, the artwork store and the database faked. */
class PlexImportServiceTest {

    private static final String PREFIX = "/Volumes/Media Library/Movies/";
    private static final String THUMB = "/library/metadata/1/thumb/2";
    private static final String ART = "/library/metadata/1/art/3";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Preflight NEEDS_ALL = new Preflight(true, false, false);
    private static final Preflight HAS_ALL = new Preflight(false, true, true);
    private static final Applied NOTHING = new Applied(0, 0, 0, 0, 0);

    private final PlexApi plex = mock(PlexApi.class);
    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private final PlexFilmWriter writer = mock(PlexFilmWriter.class);
    private final ArtworkStore artwork = mock(ArtworkStore.class);
    private final gregochr.cinefilm.library.LibraryGrouping grouping =
        mock(gregochr.cinefilm.library.LibraryGrouping.class);
    private final ScanRunRepository scans = mock(ScanRunRepository.class);
    private final PlexImportRunRepository runs = mock(PlexImportRunRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final LibraryLock lock = new LibraryLock();
    private final List<PlexImportRun> saved = new ArrayList<>();
    private PlexImportService service;

    @BeforeEach
    void setUp() {
        service = new PlexImportService(plex, tmdb, ingest, writer, artwork, scans, runs, lock, Runnable::run,
            CLOCK, PREFIX.substring(0, PREFIX.length() - 1), events, grouping);
        when(plex.configured()).thenReturn(true);
        when(scans.existsByFinishedAtNotNullAndErrorIsNull()).thenReturn(true);
        when(runs.save(any(PlexImportRun.class))).thenAnswer(call -> {
            PlexImportRun run = call.getArgument(0);
            saved.add(run);
            return run;
        });
    }

    private static Known known(long id, String path) {
        return new Known(id, path, 1L, NOW, Presence.PRESENT, Playability.PLAYABLE, "yuv420p");
    }

    private static PlexFilm film(String title, Long tmdbId, String thumb, String art, String... files) {
        return new PlexFilm("1", title, null, 1995, null, null, null, null, thumb, art, tmdbId, 0, 0, null,
            java.util.Arrays.stream(files).map(f -> new PlexModels.PlexMedia(List.of(f))).toList());
    }

    private static MovieDetails movie(long id) {
        return new MovieDetails(id, "T", "T", 1995, null, 100, List.of(), null, null, 1, List.of(), List.of(), null);
    }

    private void library(PlexFilm... films) {
        when(plex.movieSections()).thenReturn(List.of("1"));
        when(plex.films("1")).thenReturn(List.of(films));
        when(plex.collections("1")).thenReturn(List.of());
    }

    private void known(Known... known) {
        Map<String, Known> map = new java.util.LinkedHashMap<>();
        for (Known k : known) {
            map.put(k.path(), k);
        }
        when(ingest.known()).thenReturn(map);
    }

    private PlexImportRun finished() {
        assertThat(saved).isNotEmpty();
        return saved.get(saved.size() - 1);
    }

    private PlexImportReport report() throws Exception {
        return JSON.readValue(finished().getReport(), PlexImportReport.class);
    }

    @Test
    void startingWithoutPlexConfiguredIsA404AndTakesNoLock() {
        when(plex.configured()).thenReturn(false);

        assertThatThrownBy(service::start).isInstanceOf(NotFoundException.class).hasMessage("Plex is not configured");
        assertThat(lock.holder()).isNull();
        verifyNoInteractions(runs);
    }

    @Test
    void startingWhileAScanOrImportRunsIs409() {
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(service::start).isInstanceOf(ConflictException.class)
            .hasMessage("A scan or import is already running");
        assertThat(lock.heldBy(LibraryLock.SCAN)).isTrue();
        verifyNoInteractions(runs);
    }

    @Test
    void startingBeforeAnyCleanScanIs409AndReleasesTheLock() {
        when(scans.existsByFinishedAtNotNullAndErrorIsNull()).thenReturn(false);

        assertThatThrownBy(service::start).isInstanceOf(ConflictException.class)
            .hasMessage("Run a library scan first");
        assertThat(lock.heldBy(LibraryLock.IMPORT)).isFalse();
        verifyNoInteractions(runs);
    }

    @Test
    void aRefusedExecutorReleasesTheLock() {
        Executor refusing = command -> {
            throw new RejectedExecutionException("shutting down");
        };
        PlexImportService stopped = new PlexImportService(plex, tmdb, ingest, writer, artwork, scans, runs, lock,
            refusing, CLOCK, PREFIX, events, grouping);

        assertThatThrownBy(stopped::start).isInstanceOf(RejectedExecutionException.class);
        assertThat(lock.holder()).isNull();
        assertThat(stopped.isRunning()).isFalse();
    }

    @Test
    void theLockIsHeldWhileTheImportRunsAndReleasedWhenItEnds() {
        AtomicReference<Boolean> heldDuring = new AtomicReference<>();
        when(plex.movieSections()).thenAnswer(call -> {
            heldDuring.set(service.isRunning());
            return List.of();
        });

        service.start();

        assertThat(heldDuring.get()).isTrue();
        assertThat(service.isRunning()).isFalse();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anImportPairsByPathWritesEachFilmAndStoresAReport() throws Exception {
        PlexFilm heat = film("Heat", 949L, THUMB, ART, PREFIX + "Heat.m4v", PREFIX + "Heat (Extended).m4v");
        PlexFilm alien = film("Alien", 348L, null, null, PREFIX + "Alien.m4v");
        library(heat, alien);
        known(known(11, "Heat.m4v"), known(12, "Heat (Extended).m4v"), known(13, "Alien.m4v"), known(14, "Extra.m4v"));
        MovieDetails heatMovie = movie(949);
        when(tmdb.enabled()).thenReturn(true);
        when(tmdb.movie(949)).thenReturn(Optional.of(heatMovie));
        when(writer.preflight(List.of(11L, 12L), 949L)).thenReturn(NEEDS_ALL);
        when(writer.preflight(List.of(13L), 348L)).thenReturn(HAS_ALL);
        when(plex.image(THUMB, 600, 900)).thenReturn(new PlexImage(new byte[] {1}, "image/jpeg"));
        when(plex.image(ART, 1920, 1080)).thenReturn(new PlexImage(new byte[] {2}, "image/png"));
        when(artwork.store(new byte[] {1}, "image/jpeg")).thenReturn("/api/art/p.jpg");
        when(artwork.store(new byte[] {2}, "image/png")).thenReturn("/api/art/b.png");
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(11L, 12L), heatMovie, "/api/art/p.jpg", "/api/art/b.png",
            NOW))).thenReturn(new Applied(1, 2, 2, 1, 0));
        when(writer.apply(FilmWrite.ofFiles(alien, List.of(13L), null, null, null, NOW)))
            .thenReturn(new Applied(0, 0, 0, 0, 1));

        service.start();

        PlexImportRun run = finished();
        assertThat(run.getError()).isNull();
        assertThat(run.getStartedAt()).isEqualTo(NOW);
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
        assertThat(saved).hasSize(2).allSatisfy(r -> assertThat(r).isSameAs(run));
        PlexImportReport report = report();
        assertThat(report).isEqualTo(new PlexImportReport(2, 2, 1, 2, 2, 1, 1, 0, 0, 0, 2, 1, 1, 0, List.of(),
            List.of(), 0, 1, List.of(), 0));
        assertThat(lock.holder()).isNull();
    }

    @Test
    void filesPairInNfcWhicheverFormTheDatabaseAndPlexUse() throws Exception {
        String composed = "Amélie.m4v";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        PlexFilm amelie = film("Amelie", null, null, null, PREFIX + decomposed);
        library(amelie);
        known(known(5, composed));
        when(writer.preflight(List.of(5L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(amelie, List.of(5L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        assertThat(report().paired()).isEqualTo(1);
        assertThat(report().failures()).isZero();
    }

    @Test
    void aFileUnderPlexVersionsOrOutsideThePrefixDoesNotPair() throws Exception {
        PlexFilm heat = film("Heat", null, null, null, PREFIX + "Plex Versions/Heat.m4v", "/other/Heat.m4v",
            PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Plex Versions/Heat.m4v"), known(2, "Heat.m4v"));
        when(writer.preflight(List.of(2L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(2L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        assertThat(report().paired()).isEqualTo(1);
        assertThat(report().onDiskNotInPlex()).isEqualTo(1);
    }

    @Test
    void aFileClaimedByOnePlexFilmIsNotPairedAgainByAnother() throws Exception {
        PlexFilm one = film("One", null, null, null, PREFIX + "A.m4v");
        PlexFilm two = film("Two", null, null, null, PREFIX + "A.m4v");
        library(one, two);
        known(known(1, "A.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(one, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        PlexImportReport report = report();
        assertThat(report.paired()).isEqualTo(1);
        assertThat(report.notOnDisk()).containsExactly("Two");
        assertThat(report.notOnDiskTotal()).isEqualTo(1);
    }

    @Test
    void plexFilmsWithNoFileOnDiskAreReportedAndNeverCreated() throws Exception {
        PlexFilm there = film("There", null, null, null, PREFIX + "There.m4v");
        PlexFilm gone = film("Gone", null, null, null, PREFIX + "Gone.m4v");
        PlexFilm untitled = film(null, null, null, null);
        library(there, gone, untitled);
        known(known(1, "There.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(there, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        PlexImportReport report = report();
        assertThat(report.notOnDisk()).containsExactly("Gone", "Untitled");
        assertThat(report.notOnDiskTotal()).isEqualTo(2);
        assertThat(report.plexFilms()).isEqualTo(3);
        assertThat(report.paired()).isEqualTo(1);
    }

    @Test
    void onDiskNotInPlexCountsOnlyPresentFilesNoPlexFilmClaimed() throws Exception {
        PlexFilm there = film("There", null, null, null, PREFIX + "There.m4v");
        library(there);
        known(known(1, "There.m4v"), known(2, "Unknown.m4v"),
            new Known(3L, "Gone.m4v", 1L, NOW, Presence.MISSING, Playability.PLAYABLE, "yuv420p"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(there, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        assertThat(report().onDiskNotInPlex()).isEqualTo(1);
    }

    @Test
    void theListsAreCappedAtTwoHundredWithTheTotalsBesideThem() throws Exception {
        List<PlexFilm> films = new ArrayList<>();
        films.add(film("There", null, null, null, PREFIX + "There.m4v"));
        for (int i = 0; i < 250; i++) {
            films.add(film("Gone " + i, null, null, null));
        }
        library(films.toArray(new PlexFilm[0]));
        List<String> collections = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            collections.add("Collection " + i);
        }
        when(plex.collections("1")).thenReturn(collections);
        known(known(1, "There.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(films.get(0), List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        PlexImportReport report = report();
        assertThat(report.notOnDisk()).hasSize(200).startsWith("Gone 0").endsWith("Gone 199");
        assertThat(report.notOnDiskTotal()).isEqualTo(250);
        assertThat(report.collections()).hasSize(200).startsWith("Collection 0").endsWith("Collection 199");
        assertThat(report.collectionsTotal()).isEqualTo(201);
    }

    @Test
    void exactlyTwoHundredEntriesAreAllKept() throws Exception {
        List<PlexFilm> films = new ArrayList<>();
        films.add(film("There", null, null, null, PREFIX + "There.m4v"));
        for (int i = 0; i < 200; i++) {
            films.add(film("Gone " + i, null, null, null));
        }
        library(films.toArray(new PlexFilm[0]));
        known(known(1, "There.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(films.get(0), List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        assertThat(report().notOnDisk()).hasSize(200).endsWith("Gone 199");
    }

    @Test
    void whenNoFilePairsNothingIsWrittenAndTheRunSaysWhy() {
        library(film("Heat", 949L, THUMB, ART, "/wrong/prefix/Heat.m4v"));
        known(known(1, "Heat.m4v"));

        service.start();

        PlexImportRun run = finished();
        assertThat(run.getError()).isEqualTo("No Plex file matches a file in the library; check PLEX_PATH_PREFIX");
        assertThat(run.getReport()).isNull();
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
        verifyNoInteractions(writer);
        verify(plex, never()).image(THUMB, 600, 900);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFinishedImportPublishesPlexImportFinishedWhetherOrNotItFailed() {
        when(plex.movieSections()).thenThrow(new PlexException("Plex is not reachable", null));

        service.start();

        verify(events).publishEvent(new PlexImportFinished());
    }

    @Test
    void anUnreachablePlexStopsTheImportBeforeAnythingIsRead() {
        when(plex.movieSections()).thenThrow(new PlexException("Plex is not reachable", null));

        service.start();

        assertThat(finished().getError()).isEqualTo("Plex is not reachable");
        assertThat(finished().getReport()).isNull();
        verifyNoInteractions(writer);
        verifyNoInteractions(ingest);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anUnexpectedFailureIsRecordedByTypeOnlyAndReleasesTheLock() {
        when(plex.movieSections()).thenThrow(new IllegalStateException("secret detail"));

        service.start();

        assertThat(finished().getError()).isEqualTo("Import failed: IllegalStateException");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFailureOnOneFilmLeavesTheOthersCommittedAndIsReported() throws Exception {
        PlexFilm bad = film("Bad", null, null, null, PREFIX + "Bad.m4v");
        PlexFilm good = film("Good", null, null, null, PREFIX + "Good.m4v");
        PlexFilm nameless = film(null, null, null, null, PREFIX + "Nameless.m4v");
        library(bad, good, nameless);
        known(known(1, "Bad.m4v"), known(2, "Good.m4v"), known(3, "Nameless.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.preflight(List.of(2L), null)).thenReturn(HAS_ALL);
        when(writer.preflight(List.of(3L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(bad, List.of(1L), null, null, null, NOW)))
            .thenThrow(new IllegalStateException("boom"));
        when(writer.apply(FilmWrite.ofFiles(good, List.of(2L), null, null, null, NOW))).thenReturn(NOTHING);
        when(writer.apply(FilmWrite.ofFiles(nameless, List.of(3L), null, null, null, NOW)))
            .thenThrow(new IllegalStateException("boom"));

        service.start();

        PlexImportReport report = report();
        assertThat(finished().getError()).isNull();
        assertThat(report.failures()).isEqualTo(2);
        assertThat(report.failedFilms()).containsExactly("Bad", "Untitled");
        assertThat(report.metadataApplied()).isEqualTo(1);
        verify(writer).apply(FilmWrite.ofFiles(good, List.of(2L), null, null, null, NOW));
    }

    @Test
    void artworkAlreadyStoredIsNotDownloadedAgain() throws Exception {
        PlexFilm heat = film("Heat", 949L, THUMB, ART, PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), 949L)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        verify(plex, never()).image(THUMB, 600, 900);
        verify(plex, never()).image(ART, 1920, 1080);
        assertThat(report().postersStored()).isZero();
        assertThat(report().backdropsStored()).isZero();
    }

    @Test
    void onlyTheMissingHalfOfTheArtworkIsDownloaded() throws Exception {
        PlexFilm heat = film("Heat", null, THUMB, ART, PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(new Preflight(false, true, false));
        when(plex.image(ART, 1920, 1080)).thenReturn(new PlexImage(new byte[] {2}, "image/jpeg"));
        when(artwork.store(new byte[] {2}, "image/jpeg")).thenReturn("/api/art/b.jpg");
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, "/api/art/b.jpg", NOW))).thenReturn(NOTHING);

        service.start();

        verify(plex, never()).image(THUMB, 600, 900);
        assertThat(report().backdropsStored()).isEqualTo(1);
        assertThat(report().postersStored()).isZero();
    }

    @Test
    void anArtworkPathThatIsNotAPlainLibraryPathIsSkippedAndCountedNeverFetched() throws Exception {
        PlexFilm heat = film("Heat", null, "http://evil.example/x.jpg", "/library/metadata/1/art/3?x=1",
            PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(new Preflight(false, false, false));
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        verify(plex, never()).image("http://evil.example/x.jpg", 600, 900);
        verify(plex, never()).image("/library/metadata/1/art/3?x=1", 1920, 1080);
        assertThat(report().invalidArtPaths()).isEqualTo(2);
        assertThat(report().artworkFailures()).isZero();
        assertThat(report().failures()).isZero();
        assertThat(report().metadataApplied()).isEqualTo(1);
    }

    @Test
    void aFailedDownloadOrStoreIsCountedAndTheFilmIsStillWritten() throws Exception {
        PlexFilm heat = film("Heat", null, THUMB, ART, PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(new Preflight(false, false, false));
        when(plex.image(THUMB, 600, 900)).thenThrow(new PlexException("Plex is not reachable", null));
        when(plex.image(ART, 1920, 1080)).thenReturn(new PlexImage(new byte[] {2}, "image/gif"));
        when(artwork.store(new byte[] {2}, "image/gif")).thenThrow(new IllegalArgumentException("type"));
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        PlexImportReport report = report();
        assertThat(report.artworkFailures()).isEqualTo(2);
        assertThat(report.failures()).isZero();
        assertThat(report.metadataApplied()).isEqualTo(1);
    }

    @Test
    void anUnwritableStoreIsCountedAsAnArtworkFailure() throws Exception {
        PlexFilm heat = film("Heat", null, THUMB, null, PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(new Preflight(false, false, true));
        when(plex.image(THUMB, 600, 900)).thenReturn(new PlexImage(new byte[] {1}, "image/jpeg"));
        when(artwork.store(new byte[] {1}, "image/jpeg")).thenThrow(new UncheckedIOException(
            new java.io.IOException("disk full")));
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        assertThat(report().artworkFailures()).isEqualTo(1);
    }

    @Test
    void tmdbIsOnlyAskedWhenTheFilmNeedsItAndIsConfigured() throws Exception {
        PlexFilm needs = film("Needs", 1L, null, null, PREFIX + "A.m4v");
        PlexFilm has = film("Has", 2L, null, null, PREFIX + "B.m4v");
        library(needs, has);
        known(known(1, "A.m4v"), known(2, "B.m4v"));
        MovieDetails details = movie(1);
        when(tmdb.enabled()).thenReturn(true);
        when(tmdb.movie(1)).thenReturn(Optional.of(details));
        when(writer.preflight(List.of(1L), 1L)).thenReturn(new Preflight(true, true, true));
        when(writer.preflight(List.of(2L), 2L)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(needs, List.of(1L), details, null, null, NOW))).thenReturn(NOTHING);
        when(writer.apply(FilmWrite.ofFiles(has, List.of(2L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        verify(tmdb).movie(1);
        verify(tmdb, never()).movie(2);
        assertThat(report().tmdbUnavailable()).isZero();
    }

    @Test
    void aTmdbOutageIsCountedAndTheFilmStillGetsPlexMetadata() throws Exception {
        PlexFilm heat = film("Heat", 949L, null, null, PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(tmdb.enabled()).thenReturn(true);
        when(tmdb.movie(949)).thenThrow(new TmdbUnavailableException("down", null));
        when(writer.preflight(List.of(1L), 949L)).thenReturn(new Preflight(true, true, true));
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        PlexImportReport report = report();
        assertThat(report.tmdbUnavailable()).isEqualTo(1);
        assertThat(report.failures()).isZero();
        assertThat(report.metadataApplied()).isEqualTo(1);
    }

    @Test
    void withTmdbSwitchedOffOrTheIdUnknownTheFilmIsStillWritten() throws Exception {
        PlexFilm off = film("Off", 1L, null, null, PREFIX + "A.m4v");
        PlexFilm unknown = film("Unknown", 2L, null, null, PREFIX + "B.m4v");
        library(off, unknown);
        known(known(1, "A.m4v"), known(2, "B.m4v"));
        when(tmdb.enabled()).thenReturn(false).thenReturn(true);
        when(tmdb.movie(2)).thenReturn(Optional.empty());
        when(writer.preflight(List.of(1L), 1L)).thenReturn(new Preflight(true, true, true));
        when(writer.preflight(List.of(2L), 2L)).thenReturn(new Preflight(true, true, true));
        when(writer.apply(FilmWrite.ofFiles(off, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);
        when(writer.apply(FilmWrite.ofFiles(unknown, List.of(2L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        verify(tmdb, never()).movie(1);
        assertThat(report().metadataApplied()).isEqualTo(2);
        assertThat(report().tmdbUnavailable()).isZero();
    }

    @Test
    void progressNamesTheFilmBeingHandledWhileTheImportRuns() {
        PlexFilm one = film("One", null, null, null, PREFIX + "A.m4v");
        PlexFilm two = film("Two", null, null, null, PREFIX + "B.m4v");
        library(one, two);
        known(known(1, "A.m4v"), known(2, "B.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.preflight(List.of(2L), null)).thenReturn(HAS_ALL);
        List<PlexProgress> seen = new ArrayList<>();
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.empty());
        when(plex.reachable()).thenReturn(true);
        when(writer.apply(FilmWrite.ofFiles(one, List.of(1L), null, null, null, NOW))).thenAnswer(call -> {
            seen.add(service.status().progress());
            return NOTHING;
        });
        when(writer.apply(FilmWrite.ofFiles(two, List.of(2L), null, null, null, NOW))).thenAnswer(call -> {
            seen.add(service.status().progress());
            return NOTHING;
        });

        service.start();

        assertThat(seen).containsExactly(new PlexProgress(0, 2, "One"), new PlexProgress(1, 2, "Two"));
        assertThat(service.status().progress()).isNull();
    }

    @Test
    void statusWhenPlexIsNotConfiguredSaysSoWithoutTouchingPlexOrTheDatabase() {
        when(plex.configured()).thenReturn(false);

        assertThat(service.status()).isEqualTo(new PlexImportStatus(false, false, false, null, null));
        verifyNoInteractions(runs);
        verify(plex, never()).reachable();
    }

    @Test
    void statusWhenIdleAsksPlexAndShowsTheLastImportWithItsReport() throws Exception {
        PlexImportRun last = new PlexImportRun();
        last.setStartedAt(NOW.minusSeconds(60));
        last.setFinishedAt(NOW);
        PlexImportReport report = new PlexImportReport(5, 4, 1, 0, 4, 3, 2, 0, 0, 0, 2, 1, 0, 0, List.of(),
            List.of("Gone"), 1, 7, List.of("Bond"), 1);
        last.setReport(JSON.writeValueAsString(report));
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.of(last));
        when(plex.reachable()).thenReturn(true);

        PlexImportStatus status = service.status();

        assertThat(status).isEqualTo(new PlexImportStatus(true, true, false, null,
            new LastImport(NOW.minusSeconds(60), NOW, null, report)));
    }

    @Test
    void statusReportsAnUnreachablePlexAndALastImportThatStoppedWithAnError() {
        PlexImportRun last = new PlexImportRun();
        last.setStartedAt(NOW);
        last.setFinishedAt(NOW);
        last.setError("Plex is not reachable");
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.of(last));
        when(plex.reachable()).thenReturn(false);

        PlexImportStatus status = service.status();

        assertThat(status.reachable()).isFalse();
        assertThat(status.lastImport()).isEqualTo(new LastImport(NOW, NOW, "Plex is not reachable", null));
    }

    @Test
    void anUnreadableStoredReportIsShownAsNoReportNotAnError() {
        PlexImportRun last = new PlexImportRun();
        last.setStartedAt(NOW);
        last.setFinishedAt(NOW);
        last.setReport("{not json");
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.of(last));
        when(plex.reachable()).thenReturn(true);

        assertThat(service.status().lastImport().report()).isNull();
    }

    @Test
    void statusWhileRunningDoesNotAskPlexAndShowsProgress() {
        lock.tryAcquire(LibraryLock.IMPORT);
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.empty());

        PlexImportStatus status = service.status();

        assertThat(status.running()).isTrue();
        assertThat(status.reachable()).isTrue();
        verify(plex, never()).reachable();
    }

    @Test
    void aScanRunningIsNotAnImportRunning() {
        lock.tryAcquire(LibraryLock.SCAN);
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.empty());
        when(plex.reachable()).thenReturn(true);

        assertThat(service.isRunning()).isFalse();
        assertThat(service.status().running()).isFalse();
    }

    @Test
    void theSameFileCapturedOnlyOnceIsPassedToTheWriterExactlyOnce() {
        PlexFilm heat = film("Heat", null, null, null, PREFIX + "Heat.m4v", PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);

        service.start();

        verify(writer, times(1)).apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW));
        verify(writer).preflight(List.of(1L), null);
    }

    @Test
    void theProgressStartsAtZeroAsSoonAsTheImportIsAccepted() {
        List<Runnable> queued = new ArrayList<>();
        PlexImportService deferred = new PlexImportService(plex, tmdb, ingest, writer, artwork, scans, runs, lock,
            queued::add, CLOCK, PREFIX, events, grouping);
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.empty());

        deferred.start();

        assertThat(deferred.status().progress()).isEqualTo(new PlexProgress(0, 0, null));
        assertThat(queued).hasSize(1);
    }

    @Test
    void afterAnImportTheArtworkNoFilmReferencesIsSwept() {
        PlexFilm heat = film("Heat", null, null, null, PREFIX + "Heat.m4v");
        library(heat);
        known(known(1, "Heat.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(heat, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);
        when(writer.storedArtworkPaths()).thenReturn(List.of("/api/art/a.jpg", "/api/art/b.png"));

        service.start();

        verify(artwork).deleteUnreferenced(List.of("/api/art/a.jpg", "/api/art/b.png"));
    }

    @Test
    void anImportThatStopsBeforeWritingSweepsNothing() {
        library(film("Heat", 949L, null, null, "/wrong/Heat.m4v"));
        known(known(1, "Heat.m4v"));

        service.start();

        verify(artwork, never()).deleteUnreferenced(java.util.List.of());
    }

    @Test
    void afterTheImportRepairThenGroupThenDefaultsRunUnderTheLockAndTheGuardsLeftOversAreFlagged() {
        PlexFilm rocky = film("Rocky IV", null, null, null, PREFIX + "Rocky IV.m4v", PREFIX + "Rocky V.m4v");
        library(rocky);
        known(known(1, "Rocky IV.m4v"), known(2, "Rocky V.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(rocky, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);
        AtomicReference<Boolean> locked = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(call -> {
            locked.set(service.isRunning());
            return 0;
        }).when(grouping).repair();

        service.start();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(writer, grouping);
        order.verify(writer).apply(FilmWrite.ofFiles(rocky, List.of(1L), null, null, null, NOW));
        order.verify(grouping).repair();
        order.verify(grouping).group();
        order.verify(grouping).refreshDefaults();
        assertThat(locked.get()).isTrue();
        verify(writer).flag(org.mockito.ArgumentMatchers.argThat(lines -> lines.size() == 1
            && lines.get(0).contains("Rocky V.m4v") && lines.get(0).contains("Rocky IV")),
            org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(finished().getError()).isNull();
    }

    @Test
    void aFailureInTheGroupingPassOrTheFlagLogNeverFailsTheImport() throws Exception {
        PlexFilm rocky = film("Rocky IV", null, null, null, PREFIX + "Rocky IV.m4v", PREFIX + "Rocky V.m4v");
        library(rocky);
        known(known(1, "Rocky IV.m4v"), known(2, "Rocky V.m4v"));
        when(writer.preflight(List.of(1L), null)).thenReturn(HAS_ALL);
        when(writer.apply(FilmWrite.ofFiles(rocky, List.of(1L), null, null, null, NOW))).thenReturn(NOTHING);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(writer).flag(any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(grouping).repair();

        service.start();

        assertThat(finished().getError()).isNull();
        assertThat(report().paired()).isEqualTo(1);
        verify(grouping, never()).group();
    }
}
