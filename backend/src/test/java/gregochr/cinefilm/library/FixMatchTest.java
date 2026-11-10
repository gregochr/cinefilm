package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/** Fix match over real repositories and a fake TMDB. */
class FixMatchTest extends ApiTestBase {

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private EditionJoin join;

    @Autowired
    private GroupingChangeRepository groupingChanges;

    @Autowired
    private PlatformTransactionManager transactions;

    private TmdbApi tmdb;
    private LibraryLock lock;
    private FixMatch fix;

    @BeforeEach
    void setUp() {
        tmdb = mock(TmdbApi.class);
        lock = new LibraryLock();
        fix = new FixMatch(tmdb, films, ingest, join, groupingChanges, lock, transactions, CLOCK);
    }

    private static MovieDetails movie(long id, String title, int year) {
        return new MovieDetails(id, title, title, year, "About " + title, 93, List.of("Horror"), "/p" + id + ".jpg",
            "/b" + id + ".jpg", 5.0, List.of(new Credit(900 + id, "Robin Hardy", null, null)),
            List.of(new Credit(800 + id, "Edward Woodward", null, "Sgt Howie"),
                new Credit(700 + id, "Christopher Lee", null, "Lord Summerisle")), null);
    }

    private Film unmatched(String title, String path) {
        Film film = film(null, title, null, NOW);
        film.setMatchStatus(MatchStatus.NEEDS_MATCH);
        film.setMatchCandidates("[]");
        file(edition(film, null, "Original", 93 * MIN, null), path);
        return save(film);
    }

    @Test
    void searchReturnsAtMostEightResultsWithTheirDetailsAndNeverFetchesTheRest() {
        List<SearchHit> hits = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            hits.add(new SearchHit(id, "Wicker " + id, "Wicker " + id, 1973, 1.0, "/h" + id + ".jpg"));
            when(tmdb.movie(id)).thenReturn(Optional.of(movie(id, "Wicker " + id, 1973)));
        }
        when(tmdb.search("The Wicker Man", 1973)).thenReturn(hits);
        Film film = unmatched("The Wicker Man", "The Wicker Man.m4v");

        List<MatchResultDto> results = fix.search(film.getId(), "  The Wicker Man ", 1973);

