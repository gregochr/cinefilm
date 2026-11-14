package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What a file the different-film guard refuses is stored as, with every collaborator mocked: a tagged match (no
 * candidates of its own) becomes one candidate at score 100, and a title-search match keeps the matcher's candidates.
 */
@ExtendWith(MockitoExtension.class)
class LibraryIngestUnitTest {

    @Mock
    private FilmRepository films;

    @Mock
    private EditionRepository editions;

    @Mock
    private MediaFileRepository files;

    @Mock
    private PersonRepository people;

    @Mock
    private FilmPreferenceRepository preferences;

    @Mock
    private FilmCollectionRepository collections;

    @Mock
    private ScanRunRepository runs;

    @Mock
    private TmdbApi tmdb;

    private LibraryIngest ingest;

    @BeforeEach
    void setUp() {
        ingest = new LibraryIngest(films, editions, files, people, preferences, collections, runs, tmdb,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MovieDetails heat() {
        return new MovieDetails(949, "Heat", "Heat", 1995, "Overview", 170, List.of(), "/p.jpg", "/b.jpg", 1,
            List.of(), List.of(), null);
    }

    /** An existing film with TMDB id 949 whose file has another title, so the guard refuses a file called Heat. */
    private Film existingFilmWithADifferentTitle() {
        Film existing = film(1L, "Alien", 1979, NOW);
        file(edition(existing, 10L, "Original", 100 * 60_000L, null), "Alien.m4v");
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(existing));
        return existing;
    }

    private Film addHeat(MatchOutcome outcome) {
        FoundFile found = new FoundFile("Heat.m4v", 10, NOW, NOW, false);
        ProbeResult probe = new ProbeResult(170 * 60_000L, "mp4", "H.264", 1920, 800, 9_000_000L, List.of(), List.of(),
            new ProbeResult.Tags(null, null, null, null, null), false, "yuv420p");

        ingest.addNew(found, Playability.PLAYABLE, probe, TitleGuess.of(null, "Heat.m4v", null), outcome);

        ArgumentCaptor<Film> saved = ArgumentCaptor.forClass(Film.class);
        verify(films).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aTaggedMatchTheGuardRefusesIsNeedsMatchWithOneCandidateAtScore100() {
        existingFilmWithADifferentTitle();

        Film stored = addHeat(new MatchOutcome(Status.MATCHED, heat(), List.of()));

        assertThat(stored.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(stored.getTmdbId()).isNull();
        assertThat(stored.getMatchCandidates()).isEqualTo("[{\"tmdbId\":949,\"title\":\"Heat\",\"year\":1995,"
            + "\"runtimeMinutes\":170,\"posterPath\":\"/p.jpg\",\"score\":100}]");
    }

    @Test
    void aTitleSearchMatchTheGuardRefusesKeepsTheMatchersCandidatesAndTheirScores() {
        existingFilmWithADifferentTitle();
        List<Candidate> candidates = List.of(new Candidate(949, "Heat", 1995, 170, "/p.jpg", 85),
            new Candidate(950, "Heat 2", 2020, null, null, 61));

        Film stored = addHeat(new MatchOutcome(Status.MATCHED, heat(), candidates));

        assertThat(stored.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(stored.getMatchCandidates()).isEqualTo("[{\"tmdbId\":949,\"title\":\"Heat\",\"year\":1995,"
            + "\"runtimeMinutes\":170,\"posterPath\":\"/p.jpg\",\"score\":85},{\"tmdbId\":950,\"title\":\"Heat 2\","
            + "\"year\":2020,\"runtimeMinutes\":null,\"posterPath\":null,\"score\":61}]");
    }

    @Test
    void aMatchNoFilmHoldsYetIsNotRefusedAndIsApplied() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of());

        Film stored = addHeat(new MatchOutcome(Status.MATCHED, heat(), List.of()));

        assertThat(stored.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(stored.getTmdbId()).isEqualTo(949L);
        assertThat(stored.getMatchCandidates()).isNull();
    }
}
