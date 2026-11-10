package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminService;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.library.ProbeResult;
import gregochr.cinefilm.library.ScanRunRepository;
import gregochr.cinefilm.library.TitleGuess;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionRef;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Part;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Migration V2 and the scan's queries and bulk update on PostgreSQL, committed (not rolled back) like production. */
class LibraryScanIntegrationTest extends IntegrationTestBase {

    private static final Instant CREATED = NOW.minus(Duration.ofDays(3));

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private AdminService admin;

    @Autowired
    private FilmRepository films;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private FilmPreferenceRepository preferences;

    @Autowired
    private FilmCollectionRepository collections;

    @Autowired
    private ScanRunRepository runs;

    @MockitoBean
    private TmdbApi tmdb;

    @BeforeEach
    void clean() {
        preferences.deleteAll();
        states.deleteAll();
        films.deleteAll();
        collections.deleteAll();
        runs.deleteAll();
    }

    private static FoundFile found(String path, long size) {
        return new FoundFile(path, size, CREATED, CREATED, false);
    }

    private static ProbeResult probe(long durationMs) {
        return new ProbeResult(durationMs, "mp4", "H.264", 1920, 800, 9_000_000L,
            List.of(new ProbeResult.Audio(1, "English", "5.1", "AC-3", null, true, true)),
            List.of(new ProbeResult.Subtitle(2, "English", true, "English (forced)", "mov_text")),
            new ProbeResult.Tags(null, null, null, null, null), false);
    }

    private static MovieDetails heat() {
        return new MovieDetails(949, "Heat", "Heat", 1995, "A crew of thieves.", 170, List.of("Crime", "Drama"),
            "/p.jpg", "/b.jpg", 10, List.of(new TmdbModels.Credit(100, "Michael Mann", null, null)),
            List.of(new TmdbModels.Credit(200, "Al Pacino", null, "Vincent Hanna")),
            new CollectionRef(55, "Heat Collection", "/c.jpg"));
    }

    @Test
    void theScanStoresAndReadsEverythingItNeedsOnPostgres() {
        when(tmdb.collection(55)).thenReturn(Optional.of(new CollectionDetails(55, "Heat Collection", "/c.jpg",
            List.of(new Part(949, "Heat", 1995, "/h.jpg")))));
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            new MatchOutcome(Status.MATCHED, heat(), List.of()));
        ingest.addNew(found("Dune.m4v", 11), Playability.PLAYABLE, probe(2), new TitleGuess("Dune", null),
            new MatchOutcome(Status.NEEDS_MATCH, null, List.of(new Candidate(841, "Dune", 1984, 136, "/d.jpg", 50))));
        ingest.addNew(found("Drm.m4v", 12), Playability.PROTECTED, probe(3), new TitleGuess("Drm", null), null);
        ingest.addNew(found("Old.avi", 13), Playability.NEEDS_CONVERSION, null, new TitleGuess("Old", null), null);

        assertThat(ingest.known()).containsOnlyKeys("Heat.m4v", "Dune.m4v", "Drm.m4v", "Old.avi");
        assertThat(ingest.known().get("Heat.m4v").modifiedAt()).isEqualTo(CREATED);
        assertThat(admin.status().counts()).isEqualTo(new gregochr.cinefilm.library.AdminDtos.CountsDto(2, 0, 0, 1,
            1, 1, 0));
        assertThat(admin.attention().items()).extracting(AttentionItem::kind)
            .containsExactly("needs-match", "protected", "needs-conversion");
        assertThat(admin.attention().items().get(0).candidates()).singleElement()
            .satisfies(c -> assertThat(c.runtimeMinutes()).isEqualTo(136));
        Film heat = films.findFirstByTmdbIdOrderByIdAsc(949L).orElseThrow();
        assertThat(heat.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(collections.findFirstByTmdbIdOrderByIdAsc(55L).orElseThrow().getName()).isEqualTo("Heat Collection");
    }

    @Test
    void missingFilesKeepTheirRowsAndAMissingCountIsReported() {
        ingest.addNew(found("Gone.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Gone", null), null);
        Long id = ingest.known().get("Gone.m4v").id();

        ingest.markMissing(List.of(id));

        assertThat(mediaFiles.findById(id).orElseThrow().getPresence()).isEqualTo(Presence.MISSING);
        assertThat(mediaFiles.countOriginals(Presence.MISSING)).isEqualTo(1);
        assertThat(admin.status().counts().missing()).isEqualTo(1);
        assertThat(admin.status().counts().films()).isZero();
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void aPendingFilmThatMatchesAnExistingOneIsMergedByTheBulkEditionMoveKeepingItsHistory() {
        when(tmdb.collection(55)).thenReturn(Optional.empty());
        MovieDetails heat = heat();
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            new MatchOutcome(Status.MATCHED, heat, List.of()));
        ingest.addNew(found("Heat - HD.m4v", 11), Playability.PLAYABLE, probe(2), new TitleGuess("Heat - HD", null),
            null);
        Long pendingFilm = ingest.pending().get(0).filmId();
        Long copyEdition = mediaFiles.findById(ingest.known().get("Heat - HD.m4v").id()).orElseThrow()
            .getEdition().getId();
        PlaybackState state = new PlaybackState(copyEdition);
        state.setPositionMs(4242);
        states.save(state);

        ingest.applyOutcome(pendingFilm, new MatchOutcome(Status.MATCHED, heat, List.of()));

        assertThat(films.count()).isEqualTo(1);
        assertThat(states.findById(copyEdition).orElseThrow().getPositionMs()).isEqualTo(4242);
        assertThat(mediaFiles.findAll()).hasSize(2);
        assertThat(admin.attention().items()).extracting(AttentionItem::kind).containsExactly("multiple-files");
    }

    @Test
    void scanRunsAreRecordedAndTheLastFinishedOneIsFound() {
        Long first = ingest.startRun("nightly", NOW.minusSeconds(100));
        ingest.finishRun(first, NOW.minusSeconds(50), 5, 1, 0, null);
        ingest.startRun("admin", NOW);

        assertThat(admin.status().lastScan().trigger()).isEqualTo("nightly");
        assertThat(admin.status().lastScan().filesSeen()).isEqualTo(5);
    }
}
