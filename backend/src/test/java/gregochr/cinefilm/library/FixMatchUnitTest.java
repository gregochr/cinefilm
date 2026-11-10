package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.library.AdminDtos.MatchApplied;
import gregochr.cinefilm.library.AdminDtos.MatchResultDto;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.Credit;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/** {@link FixMatch} on its own: mocked collaborators, no Spring context and no database. */
class FixMatchUnitTest {

    private static final long FILM_ID = 7L;

    private TmdbApi tmdb;
    private FilmRepository films;
    private LibraryIngest ingest;
    private EditionJoin join;
    private GroupingChangeRepository changes;
    private LibraryLock lock;
    private FixMatch fix;

    @BeforeEach
    void setUp() {
        tmdb = mock(TmdbApi.class);
        films = mock(FilmRepository.class);
        ingest = mock(LibraryIngest.class);
        join = mock(EditionJoin.class);
        changes = mock(GroupingChangeRepository.class);
        lock = new LibraryLock();
        fix = new FixMatch(tmdb, films, ingest, join, changes, lock, mock(PlatformTransactionManager.class), CLOCK);
        when(films.existsById(FILM_ID)).thenReturn(true);
    }

    private static MovieDetails movie(long id, String title, Integer year, Integer runtime, List<Credit> directors,
                                      List<Credit> cast) {
        return new MovieDetails(id, title, title, year, null, runtime, List.of(), "/p" + id + ".jpg", null, 1.0,
            directors, cast, null);
    }

    private static Credit person(String name) {
        return new Credit(1, name, null, null);
    }

    private static Film film(Long id, String title) {
        return TestData.film(id, title, 1973, NOW);
    }

    @Test
    void searchShapesEachResultFromTheMovieDetails() {
        when(tmdb.search("Wicker Man", 1973)).thenReturn(List.of(new SearchHit(5, "x", "x", 1973, 1, null)));
        when(tmdb.movie(5)).thenReturn(Optional.of(movie(5, "The Wicker Man", 1973, 88,
            List.of(person("Robin Hardy"), person("Second Director")),
            List.of(person("A"), person("B"), person("C"), person("D"), person("E")))));

        List<MatchResultDto> results = fix.search(FILM_ID, "  Wicker Man  ", 1973);

        assertThat(results).containsExactly(new MatchResultDto(5, "The Wicker Man", 1973, 88, "/p5.jpg",
            "Robin Hardy, Second Director", List.of("A", "B", "C", "D")));
    }

    @Test
    void searchWithoutYearPassesNullAndALackOfDirectorsGivesNull() {
        when(tmdb.search("Dune", null)).thenReturn(List.of(new SearchHit(6, "Dune", "Dune", null, 1, null)));
        when(tmdb.movie(6)).thenReturn(Optional.of(movie(6, "Dune", null, null, List.of(), List.of())));

        assertThat(fix.search(FILM_ID, "Dune", null)).singleElement().satisfies(result -> {
            assertThat(result.director()).isNull();
            assertThat(result.cast()).isEmpty();
            assertThat(result.runtimeMinutes()).isNull();
            assertThat(result.year()).isNull();
        });
    }

    @Test
    void searchStopsAtEightResultsAndNeverAsksForTheNinth() {
        List<SearchHit> hits = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            hits.add(new SearchHit(id, "T", "T", 2000, 1, null));
            when(tmdb.movie(id)).thenReturn(Optional.of(movie(id, "T" + id, 2000, 90, List.of(), List.of())));
        }
        when(tmdb.search("T", null)).thenReturn(hits);

        List<MatchResultDto> results = fix.search(FILM_ID, "T", null);

        assertThat(results).extracting(MatchResultDto::tmdbId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        verify(tmdb, never()).movie(9);
        verify(tmdb, never()).movie(10);
    }

    @Test
    void searchSkipsAHitWithNoDetails() {
        when(tmdb.search("X", null)).thenReturn(List.of(new SearchHit(1, "a", "a", 1, 1, null),
            new SearchHit(2, "b", "b", 1, 1, null)));
        when(tmdb.movie(1)).thenReturn(Optional.empty());
        when(tmdb.movie(2)).thenReturn(Optional.of(movie(2, "b", 2000, 90, List.of(), List.of())));

        assertThat(fix.search(FILM_ID, "X", null)).extracting(MatchResultDto::tmdbId).containsExactly(2L);
    }

