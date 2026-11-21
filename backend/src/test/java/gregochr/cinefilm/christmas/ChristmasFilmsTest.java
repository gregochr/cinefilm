package gregochr.cinefilm.christmas;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.ZONE;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.christmas.ChristmasDtos.ChristmasView;
import gregochr.cinefilm.christmas.ChristmasDtos.FilmState;
import gregochr.cinefilm.christmas.ChristmasDtos.Item;
import gregochr.cinefilm.christmas.ChristmasDtos.Keywords;
import gregochr.cinefilm.christmas.ChristmasDtos.LeftOutItem;
import gregochr.cinefilm.christmas.ChristmasDtos.Limits;
import gregochr.cinefilm.christmas.ChristmasDtos.Reason;
import gregochr.cinefilm.christmas.ChristmasDtos.State;
import gregochr.cinefilm.christmas.ChristmasDtos.WeekDto;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.ChristmasChoice;
import gregochr.cinefilm.film.ChristmasOverrides;
import gregochr.cinefilm.film.ChristmasOverrides.Snapshot;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.KeywordsRefresher;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class ChristmasFilmsTest {

    @Mock
    private LibraryLoader loader;

    @Mock
    private ChristmasOverrides overrides;

    @Mock
    private FilmRepository films;

    @Mock
    private KeywordsRefresher keywords;

    @Mock
    private PlatformTransactionManager transactions;

    private LibraryLock lock;
    private ChristmasFilms service;

    @BeforeEach
    void setUp() {
        lock = new LibraryLock();
        service = serviceAt(CLOCK);
    }

    private ChristmasFilms serviceAt(Clock clock) {
        return new ChristmasFilms(loader, overrides, films, keywords, lock, clock, transactions);
    }

    private static Clock on(String date) {
        return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZONE);
    }

    private static Film mk(long id, String title, Integer year, Long tmdbId, String... keywords) {
        Film film = film(id, title, year, NOW, "Comedy");
        film.setTmdbId(tmdbId);
        film.getKeywords().addAll(List.of(keywords));
        return film;
    }

    private void library(Film... library) {
        when(loader.loadAll()).thenReturn(java.util.Arrays.stream(library).map(f -> view(f)).toList());
    }

    private void overrides(Set<Long> include, Set<Long> exclude) {
        when(overrides.snapshot()).thenReturn(new Snapshot(include, exclude));
    }

    @Test
    void excludeBeatsIncludeBeatsTheKeywordAndTheOrderIsYearThenSortTitle() {
        Film excludedAndTagged = mk(1, "Excluded", 1950, 11L, "christmas");
        Film excludedAndIncluded = mk(2, "Both", 1951, 12L);
        Film includedAndTagged = mk(3, "Included tagged", 1980, 13L, "christmas");
        Film laterTitle = mk(4, "B title", 1990, 14L, "christmas");
        Film earlierTitle = mk(5, "A title", 1990, 15L, "christmas");
        Film noYear = mk(6, "No year", null, 16L);
        Film untagged = mk(7, "Untagged", 1960, 17L);
        library(excludedAndTagged, excludedAndIncluded, includedAndTagged, laterTitle, earlierTitle, noYear,
            untagged);
        overrides(Set.of(12L, 13L, 16L), Set.of(11L, 12L));

        ChristmasView view = service.view();

        assertThat(view.inWeek()).extracting(Item::filmId).containsExactly(3L, 5L, 4L, 6L);
        assertThat(view.inWeek()).extracting(Item::reason)
            .containsExactly(Reason.KEYWORD, Reason.KEYWORD, Reason.KEYWORD, Reason.ADDED);
        assertThat(view.inWeek().get(0)).isEqualTo(new Item(3L, 13L, "Included tagged", 1980, null, Reason.KEYWORD));
    }

    @Test
    void addedHoldsIncludedFilmsTmdbDoesNotTagAndLeftOutHoldsExcludedOnesWithWhetherTmdbTagsThem() {
        Film added = mk(1, "Added", 1999, 11L);
        Film includedAndTagged = mk(2, "Redundant", 1980, 12L, "christmas");
        Film leftOutTagged = mk(3, "Tagged out", 1990, 13L, "christmas");
        Film leftOutPlain = mk(4, "Plain out", 1970, 14L);
        Film leftOutNoYear = mk(5, "Unknown year out", null, 15L);
        Film noTmdbId = mk(6, "No id", 1985, null);
        library(added, includedAndTagged, leftOutTagged, leftOutPlain, leftOutNoYear, noTmdbId);
        overrides(Set.of(11L, 12L, 777L), Set.of(13L, 14L, 15L, 888L));

        ChristmasView view = service.view();

        assertThat(view.added()).containsExactly(new Item(1L, 11L, "Added", 1999, null, Reason.ADDED));
        assertThat(view.leftOut()).containsExactly(
            new LeftOutItem(4L, 14L, "Plain out", 1970, null, false),
            new LeftOutItem(3L, 13L, "Tagged out", 1990, null, true),
            new LeftOutItem(5L, 15L, "Unknown year out", null, null, false));
        assertThat(view.inWeek()).as("overrides for no library film are ignored")
            .extracting(Item::tmdbId).doesNotContain(777L, 888L);
    }

    @Test
    void aFilmWithNoTmdbIdAndTheKeywordStillCountsAsHomeCountsIt() {
        library(mk(1, "Legacy", 1970, null, "christmas"));
        overrides(Set.of(), Set.of());

        assertThat(service.view().inWeek()).containsExactly(new Item(1L, null, "Legacy", 1970, null, Reason.KEYWORD));
    }

    @Test
    void posterPathIsPassedThrough() {
        Film film = mk(1, "Poster", 1970, 1L, "christmas");
        film.setPosterPath("/p.jpg");
        library(film);
        overrides(Set.of(), Set.of());

        assertThat(service.view().inWeek().get(0).posterUrl()).isEqualTo("/p.jpg");
    }

    @ParameterizedTest
    @CsvSource({"0,true", "4,true", "5,false", "6,false"})
    void fallsBackBelowFiveFilmsAndNotAtFive(int count, boolean fallsBack) {
        Film[] all = LongStream.rangeClosed(1, count).mapToObj(i -> mk(i, "Film " + i, 1980 + (int) i, i, "christmas"))
            .toArray(Film[]::new);
        library(all);
        overrides(Set.of(), Set.of());

        ChristmasView view = service.view();

        assertThat(view.inWeek()).hasSize(count);
        assertThat(view.fallsBack()).isEqualTo(fallsBack);
        assertThat(view.limits()).isEqualTo(new Limits(12, 5));
    }

    @Test
    void inWeekIsNotCappedAtTwelve() {
        List<Film> all = new ArrayList<>();
        for (long i = 1; i <= 14; i++) {
            all.add(mk(i, "Film " + i, 1950 + (int) i, i, "christmas"));
        }
        library(all.toArray(Film[]::new));
        overrides(Set.of(), Set.of());

        assertThat(service.view().inWeek()).hasSize(14);
    }

    @Test
    void keywordsReportFetchedTotalAndWhetherARunIsGoing() {
        library();
        overrides(Set.of(), Set.of());
        when(films.countByTmdbIdIsNotNullAndKeywordsFetchedAtIsNotNull()).thenReturn(7L);
        when(films.countByTmdbIdIsNotNull()).thenReturn(9L);
        when(keywords.isRunning()).thenReturn(true);

        assertThat(service.view().keywords()).isEqualTo(new Keywords(7, 9, true, false, false));
    }

    @Test
    void theWeekIsThisYearsUntilItsSundayEndsThenNextYears() {
        library();
        overrides(Set.of(), Set.of());

        assertThat(serviceAt(CLOCK).view().week()).as("2024-03-31: this year's")
            .isEqualTo(new WeekDto(LocalDate.of(2024, 12, 16), LocalDate.of(2024, 12, 22)));
        assertThat(serviceAt(on("2026-12-20")).view().week()).as("the Sunday it ends")
            .isEqualTo(new WeekDto(LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 20)));
        assertThat(serviceAt(on("2026-12-21")).view().week()).as("the Monday after")
            .isEqualTo(new WeekDto(LocalDate.of(2027, 12, 13), LocalDate.of(2027, 12, 19)));
    }

    @Test
    void inTheYearEighteenDecemberIsAMondayTheWeekStartsOnItAndRollsOverAfterTheTwentyFourth() {
        library();
        overrides(Set.of(), Set.of());

        assertThat(serviceAt(on("2023-12-01")).view().week())
            .isEqualTo(new WeekDto(LocalDate.of(2023, 12, 18), LocalDate.of(2023, 12, 24)));
        assertThat(serviceAt(on("2023-12-24")).view().week().end()).isEqualTo(LocalDate.of(2023, 12, 24));
        assertThat(serviceAt(on("2023-12-25")).view().week())
            .isEqualTo(new WeekDto(LocalDate.of(2024, 12, 16), LocalDate.of(2024, 12, 22)));
    }

    @Test
    void choosingSavesTheChoiceAndAnswersTheScreen() {
        Film film = mk(1, "Added", 1999, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(film));
        library(film);
        overrides(Set.of(11L), Set.of());

        ChristmasView view = service.choose(11L, ChristmasChoice.INCLUDE);

        verify(overrides).set(11L, ChristmasChoice.INCLUDE);
        verify(overrides, never()).remove(11L);
        assertThat(view.added()).extracting(Item::filmId).containsExactly(1L);
    }

    @Test
    void aNullChoiceForgetsTheOverride() {
        Film film = mk(1, "Film", 1999, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(film));
        library(film);
        overrides(Set.of(), Set.of());

        service.choose(11L, null);

        verify(overrides).remove(11L);
        verify(overrides, never()).set(11L, ChristmasChoice.INCLUDE);
        verify(overrides, never()).set(11L, ChristmasChoice.EXCLUDE);
    }

    @Test
    void anIncludeRowForAFilmTmdbTagsIsNeverDeletedByAWrite() {
        Film tagged = mk(1, "Tagged", 1980, 11L, "christmas");
        Film other = mk(2, "Other", 1990, 12L);
        when(films.findByTmdbIdOrderByIdAsc(12L)).thenReturn(List.of(other));
        library(tagged, other);
        overrides(Set.of(11L, 12L), Set.of());

        ChristmasView view = service.choose(12L, ChristmasChoice.INCLUDE);

        verify(overrides, never()).remove(11L);
        verify(overrides, never()).remove(12L);
        assertThat(view.inWeek()).extracting(Item::tmdbId, Item::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(11L, Reason.KEYWORD),
                org.assertj.core.groups.Tuple.tuple(12L, Reason.ADDED));
        assertThat(view.added()).extracting(Item::tmdbId).containsExactly(12L);
    }

    @Test
    void theReasonFollowsTheKeywordOnEveryReadWithoutAWrite() {
        Film film = mk(1, "Film", 1980, 11L, "christmas");
        library(film);
        overrides(Set.of(11L), Set.of());
        assertThat(service.view().inWeek()).extracting(Item::reason).containsExactly(Reason.KEYWORD);

        film.getKeywords().clear();

        assertThat(service.view().inWeek()).extracting(Item::reason).containsExactly(Reason.ADDED);
        assertThat(service.view().added()).extracting(Item::filmId).containsExactly(1L);
    }

    @Test
    void twoFilmRowsWithOneTmdbIdKeepTheIncludeWhenOnlyOneIsTagged() {
        Film tagged = mk(1, "Tagged twin", 1980, 11L, "christmas");
        Film untagged = mk(2, "Untagged twin", 1981, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(tagged, untagged));
        library(tagged, untagged);
        overrides(Set.of(11L), Set.of());

        ChristmasView view = service.choose(11L, ChristmasChoice.INCLUDE);

        verify(overrides, never()).remove(11L);
        assertThat(view.inWeek()).extracting(Item::filmId, Item::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, Reason.KEYWORD),
                org.assertj.core.groups.Tuple.tuple(2L, Reason.ADDED));
    }

    @Test
    void theLibraryLockIsHeldForTheWholeWriteAndGivenBackAfterSuccess() throws Exception {
        Film film = mk(1, "Film", 1999, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(film));
        library(film);
        overrides(Set.of(11L), Set.of());
        List<String> heldDuringWrite = new ArrayList<>();
        doAnswer(call -> heldDuringWrite.add(lock.holder())).when(overrides).set(11L, ChristmasChoice.INCLUDE);

        service.choose(11L, ChristmasChoice.INCLUDE);

        assertThat(heldDuringWrite).containsExactly(LibraryLock.CHRISTMAS);
        assertThat(CompletableFuture.supplyAsync(lock::holder).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void theLibraryLockIsGivenBackAfterAFailedWriteAndAfterANotFound() throws Exception {
        Film film = mk(1, "Film", 1999, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(film));
        when(films.findByTmdbIdOrderByIdAsc(99L)).thenReturn(List.of());
        doThrow(new IllegalStateException("db down")).when(overrides).set(11L, ChristmasChoice.INCLUDE);

        assertThatThrownBy(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .isInstanceOf(IllegalStateException.class);
        assertThat(CompletableFuture.supplyAsync(lock::holder).get(5, TimeUnit.SECONDS)).isNull();
        assertThatThrownBy(() -> service.choose(99L, ChristmasChoice.INCLUDE))
            .isInstanceOf(NotFoundException.class);
        assertThat(CompletableFuture.supplyAsync(lock::holder).get(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void aScanHoldingTheLibraryLockRefusesFromAnotherThreadAndKeepsItsLock() throws Exception {
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> CompletableFuture.supplyAsync(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .get(5, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(ConflictException.class)
            .hasRootCauseMessage("A scan is running — try again when it finishes");
        assertThat(lock.holder()).as("the scan's lock is not released by the refused change")
            .isEqualTo(LibraryLock.SCAN);
        verifyNoInteractions(overrides, films);
    }

    @Test
    void anotherLibraryWriterHoldingTheLockRefusesWithItsOwnWords() {
        lock.tryAcquire(LibraryLock.IMPORT);

        assertThatThrownBy(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .isInstanceOf(ConflictException.class).hasMessage("A Plex import is running");
        verifyNoInteractions(overrides, films);
    }

    @Test
    void anExcludeRowForATaggedFilmIsKept() {
        Film tagged = mk(1, "Tagged", 1980, 11L, "christmas");
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(tagged));
        when(overrides.snapshot()).thenReturn(new Snapshot(Set.of(), Set.of(11L)));
        library(tagged);

        ChristmasView view = service.choose(11L, ChristmasChoice.EXCLUDE);

        verify(overrides).set(11L, ChristmasChoice.EXCLUDE);
        verify(overrides, never()).remove(11L);
        assertThat(view.leftOut()).extracting(LeftOutItem::taggedByTmdb).containsExactly(true);
    }

    @Test
    void choosingForATmdbIdNoFilmHasIs404AndChangesNothing() {
        when(films.findByTmdbIdOrderByIdAsc(99L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.choose(99L, ChristmasChoice.INCLUDE))
            .isInstanceOf(NotFoundException.class).hasMessage("No film with TMDB id 99");
        verifyNoInteractions(overrides);
    }

    @Test
    void aScanRunningRefusesAChangeWithTheOwnersWordsAndChangesNothing() {
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running — try again when it finishes");
        verifyNoInteractions(overrides, films);
    }

    @Test
    void aFailedWriteGivesTheWriteSlotBackSoTheNextChangeIsNotBlocked() throws Exception {
        Film film = mk(1, "Film", 1999, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(film));
        doThrow(new IllegalStateException("db down")).doNothing().when(overrides).set(11L, ChristmasChoice.INCLUDE);
        library(film);
        overrides(Set.of(11L), Set.of());

        assertThatThrownBy(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .isInstanceOf(IllegalStateException.class);
        // Another thread: a lock the failed call kept would block it, and the bound makes that a failure. (The same
        // thread would pass anyway, the lock being reentrant.)
        ChristmasView next = CompletableFuture.supplyAsync(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .get(5, TimeUnit.SECONDS);
        assertThat(next.added()).hasSize(1);
    }

    private static Film hidden(long id, String title, Long tmdbId, String... keywords) {
        Film film = mk(id, title, 1980, tmdbId, keywords);
        file(edition(film, id, "Original", 100 * MIN, null), "/missing/" + id + ".m4v").setPresence(Presence.MISSING);
        return film;
    }

    @Test
    void aFilmWhoseEveryFileIsMissingHasNoStateAndCannotBeOverridden() {
        Film hiddenFilm = hidden(1, "Hidden", 11L, "christmas");
        Film shown = mk(2, "Shown", 1980, 12L, "christmas");
        when(overrides.snapshot()).thenReturn(new Snapshot(Set.of(), Set.of()));
        when(films.findAllById(Set.of(1L, 2L))).thenReturn(List.of(hiddenFilm, shown));
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(hiddenFilm));

        assertThat(service.states(List.of(1L, 2L))).containsExactly(new FilmState(2L, 12L, State.IN_KEYWORD));
        assertThatThrownBy(() -> service.choose(11L, ChristmasChoice.INCLUDE))
            .isInstanceOf(NotFoundException.class).hasMessage("No film with TMDB id 11");
        verify(overrides, never()).set(11L, ChristmasChoice.INCLUDE);
        verify(overrides, never()).remove(11L);
    }

    @Test
    void aTmdbIdOneBrowsableFilmHoldsCanBeOverriddenEvenIfAnotherFilmWithItIsHidden() {
        Film hiddenFilm = hidden(1, "Hidden", 11L);
        Film shown = mk(2, "Shown", 1980, 11L);
        when(films.findByTmdbIdOrderByIdAsc(11L)).thenReturn(List.of(hiddenFilm, shown));
        library(shown);
        overrides(Set.of(), Set.of(11L));

        assertThat(service.choose(11L, ChristmasChoice.EXCLUDE).leftOut()).hasSize(1);
        verify(overrides).set(11L, ChristmasChoice.EXCLUDE);
    }

    @Test
    void statesNameEachFilmsPlaceInTheAskedOrderAndSkipUnknownIds() {
        Film keyword = mk(1, "Keyword", 1980, 11L, "christmas");
        Film added = mk(2, "Added", 1980, 12L);
        Film leftOut = mk(3, "Left out", 1980, 13L, "christmas");
        Film notIn = mk(4, "Not in", 1980, 14L);
        notIn.setKeywordsFetchedAt(NOW);
        Film pending = mk(5, "Pending", 1980, 15L);
        Film noId = mk(6, "No id", 1980, null);
        Film includedPending = mk(7, "Included pending", 1980, 17L);
        when(overrides.snapshot()).thenReturn(new Snapshot(Set.of(12L, 13L, 17L), Set.of(13L)));
        when(films.findAllById(Set.of(6L, 5L, 4L, 3L, 2L, 1L, 7L, 404L)))
            .thenReturn(List.of(keyword, added, leftOut, notIn, pending, noId, includedPending));

        List<FilmState> states = service.states(List.of(6L, 5L, 4L, 3L, 2L, 1L, 7L, 404L, 6L));

        assertThat(states).containsExactly(
            new FilmState(6L, null, State.NO_TMDB_ID),
            new FilmState(5L, 15L, State.KEYWORDS_PENDING),
            new FilmState(4L, 14L, State.NOT_IN),
            new FilmState(3L, 13L, State.LEFT_OUT),
            new FilmState(2L, 12L, State.IN_ADDED),
            new FilmState(1L, 11L, State.IN_KEYWORD),
            new FilmState(7L, 17L, State.IN_ADDED));
    }

    @Test
    void anIncludedFilmTmdbAlsoTagsIsInKeywordNotInAdded() {
        Film both = mk(1, "Both", 1980, 11L, "christmas");
        when(overrides.snapshot()).thenReturn(new Snapshot(Set.of(11L), Set.of()));
        when(films.findAllById(Set.of(1L))).thenReturn(List.of(both));

        assertThat(service.states(List.of(1L))).containsExactly(new FilmState(1L, 11L, State.IN_KEYWORD));
    }

    @Test
    void statesAcceptFiftyIdsAndRefuseFiftyOne() {
        List<Long> fifty = LongStream.rangeClosed(1, 50).boxed().toList();
        when(overrides.snapshot()).thenReturn(new Snapshot(Set.of(), Set.of()));
        when(films.findAllById(Set.copyOf(fifty))).thenReturn(List.of());

        assertThat(service.states(fifty)).isEmpty();
        List<Long> fiftyOne = LongStream.rangeClosed(1, 51).boxed().toList();
        assertThatThrownBy(() -> service.states(fiftyOne))
            .isInstanceOf(BadRequestException.class).hasMessage("Ask for at most 50 films at a time");
    }

    @Test
    void fiftyOneIdsThatAreMostlyDuplicatesAreStillWithinTheCap() {
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 50).boxed().toList());
        ids.add(1L);
        when(overrides.snapshot()).thenReturn(new Snapshot(Set.of(), Set.of()));
        when(films.findAllById(Set.copyOf(ids))).thenReturn(List.of());

        assertThat(service.states(ids)).isEmpty();
    }

    @Test
    void aBlankIdInStatesIsRefusedBeforeAnythingIsRead() {
        assertThatThrownBy(() -> service.states(java.util.Arrays.asList(1L, null, 2L)))
            .isInstanceOf(BadRequestException.class).hasMessage("Each film id must be a number");
        verifyNoInteractions(overrides, films);
    }

    @Test
    void aFilmIsInAStateOfInExactlyWhenItIsInTheChristmasFilms() {
        boolean[] both = {false, true};
        for (boolean hasId : both) {
            for (boolean tagged : both) {
                for (boolean included : both) {
                    for (boolean excluded : both) {
                        for (boolean fetched : both) {
                            Film film = mk(1, "Film", 1980, hasId ? 11L : null, tagged ? new String[] {"christmas"}
                                : new String[0]);
                            film.setKeywordsFetchedAt(fetched ? NOW : null);
                            Set<Long> include = hasId && included ? Set.of(11L) : Set.of();
                            Set<Long> exclude = hasId && excluded ? Set.of(11L) : Set.of();
                            library(film);
                            overrides(include, exclude);
                            when(films.findAllById(Set.of(1L))).thenReturn(List.of(film));
                            String combo = "tmdbId=" + hasId + " tagged=" + tagged + " included=" + included
                                + " excluded=" + excluded + " fetched=" + fetched;

                            State state = service.states(List.of(1L)).get(0).state();
                            boolean inFilms = !service.view().inWeek().isEmpty();

                            assertThat(state == State.IN_KEYWORD || state == State.IN_ADDED).as(combo)
                                .isEqualTo(inFilms);
                            assertThat(state == State.IN_KEYWORD).as(combo + " keyword")
                                .isEqualTo(inFilms && tagged);
                            assertThat(!inFilms && !hasId).as(combo + " no id").isEqualTo(state == State.NO_TMDB_ID);
                            assertThat(!inFilms && hasId && excluded).as(combo + " out")
                                .isEqualTo(state == State.LEFT_OUT);
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        // running, enabled, lastRunIncomplete, fetched, total, stalled
        "false, true,  false, 3, 9, false",
        "false, true,  true,  3, 9, true",
        "false, false, false, 3, 9, true",
        "true,  false, true,  3, 9, false",
        "false, false, true,  9, 9, false",
        "false, true,  true,  9, 9, false"})
    void keywordsAreStalledOnlyWhenNothingIsRunningFilmsAreLeftAndNothingWillFetchThem(boolean running,
            boolean enabled, boolean incomplete, long fetched, long total, boolean stalled) {
        library();
        overrides(Set.of(), Set.of());
        when(films.countByTmdbIdIsNotNullAndKeywordsFetchedAtIsNotNull()).thenReturn(fetched);
        when(films.countByTmdbIdIsNotNull()).thenReturn(total);
        when(keywords.isRunning()).thenReturn(running);
        when(keywords.isEnabled()).thenReturn(enabled);
        when(keywords.lastRunIncomplete()).thenReturn(incomplete);

        assertThat(service.view().keywords()).isEqualTo(new Keywords(fetched, total, running, enabled, stalled));
    }
}
