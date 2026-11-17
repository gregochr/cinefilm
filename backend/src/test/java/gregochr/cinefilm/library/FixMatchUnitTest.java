package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.library.AdminDtos.MatchApplied;
import gregochr.cinefilm.library.AdminDtos.MatchResultDto;
import gregochr.cinefilm.library.AdminDtos.MatchSearchDto;
import gregochr.cinefilm.search.LibraryChanged;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** {@link FixMatch} on its own: mocked collaborators, no Spring context and no database. */
class FixMatchUnitTest {

    private static final long FILM_ID = 7L;

    private TmdbApi tmdb;
    private FilmRepository films;
    private LibraryIngest ingest;
    private EditionJoin join;
    private FilmPreferenceRepository preferences;
    private GroupingChangeRepository changes;
    private PlatformTransactionManager transactions;
    private LibraryLock lock;
    private ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private FixMatch fix;

    @BeforeEach
    void setUp() {
        tmdb = mock(TmdbApi.class);
        films = mock(FilmRepository.class);
        ingest = mock(LibraryIngest.class);
        join = mock(EditionJoin.class);
        preferences = mock(FilmPreferenceRepository.class);
        changes = mock(GroupingChangeRepository.class);
        transactions = mock(PlatformTransactionManager.class);
        lock = new LibraryLock();
        fix = new FixMatch(tmdb, films, preferences, ingest, join, changes, lock,
            transactions, CLOCK, events);
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
        givenFilm(-1, null);
        when(tmdb.search("Wicker Man", 1973)).thenReturn(List.of(new SearchHit(5, "x", "x", 1973, 1, null)));
        when(tmdb.movie(5)).thenReturn(Optional.of(movie(5, "The Wicker Man", 1973, 88,
            List.of(person("Robin Hardy"), person("Second Director")),
            List.of(person("A"), person("B"), person("C"), person("D"), person("E")))));

        List<MatchResultDto> results = fix.search(FILM_ID, "  Wicker Man  ", 1973).results();

        assertThat(results).containsExactly(new MatchResultDto(5, "The Wicker Man", 1973, 88, "/p5.jpg",
            "Robin Hardy, Second Director", List.of("A", "B", "C", "D")));
        // Searching only reads: its transaction is read-only.
        verify(transactions).getTransaction(argThat(TransactionDefinition::isReadOnly));
    }

    private static final long FILE_MS = 107 * MIN;

    /** The film has one edition of the given length (negative for none) and the given stored candidate JSON. */
    private Film givenFilm(long editionMs, String candidates) {
        Film target = film(FILM_ID, "Enchanted");
        if (editionMs >= 0) {
            edition(target, 70L, "Original", editionMs, null);
        }
        target.setMatchCandidates(candidates);
        when(films.findById(FILM_ID)).thenReturn(Optional.of(target));
        return target;
    }

    private static String guessJson(long tmdbId) {
        return "[{\"tmdbId\":" + tmdbId + ",\"title\":\"T\",\"year\":2007,\"runtimeMinutes\":107,"
            + "\"posterPath\":null,\"score\":70},{\"tmdbId\":999,\"title\":\"U\",\"year\":2007,"
            + "\"runtimeMinutes\":90,\"posterPath\":null,\"score\":50}]";
    }

