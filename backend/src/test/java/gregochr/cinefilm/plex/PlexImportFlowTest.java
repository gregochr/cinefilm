package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.FfprobeService;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.LibraryScanner;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.library.ScanRun;
import gregochr.cinefilm.library.ScanRunRepository;
import gregochr.cinefilm.library.ScanService;
import gregochr.cinefilm.plex.PlexDtos.PlexImportReport;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexImage;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import gregochr.cinefilm.tmdb.TmdbModels;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** The whole import against H2 with a fake Plex and TMDB: structure, metadata, idempotence and the later scan. */
class PlexImportFlowTest extends ApiTestBase {

    private static final String PREFIX = "/Volumes/Media Library/Movies/";
    private static final Instant MODIFIED = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant PLEX_ADDED = NOW.minus(Duration.ofDays(500));
    private static final Instant VIEWED = NOW.minus(Duration.ofDays(20));
    private static final String HEAT_THUMB = "/library/metadata/1/thumb/2";
    private static final String HEAT_ART = "/library/metadata/1/art/3";
    private static final String ALIEN_THUMB = "/library/metadata/2/thumb/4";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    @MockitoBean
    private PlexApi plex;

    @MockitoBean
    private TmdbApi tmdb;

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private PlexFilmWriter writer;

    @Autowired
    private ArtworkStore artwork;

    @Autowired
    private ScanRunRepository scanRuns;

    @Autowired
    private PlexImportRunRepository importRuns;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private EntityManager em;

    private PlexImportService service;
    private Film heatA;
    private Film heatB;
    private Film alien;

    private Film film(String title, Long tmdbId, MatchStatus status, String path) {
        Film film = TestData.film(null, title, 2000, NOW.minus(Duration.ofDays(3)));
        film.setTmdbId(tmdbId);
        film.setMatchStatus(status);
        film.setPosterPath("/tmdb-poster.jpg");
        MediaFile file = TestData.file(TestData.edition(film, null, "Original", 100 * MIN, null), path);
        file.setSizeBytes(10L);
        file.setModifiedAt(MODIFIED);
        return films.saveAndFlush(film);
    }

    private static MovieDetails movie(long id, String title) {
        return new MovieDetails(id, title, title, 1990, "TMDB overview", 100, List.of("Sci-Fi"), "/tp.jpg", "/tb.jpg",
            1, List.of(new TmdbModels.Credit(100 + id, "Director " + id, null, null)),
            List.of(new TmdbModels.Credit(200 + id, "Actor " + id, null, "Role")), null);
    }