    @Test
    void searchTakesNoLibraryLock() {
        when(tmdb.search("X", null)).thenReturn(List.of());
        lock.tryAcquire(LibraryLock.SCAN);

        assertThat(fix.search(FILM_ID, "X", null)).isEmpty();
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
    }

    @Test
    void aBlankOrMissingQueryIs400() {
        assertThatThrownBy(() -> fix.search(FILM_ID, "  ", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fix.search(FILM_ID, null, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchAndApplyForAnUnknownFilmAre404() {
        assertThatThrownBy(() -> fix.search(99L, "X", null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> fix.apply(99L, 5L)).isInstanceOf(NotFoundException.class);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void searchWhenTmdbIsUnavailableIs503() {
        when(tmdb.search("X", null)).thenThrow(new TmdbUnavailableException("down", null));

        assertThatThrownBy(() -> fix.search(FILM_ID, "X", null)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void applyWithoutATmdbIdIs400AndATmdbIdTmdbDoesNotKnowIs404() {
        when(tmdb.movie(5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fix.apply(FILM_ID, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(NotFoundException.class);
        assertThat(lock.holder()).as("released after the failure").isNull();
    }

    @Test
    void applyWhenTmdbIsUnavailableIs503AndReleasesTheLock() {
        when(tmdb.movie(5)).thenThrow(new TmdbUnavailableException("down", null));

        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void applyWhileTheLibraryLockIsHeldIs409AndDoesNotReleaseSomeoneElsesLock() {
        lock.tryAcquire(LibraryLock.IMPORT);

        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");
        verify(tmdb, never()).movie(5);
        assertThat(lock.holder()).isEqualTo(LibraryLock.IMPORT);
    }

    @Test
    void applyWithNoOtherFilmSettlesTheFilmAndReleasesTheLock() {
        Film target = film(FILM_ID, "Wicker Man");
        MovieDetails movie = movie(16307, "The Wicker Man", 1973, 88, List.of(), List.of());
        when(tmdb.movie(16307)).thenReturn(Optional.of(movie));
        when(films.findById(FILM_ID)).thenReturn(Optional.of(target));
        when(films.findByTmdbIdOrderByIdAsc(16307L)).thenReturn(List.of(target));

        MatchApplied applied = fix.apply(FILM_ID, 16307L);

        assertThat(applied).isEqualTo(new MatchApplied(FILM_ID, false));
        verify(ingest).applyMovie(target, movie);
        assertThat(target.isEditionsConfirmed()).isTrue();
        verify(films).save(target);
        verify(join, never()).joinFilms(FILM_ID, FILM_ID);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void applyWhenAnotherFilmHasTheIdJoinsIntoItConfirmsItAndLogsTheJoin() {
        Film mine = film(FILM_ID, "Summerisle");
        Film existing = film(3L, "The Wicker Man");
        when(tmdb.movie(16307)).thenReturn(Optional.of(movie(16307, "The Wicker Man", 1973, 88, List.of(),
            List.of())));
        when(films.findById(FILM_ID)).thenReturn(Optional.of(mine));
        when(films.findById(3L)).thenReturn(Optional.of(existing));
        when(films.findByTmdbIdOrderByIdAsc(16307L)).thenReturn(List.of(existing, mine));

        MatchApplied applied = fix.apply(FILM_ID, 16307L);

        assertThat(applied).isEqualTo(new MatchApplied(3L, true));
        verify(join).joinFilms(FILM_ID, 3L);
        verify(ingest, never()).applyMovie(mine, movie(16307, "The Wicker Man", 1973, 88, List.of(), List.of()));
        assertThat(existing.isEditionsConfirmed()).isTrue();
        verify(films).save(existing);
        ArgumentCaptor<GroupingChange> saved = ArgumentCaptor.forClass(GroupingChange.class);
        verify(changes, times(1)).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(GroupingChange.JOIN);
        assertThat(saved.getValue().getHappenedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getSummary())
            .isEqualTo("You joined \"Summerisle\" into \"The Wicker Man\" with Fix match");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFailureWhileSettlingStillReleasesTheLock() {
        Film mine = film(FILM_ID, "Summerisle");
        when(tmdb.movie(5)).thenReturn(Optional.of(movie(5, "X", 2000, 90, List.of(), List.of())));
        when(films.findById(FILM_ID)).thenReturn(Optional.of(mine));
        when(films.findByTmdbIdOrderByIdAsc(5L)).thenReturn(List.of());
        when(films.save(mine)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(IllegalStateException.class);
        assertThat(lock.holder()).isNull();
    }
}