    /** TMDB answers the search "Enchanted" with these films: pairs of id and runtime in minutes (0 for unknown). */
    private void tmdbReturns(long... idsAndRuntimes) {
        List<SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < idsAndRuntimes.length; i += 2) {
            long id = idsAndRuntimes[i];
            hits.add(new SearchHit(id, "T", "T", 2007, 1, null));
            Integer runtime = idsAndRuntimes[i + 1] == 0 ? null : (int) idsAndRuntimes[i + 1];
            when(tmdb.movie(id)).thenReturn(Optional.of(movie(id, "T" + id, 2007, runtime, List.of(), List.of())));
        }
        when(tmdb.search("Enchanted", null)).thenReturn(hits);
    }

    @Test
    void oneResultOfTheFilesLengthIsSuggestedBecauseOfRuntime() {
        givenFilm(FILE_MS, guessJson(2));
        tmdbReturns(1, 90, 2, 120, 3, 108);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.fileRuntimeMs()).isEqualTo(FILE_MS);
        assertThat(answer.suggestedTmdbId()).isEqualTo(3L);
        assertThat(answer.suggestedBecause()).isEqualTo("runtime");
        assertThat(answer.results()).extracting(MatchResultDto::tmdbId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void twoResultsOfTheFilesLengthFallBackToTheStoredGuessAmongThem() {
        givenFilm(FILE_MS, guessJson(2));
        tmdbReturns(1, 106, 2, 108, 3, 90);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.suggestedTmdbId()).isEqualTo(2L);
        assertThat(answer.suggestedBecause()).isEqualTo("guess");
    }

    @Test
    void twoResultsOfTheFilesLengthAndNoGuessAmongThemSuggestNothing() {
        givenFilm(FILE_MS, guessJson(77));
        tmdbReturns(1, 106, 2, 108, 3, 90);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.suggestedTmdbId()).isNull();
        assertThat(answer.suggestedBecause()).isNull();
    }

    @Test
    void noResultOfTheFilesLengthSuggestsAGuessWhoseRuntimeIsUnknown() {
        givenFilm(FILE_MS, guessJson(3));
        tmdbReturns(1, 90, 3, 0);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.suggestedTmdbId()).isEqualTo(3L);
        assertThat(answer.suggestedBecause()).isEqualTo("guess");
    }

    @Test
    void aGuessWhoseRuntimeContradictsTheFileIsNotSuggested() {
        givenFilm(FILE_MS, guessJson(1));
        tmdbReturns(1, 90, 2, 130);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.suggestedTmdbId()).isNull();
        assertThat(answer.fileRuntimeMs()).isEqualTo(FILE_MS);
    }

    @Test
    void anUnknownFileLengthSuggestsTheGuessAndReportsNoLength() {
        givenFilm(0, guessJson(1));
        tmdbReturns(1, 90, 2, 107);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.fileRuntimeMs()).isNull();
        assertThat(answer.suggestedTmdbId()).isEqualTo(1L);
        assertThat(answer.suggestedBecause()).isEqualTo("guess");
    }

    @Test
    void aFilmWithNoEditionHasNoLengthAndNoStoredCandidatesSuggestNothing() {
        givenFilm(-1, null);
        tmdbReturns(1, 90, 2, 107);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.fileRuntimeMs()).isNull();
        assertThat(answer.suggestedTmdbId()).isNull();
        assertThat(answer.suggestedBecause()).isNull();
    }

    @Test
    void emptyStoredCandidatesSuggestNothing() {
        givenFilm(FILE_MS, "[]");
        tmdbReturns(1, 90, 2, 130);

        assertThat(fix.search(FILM_ID, "Enchanted", null).suggestedTmdbId()).isNull();
    }

    @Test
    void malformedStoredCandidatesSuggestNothing() {
        givenFilm(FILE_MS, "{not json");
        tmdbReturns(1, 90, 2, 130);

        assertThat(fix.search(FILM_ID, "Enchanted", null).suggestedTmdbId()).isNull();
    }

    @Test
    void theLengthComesFromTheStoredDefaultEditionWhenThereIsOne() {
        Film target = givenFilm(FILE_MS, "[]");
        edition(target, 71L, "Extended", 150 * MIN, null);
        FilmPreference stored = new FilmPreference(FILM_ID);
        stored.setDefaultEditionId(71L);
        when(preferences.findById(FILM_ID)).thenReturn(Optional.of(stored));
        tmdbReturns(1, 90, 2, 150);

        MatchSearchDto answer = fix.search(FILM_ID, "Enchanted", null);

        assertThat(answer.fileRuntimeMs()).isEqualTo(150 * MIN);
        assertThat(answer.suggestedTmdbId()).isEqualTo(2L);
    }

    @Test
    void searchWithoutYearPassesNullAndALackOfDirectorsGivesNull() {
        givenFilm(-1, null);
        when(tmdb.search("Dune", null)).thenReturn(List.of(new SearchHit(6, "Dune", "Dune", null, 1, null)));
        when(tmdb.movie(6)).thenReturn(Optional.of(movie(6, "Dune", null, null, List.of(), List.of())));

        assertThat(fix.search(FILM_ID, "Dune", null).results()).singleElement().satisfies(result -> {
            assertThat(result.director()).isNull();
            assertThat(result.cast()).isEmpty();
            assertThat(result.runtimeMinutes()).isNull();
            assertThat(result.year()).isNull();
        });
    }

    @Test
    void searchStopsAtEightResultsAndNeverAsksForTheNinth() {
        givenFilm(-1, null);
        List<SearchHit> hits = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            hits.add(new SearchHit(id, "T", "T", 2000, 1, null));
            when(tmdb.movie(id)).thenReturn(Optional.of(movie(id, "T" + id, 2000, 90, List.of(), List.of())));
        }
        when(tmdb.search("T", null)).thenReturn(hits);

        List<MatchResultDto> results = fix.search(FILM_ID, "T", null).results();

        assertThat(results).extracting(MatchResultDto::tmdbId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        verify(tmdb, never()).movie(9);
        verify(tmdb, never()).movie(10);
    }

    @Test
    void searchSkipsAHitWithNoDetails() {
        givenFilm(-1, null);
        when(tmdb.search("X", null)).thenReturn(List.of(new SearchHit(1, "a", "a", 1, 1, null),
            new SearchHit(2, "b", "b", 1, 1, null)));
        when(tmdb.movie(1)).thenReturn(Optional.empty());
        when(tmdb.movie(2)).thenReturn(Optional.of(movie(2, "b", 2000, 90, List.of(), List.of())));

        assertThat(fix.search(FILM_ID, "X", null).results()).extracting(MatchResultDto::tmdbId).containsExactly(2L);
    }

    @Test
    void searchTakesNoLibraryLock() {
        givenFilm(-1, null);
        when(tmdb.search("X", null)).thenReturn(List.of());
        lock.tryAcquire(LibraryLock.SCAN);

        assertThat(fix.search(FILM_ID, "X", null).results()).isEmpty();
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
    }

    @Test
    void aBlankOrMissingQueryIs400() {
        assertThatThrownBy(() -> fix.search(FILM_ID, "  ", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fix.search(FILM_ID, null, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchAndApplyForAnUnknownFilmAre404() {
        assertThatThrownBy(() -> fix.search(99L, "X", null)).isInstanceOf(NotFoundException.class)
            .hasMessage("No film 99");
        verifyNoInteractions(tmdb);
        assertThatThrownBy(() -> fix.apply(99L, 5L)).isInstanceOf(NotFoundException.class);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void searchWhenTmdbIsUnavailableIs503() {
        givenFilm(-1, null);
        when(tmdb.search("X", null)).thenThrow(new TmdbUnavailableException("down", null));

        assertThatThrownBy(() -> fix.search(FILM_ID, "X", null)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void applyWithoutATmdbIdIs400AndATmdbIdTmdbDoesNotKnowIs404() {
        when(films.existsById(FILM_ID)).thenReturn(true);
        when(tmdb.movie(5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fix.apply(FILM_ID, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(NotFoundException.class);
        assertThat(lock.holder()).as("released after the failure").isNull();
    }

    @Test
    void applyWhenTmdbIsUnavailableIs503AndReleasesTheLock() {
        when(films.existsById(FILM_ID)).thenReturn(true);
        when(tmdb.movie(5)).thenThrow(new TmdbUnavailableException("down", null));

        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(ServiceUnavailableException.class);
        verify(events, never()).publishEvent(new LibraryChanged());
        assertThat(lock.holder()).isNull();
    }

    @Test
    void applyWhileTheLibraryLockIsHeldIs409AndDoesNotReleaseSomeoneElsesLock() {
        lock.tryAcquire(LibraryLock.IMPORT);

        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(ConflictException.class)
            .hasMessage("A Plex import is running");
        verify(tmdb, never()).movie(5);
        assertThat(lock.holder()).isEqualTo(LibraryLock.IMPORT);
    }

    @Test
    void applyWithNoOtherFilmSettlesTheFilmAndReleasesTheLock() {
        when(films.existsById(FILM_ID)).thenReturn(true);
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
        verify(events).publishEvent(new LibraryChanged());
        assertThat(lock.holder()).isNull();
        // Applying writes: its transaction is not read-only.
        verify(transactions).getTransaction(argThat(definition -> !definition.isReadOnly()));
    }

    @Test
    void applyWhenAnotherFilmHasTheIdJoinsIntoItConfirmsItAndLogsTheJoin() {
        when(films.existsById(FILM_ID)).thenReturn(true);
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
        verify(events).publishEvent(new LibraryChanged());
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFailureWhileSettlingStillReleasesTheLock() {
        when(films.existsById(FILM_ID)).thenReturn(true);
        Film mine = film(FILM_ID, "Summerisle");
        when(tmdb.movie(5)).thenReturn(Optional.of(movie(5, "X", 2000, 90, List.of(), List.of())));
        when(films.findById(FILM_ID)).thenReturn(Optional.of(mine));
        when(films.findByTmdbIdOrderByIdAsc(5L)).thenReturn(List.of());
        when(films.save(mine)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> fix.apply(FILM_ID, 5L)).isInstanceOf(IllegalStateException.class);
        assertThat(lock.holder()).isNull();
    }
}