        assertThat(results).hasSize(8);
        assertThat(results.get(0)).isEqualTo(new MatchResultDto(1, "Wicker 1", 1973, 93, "/p1.jpg", "Robin Hardy",
            List.of("Edward Woodward", "Christopher Lee")));
        verify(tmdb, never()).movie(9);
        assertThat(lock.holder()).as("the lock is given back").isNull();
    }

    @Test
    void searchSkipsAHitTmdbHasNoDetailsFor() {
        when(tmdb.search("Dune", null)).thenReturn(List.of(new SearchHit(1, "A", "A", 1984, 1, null),
            new SearchHit(2, "B", "B", 2021, 1, null)));
        when(tmdb.movie(1)).thenReturn(Optional.empty());
        when(tmdb.movie(2)).thenReturn(Optional.of(movie(2, "B", 2021)));
        Film film = unmatched("Dune", "Dune.m4v");

        assertThat(fix.search(film.getId(), "Dune", null)).extracting(MatchResultDto::tmdbId).containsExactly(2L);
    }

    @Test
    void aMovieWithNoDirectorOrCastStillListsWithoutThem() {
        when(tmdb.search("X", null)).thenReturn(List.of(new SearchHit(3, "X", "X", null, 1, null)));
        when(tmdb.movie(3)).thenReturn(Optional.of(new MovieDetails(3, "X", "X", null, null, null, List.of(), null,
            null, 1, List.of(), List.of(), null)));
        Film film = unmatched("X", "X.m4v");

        assertThat(fix.search(film.getId(), "X", null)).singleElement().satisfies(r -> {
            assertThat(r.director()).isNull();
            assertThat(r.cast()).isEmpty();
            assertThat(r.runtimeMinutes()).isNull();
        });
    }

    @Test
    void aBlankQueryIsRefusedBeforeAnythingIsTried() {
        assertThatThrownBy(() -> fix.search(1L, "   ", null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fix.search(1L, null, null)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tmdb);
    }

    @Test
    void searchForAnUnknownFilmIs404() {
        assertThatThrownBy(() -> fix.search(9999L, "Dune", null)).isInstanceOf(NotFoundException.class);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void searchDuringAScanStillWorksBecauseItOnlyReadsTmdb() {
        when(tmdb.search("Dune", null)).thenReturn(List.of(new SearchHit(2, "B", "B", 2021, 1, null)));
        when(tmdb.movie(2)).thenReturn(Optional.of(movie(2, "B", 2021)));
        Film film = unmatched("Dune", "Dune.m4v");
        lock.tryAcquire(LibraryLock.SCAN);

        assertThat(fix.search(film.getId(), "Dune", null)).hasSize(1);
        assertThat(lock.holder()).as("the scan keeps its lock").isEqualTo(LibraryLock.SCAN);
    }

    @Test
    void searchWhenTmdbIsUnavailableIs503AndReleasesTheLock() {
        when(tmdb.search("Dune", null)).thenThrow(new TmdbUnavailableException("down", null));
        Film film = unmatched("Dune", "Dune.m4v");

        assertThatThrownBy(() -> fix.search(film.getId(), "Dune", null))
            .isInstanceOf(ServiceUnavailableException.class);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void applyingAMatchSettlesTheFilmFromTmdbAndConfirmsIt() {
        when(tmdb.movie(16307)).thenReturn(Optional.of(movie(16307, "The Wicker Man", 1973)));
        Film film = unmatched("Wicker Man", "Wicker Man.m4v");

        MatchApplied applied = fix.apply(film.getId(), 16307L);

        assertThat(applied).isEqualTo(new MatchApplied(film.getId(), false));
        Film saved = films.findById(film.getId()).orElseThrow();
        assertThat(saved.getTmdbId()).isEqualTo(16307L);
        assertThat(saved.getTitle()).isEqualTo("The Wicker Man");
        assertThat(saved.getYear()).isEqualTo(1973);
        assertThat(saved.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(saved.getMatchCandidates()).isNull();
        assertThat(saved.isEditionsConfirmed()).isTrue();
        assertThat(saved.getCredits()).hasSize(3);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void applyingAMatchAnotherFilmAlreadyHasJoinsThisFilmIntoItWithoutTheGuard() {
        Film existing = film(null, "The Wicker Man", 1973, NOW);
        existing.setTmdbId(16307L);
        existing.setMatchStatus(MatchStatus.MATCHED);
        file(edition(existing, null, "Original", 93 * MIN, null), "The Wicker Man.m4v");
        existing = save(existing);
        Film other = unmatched("Summerisle", "Summerisle Cut.m4v");
        Long otherEdition = other.getEditions().get(0).getId();
        when(tmdb.movie(16307)).thenReturn(Optional.of(movie(16307, "The Wicker Man", 1973)));
        assertThat(GroupingGuard.sameFilm("The Wicker Man.m4v", "Summerisle Cut.m4v")).isFalse();

        MatchApplied applied = fix.apply(other.getId(), 16307L);

        assertThat(applied).isEqualTo(new MatchApplied(existing.getId(), true));
        assertThat(films.findById(other.getId())).isEmpty();
        Film survivor = films.findById(existing.getId()).orElseThrow();
        assertThat(survivor.isEditionsConfirmed()).isTrue();
        assertThat(survivor.getEditions()).hasSize(2).extracting(e -> e.getId()).contains(otherEdition);
        assertThat(groupingChanges.findTop20ByOrderByHappenedAtDescIdDesc()).singleElement().satisfies(change -> {
            assertThat(change.getKind()).isEqualTo(GroupingChange.JOIN);
            assertThat(change.getSummary()).isEqualTo(
                "You joined \"Summerisle\" into \"The Wicker Man\" with Fix match");
        });
    }

    @Test
    void applyingTheMatchTheFilmAlreadyHasJustConfirmsIt() {
        Film film = unmatched("Wicker Man", "Wicker Man.m4v");
        film.setTmdbId(16307L);
        when(tmdb.movie(16307)).thenReturn(Optional.of(movie(16307, "The Wicker Man", 1973)));

        assertThat(fix.apply(film.getId(), 16307L).joined()).isFalse();
        assertThat(films.findById(film.getId()).orElseThrow().isEditionsConfirmed()).isTrue();
    }

    @Test
    void applyNeedsATmdbIdAnExistingFilmAndAFilmTmdbKnows() {
        Film film = unmatched("Dune", "Dune.m4v");
        when(tmdb.movie(5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fix.apply(film.getId(), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> fix.apply(9999L, 5L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> fix.apply(film.getId(), 5L)).isInstanceOf(NotFoundException.class);
        assertThat(films.findById(film.getId()).orElseThrow().isEditionsConfirmed()).isFalse();
    }

    @Test
    void applyWhileAScanRunsIsA409AndChangesNothing() {
        Film film = unmatched("Dune", "Dune.m4v");
        lock.tryAcquire(LibraryLock.IMPORT);

        assertThatThrownBy(() -> fix.apply(film.getId(), 5L)).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");
        verify(tmdb, never()).movie(5);
    }

    @Test
    void applyWhenTmdbIsUnavailableIs503AndChangesNothing() {
        when(tmdb.movie(5)).thenThrow(new TmdbUnavailableException("down", null));
        Film film = unmatched("Dune", "Dune.m4v");

        assertThatThrownBy(() -> fix.apply(film.getId(), 5L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(films.findById(film.getId()).orElseThrow().getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aLongTitleDoesNotBreakTheJoinLine() {
        String title = "T".repeat(255);
        Film existing = film(null, title, 1973, NOW);
        existing.setTmdbId(7L);
        file(edition(existing, null, "Original", 93 * MIN, null), "a/T.m4v");
        existing = save(existing);
        Film other = unmatched(title, "b/U.m4v");
        when(tmdb.movie(7)).thenReturn(Optional.of(movie(7, title, 1973)));

        fix.apply(other.getId(), 7L);

        assertThat(groupingChanges.findTop20ByOrderByHappenedAtDescIdDesc().get(0).getSummary()).hasSize(500);
    }
}