    @BeforeEach
    void setUp() {
        ScanRun scan = new ScanRun();
        scan.setStartedAt(NOW);
        scan.setFinishedAt(NOW);
        scan.setTrigger("admin");
        scanRuns.save(scan);
        heatA = film("heat", 949L, MatchStatus.MATCHED, "Heat.m4v");
        heatB = film("heat dc", 949L, MatchStatus.MATCHED, "Heat (DC).m4v");
        alien = film("alien", null, MatchStatus.PENDING, "Alien.m4v");
        saveState(TestData.state(heatB.getEditions().get(0), 10 * MIN, false, NOW.minus(Duration.ofDays(1)), null));

        PlexFilm plexHeat = new PlexFilm("1", "Heat", "Heat", 1995, "Plex heat", null, PLEX_ADDED, PLEX_ADDED,
            HEAT_THUMB, HEAT_ART, 949L, 1, 30 * MIN, VIEWED, List.of(PREFIX + "Heat.m4v", PREFIX + "Heat (DC).m4v"));
        PlexFilm plexAlien = new PlexFilm("2", "Alien", "Alien", 1979, "Plex alien", "Final Cut", PLEX_ADDED,
            PLEX_ADDED, ALIEN_THUMB, null, 348L, 0, 0, null, List.of(PREFIX + "Alien.m4v"));
        when(plex.configured()).thenReturn(true);
        when(plex.movieSections()).thenReturn(List.of("1"));
        when(plex.films("1")).thenReturn(List.of(plexHeat, plexAlien));
        when(plex.collections("1")).thenReturn(List.of("Alien Collection"));
        when(plex.image(HEAT_THUMB, 600, 900)).thenReturn(new PlexImage(new byte[] {1, 1}, "image/jpeg"));
        when(plex.image(HEAT_ART, 1920, 1080)).thenReturn(new PlexImage(new byte[] {2, 2}, "image/jpeg"));
        when(plex.image(ALIEN_THUMB, 600, 900)).thenReturn(new PlexImage(new byte[] {3, 3}, "image/png"));
        when(tmdb.enabled()).thenReturn(true);
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949, "Heat")));
        when(tmdb.movie(348)).thenReturn(Optional.of(movie(348, "Alien")));
        service = new PlexImportService(plex, tmdb, ingest, writer, artwork, scanRuns, importRuns, new LibraryLock(),
            Runnable::run, CLOCK, PREFIX, events);
    }

    private PlexImportReport lastReport() throws Exception {
        em.flush();
        em.clear();
        PlexImportRun run = importRuns.findFirstByFinishedAtNotNullOrderByIdDesc().orElseThrow();
        assertThat(run.getError()).isNull();
        return JSON.readValue(run.getReport(), PlexImportReport.class);
    }

    @Test
    void anImportMergesMatchesOverlaysPlexAndStoresArtwork() throws Exception {
        service.start();

        PlexImportReport report = lastReport();
        assertThat(report).isEqualTo(new PlexImportReport(2, 2, 1, 0, 2, 2, 1, 0, 0, 0, 1, 1, 1, 0, List.of(),
            List.of(), 0, 0, List.of("Alien Collection"), 1));
        assertThat(films.findAll()).hasSize(2);
        Film heat = films.findById(heatA.getId()).orElseThrow();
        assertThat(heat.getEditions()).hasSize(2);
        assertThat(heat.getTitle()).isEqualTo("Heat");
        assertThat(heat.getOverview()).isEqualTo("Plex heat");
        assertThat(heat.getAddedAt()).isEqualTo(PLEX_ADDED);
        assertThat(heat.getPosterPath()).startsWith("/api/art/").endsWith(".jpg");
        assertThat(heat.getBackdropPath()).startsWith("/api/art/").endsWith(".jpg");
        assertThat(artwork.exists(heat.getPosterPath())).isTrue();
        assertThat(states.findById(heat.getEditions().get(0).getId()).orElseThrow().getPositionMs())
            .isEqualTo(30 * MIN);
        // the DC edition was played in CineFilm more recently than Plex's last view, so it is kept as it is
        assertThat(states.findById(heat.getEditions().get(1).getId()).orElseThrow().isWatched()).isFalse();
        assertThat(states.findById(heat.getEditions().get(1).getId()).orElseThrow().getPositionMs())
            .isEqualTo(10 * MIN);
        Film alienAfter = films.findById(alien.getId()).orElseThrow();
        assertThat(alienAfter.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(alienAfter.getTmdbId()).isEqualTo(348L);
        assertThat(alienAfter.getTitle()).isEqualTo("Alien");
        assertThat(alienAfter.getPosterPath()).endsWith(".png");
        assertThat(alienAfter.getBackdropPath()).isEqualTo("/tb.jpg");
        assertThat(alienAfter.getEditions().get(0).getName()).isEqualTo("Final Cut");
        assertThat(ingest.pending()).isEmpty();
    }

    @Test
    void aSecondRunMakesNoNewRowsDownloadsNothingAndChangesNothing() throws Exception {
        service.start();
        lastReport();
        long filmRows = films.count();
        long editionRows = editions.count();
        long stateRows = states.count();
        long fileRows = mediaFiles.count();
        long creditRows = credits.count();
        Film heat = films.findById(heatA.getId()).orElseThrow();
        String posterBefore = heat.getPosterPath();
        em.clear();

        service.start();

        PlexImportReport second = lastReport();
        assertThat(second).isEqualTo(new PlexImportReport(2, 2, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1, 0, List.of(),
            List.of(), 0, 0, List.of("Alien Collection"), 1));
        assertThat(films.count()).isEqualTo(filmRows);
        assertThat(editions.count()).isEqualTo(editionRows);
        assertThat(states.count()).isEqualTo(stateRows);
        assertThat(mediaFiles.count()).isEqualTo(fileRows);
        assertThat(credits.count()).isEqualTo(creditRows);
        assertThat(importRuns.count()).isEqualTo(2);
        assertThat(films.findById(heatA.getId()).orElseThrow().getPosterPath()).isEqualTo(posterBefore);
        verify(plex, times(1)).image(HEAT_THUMB, 600, 900);
        verify(plex, times(1)).image(HEAT_ART, 1920, 1080);
        verify(plex, times(1)).image(ALIEN_THUMB, 600, 900);
        verify(tmdb, times(1)).movie(949);
        verify(tmdb, times(1)).movie(348);
    }

    @Test
    void aLaterScanWhoseTmdbSearchWouldGiveADifferentHitLeavesTheImportedFilmsUnchanged() {
        service.start();
        em.flush();
        em.clear();
        LibraryScanner scanner = mock(LibraryScanner.class);
        TmdbMatcher matcher = mock(TmdbMatcher.class);
        when(scanner.scan()).thenReturn(new Found(List.of(new FoundFile("Heat.m4v", 10, MODIFIED, MODIFIED, false),
            new FoundFile("Heat (DC).m4v", 10, MODIFIED, MODIFIED, false),
            new FoundFile("Alien.m4v", 10, MODIFIED, MODIFIED, false)), 0));
        when(matcher.match(new gregochr.cinefilm.library.TitleGuess("Alien", 1979), 100 * MIN)).thenReturn(
            new MatchOutcome(Status.MATCHED, movie(8078, "Alien 3"), List.of()));
        ScanService scan = new ScanService(new LibraryRoot(System.getProperty("java.io.tmpdir")), scanner,
            mock(FfprobeService.class), tmdb, matcher, ingest, Runnable::run, CLOCK, new LibraryLock(),
            event -> { }, mock(gregochr.cinefilm.library.LibraryGrouping.class));

        scan.start("admin");

        em.flush();
        em.clear();
        Film after = films.findById(alien.getId()).orElseThrow();
        assertThat(after.getTmdbId()).isEqualTo(348L);
        assertThat(after.getTitle()).isEqualTo("Alien");
        assertThat(after.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(films.findAll()).hasSize(2);
        verifyNoInteractions(matcher);
    }

    @Test
    void whenNoFilePairsNothingIsWrittenAndTheRunRecordsWhy() {
        service = new PlexImportService(plex, tmdb, ingest, writer, artwork, scanRuns, importRuns, new LibraryLock(),
            Runnable::run, CLOCK, "/somewhere/else/", events);
        long filmRows = films.count();

        service.start();

        em.flush();
        em.clear();
        assertThat(importRuns.findFirstByFinishedAtNotNullOrderByIdDesc().orElseThrow().getError())
            .isEqualTo("No Plex file matches a file in the library; check PLEX_PATH_PREFIX");
        assertThat(films.count()).isEqualTo(filmRows);
        assertThat(films.findById(alien.getId()).orElseThrow().getMatchStatus()).isEqualTo(MatchStatus.PENDING);
        verify(plex, times(0)).image(HEAT_THUMB, 600, 900);
    }

    @Test
    void aTmdbOutageLeavesPlexMetadataAndTheFilmIsFetchedAgainNextRun() throws Exception {
        when(tmdb.movie(348)).thenThrow(new gregochr.cinefilm.tmdb.TmdbUnavailableException("down", null));

        service.start();

        PlexImportReport first = lastReport();
        assertThat(first.tmdbUnavailable()).isEqualTo(1);
        Film down = films.findById(alien.getId()).orElseThrow();
        assertThat(down.getTitle()).isEqualTo("Alien");
        assertThat(down.getTmdbId()).isEqualTo(348L);
        assertThat(down.getCredits()).isEmpty();
        em.clear();
        org.mockito.Mockito.doReturn(Optional.of(movie(348, "Alien"))).when(tmdb).movie(348);

        service.start();

        lastReport();
        assertThat(films.findById(alien.getId()).orElseThrow().getCredits()).hasSize(2);
    }
}
