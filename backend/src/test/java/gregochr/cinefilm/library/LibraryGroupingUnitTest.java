package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryGrouping.RepairResult;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** Library repair's use of TMDB, with every collaborator mocked: when it asks, when it stops, and what it writes. */
@ExtendWith(MockitoExtension.class)
class LibraryGroupingUnitTest {

    private static final long ELF = 10719L;
    private static final SearchHit ENCHANTED_2007 = new SearchHit(4523, "Enchanted", "Enchanted", 2007, 30, null);
    private static final SearchHit ENCHANTED_1998 = new SearchHit(404277, "Enchanted", "Enchanted", 1998, 1, null);
    private static final SearchHit ELLA_ENCHANTED = new SearchHit(14442, "Ella Enchanted", "Ella Enchanted", 2004,
        9, null);

    @Mock
    private FilmRepository films;
    @Mock
    private EditionRepository editions;
    @Mock
    private MediaFileRepository files;
    @Mock
    private FilmPreferenceRepository preferences;
    @Mock
    private GroupingChangeRepository changes;
    @Mock
    private EditionJoin join;
    @Mock
    private LibraryIngest ingest;
    @Mock
    private TmdbApi tmdb;
    @Mock
    private PlatformTransactionManager transactions;

    private LibraryGrouping grouping;
    private final List<MediaFile> library = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        grouping = new LibraryGrouping(films, editions, files, preferences, changes, join, ingest, tmdb,
            transactions, CLOCK);
        when(files.findAllOriginalsWithEdition()).thenReturn(library);
    }

    /** Adds a matched film (TMDB id given) with one edition and one original file per path. */
    private Film film(String title, Long tmdbId, String... paths) {
        Film film = TestData.film(nextId++, title, null, NOW);
        film.setMatchStatus(MatchStatus.MATCHED);
        film.setTmdbId(tmdbId);
        for (String path : paths) {
            Edition edition = TestData.edition(film, nextId++, "Original", 100 * MIN, null);
            MediaFile file = TestData.file(edition, path);
            file.setId(nextId++);
            library.add(file);
        }
        return film;
    }

    private static MovieDetails movie(long id, String title, String originalTitle) {
        return new MovieDetails(id, title, originalTitle, 2003, null, 97, List.of(), null, null, 1, List.of(),
            List.of(), null);
    }

    private List<GroupingChange> saved() {
        return mockingDetails(changes).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("save"))
            .map(call -> (GroupingChange) call.getArgument(0)).toList();
    }

    /** Stubs what a split of the given film's second edition needs; returns the new film the split will create. */
    private ArgumentCaptor<Film> stubSplit(Film kept, Edition moved) {
        MediaFile movedFile = moved.getMediaFiles().get(0);
        when(films.findById(kept.getId())).thenReturn(Optional.of(kept));
        when(files.findAllById(List.of(movedFile.getId()))).thenReturn(List.of(movedFile));
        when(preferences.findById(kept.getId())).thenReturn(Optional.empty());
        ArgumentCaptor<Film> created = ArgumentCaptor.forClass(Film.class);
        when(films.saveAndFlush(created.capture())).thenAnswer(call -> call.getArgument(0));
        return created;
    }

    private void tmdbKnowsElf() {
        when(tmdb.movie(ELF)).thenReturn(Optional.of(movie(ELF, "Elf", "Elf")));
        when(tmdb.alternativeTitles(ELF)).thenReturn(List.of("Buddy the Elf"));
    }

    @Test
    void aGroupTmdbListsOnlyAsOtherFilmsIsSplitOffAndTheLogSaysWhy() {
        Film elf = film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        tmdbKnowsElf();
        when(tmdb.search("Enchanted", null)).thenReturn(List.of(ENCHANTED_2007, ENCHANTED_1998, ELLA_ENCHANTED));
        ArgumentCaptor<Film> created = stubSplit(elf, elf.getEditions().get(1));

        RepairResult result = grouping.repair(true);

        assertThat(result).isEqualTo(new RepairResult(1, false));
        assertThat(created.getValue().getTitle()).isEqualTo("Enchanted");
        assertThat(created.getValue().getMatchStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(saved()).singleElement().satisfies(change -> {
            assertThat(change.getKind()).isEqualTo(GroupingChange.SPLIT);
            assertThat(change.getSummary()).isEqualTo("Moved \"Enchanted\" out of \"Elf\" into a film of its own "
                + "(TMDB lists it as a different film)");
        });
    }

    @Test
    void aGroupWithAYearIsSearchedAgainWithoutItWhenTheYearSearchFindsNoExactTitle() {
        Film elf = film("Elf", ELF, "Elf.m4v", "Enchanted (2007).m4v");
        tmdbKnowsElf();
        when(tmdb.search("Enchanted", 2007)).thenReturn(List.of(ELLA_ENCHANTED));
        when(tmdb.search("Enchanted", null)).thenReturn(List.of(ENCHANTED_2007));
        stubSplit(elf, elf.getEditions().get(1));

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        verify(tmdb).search("Enchanted", 2007);
        verify(tmdb).search("Enchanted", null);
    }

    @Test
    void anExactTitleFoundWithTheYearIsNotSearchedAgainWithoutIt() {
        Film elf = film("Elf", ELF, "Elf.m4v", "Enchanted (2007).m4v");
        tmdbKnowsElf();
        when(tmdb.search("Enchanted", 2007)).thenReturn(List.of(ENCHANTED_2007));
        stubSplit(elf, elf.getEditions().get(1));

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        verify(tmdb).search("Enchanted", 2007);
        verifyNoMoreInteractions(tmdb);
    }

    @Test
    void aFilmWhoseOtherTitleTmdbDoesNotKnowIsFlaggedNotSplit() {
        film("Blade Runner", 78L, "Blade Runner.m4v", "Blader Runner.m4v");
        when(tmdb.movie(78L)).thenReturn(Optional.of(movie(78L, "Blade Runner", "Blade Runner")));
        when(tmdb.alternativeTitles(78L)).thenReturn(List.of());
        when(tmdb.search("Blader Runner", null)).thenReturn(List.of(
            new SearchHit(78, "Blade Runner", "Blade Runner", 1982, 40, null)));

        RepairResult result = grouping.repair(true);

        assertThat(result).isEqualTo(new RepairResult(1, false));
        assertThat(saved()).singleElement().satisfies(change -> {
            assertThat(change.getKind()).isEqualTo(GroupingChange.FLAG);
            assertThat(change.getSummary()).isEqualTo(
                "Files look like different films: \"Blade Runner\", \"Blader Runner\" in \"Blade Runner\"");
        });
    }

    @Test
    void aTitleThatIsAnAlternativeTitleOfTheFilmItselfIsFlaggedNotSplit() {
        film("Léon", 101L, "Léon.m4v", "The Professional.m4v");
        when(tmdb.movie(101L)).thenReturn(Optional.of(movie(101L, "Léon", "Léon")));
        when(tmdb.alternativeTitles(101L)).thenReturn(List.of("The Professional"));
        when(tmdb.search("The Professional", null)).thenReturn(List.of(
            new SearchHit(9, "The Professional", "The Professional", 1981, 5, null)));

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        assertThat(saved()).singleElement().extracting(GroupingChange::getKind).isEqualTo(GroupingChange.FLAG);
    }

    @Test
    void anOutageStopsFurtherLookupsReportsItAndLeavesEveryFilmFlagged() {
        film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        long ghost = 500L;
        film("Ghost", ghost, "Ghost.m4v", "The Witches.m4v");
        when(tmdb.movie(ELF)).thenReturn(Optional.of(movie(ELF, "Elf", "Elf")));
        when(tmdb.alternativeTitles(ELF)).thenReturn(List.of());
        when(tmdb.search("Enchanted", null)).thenThrow(new TmdbUnavailableException("down", null));

        RepairResult result = grouping.repair(true);

        assertThat(result).isEqualTo(new RepairResult(2, true));
        assertThat(saved()).extracting(GroupingChange::getSummary).containsExactly(
            "Files look like different films: \"Elf\", \"Enchanted\" in \"Elf\"",
            "Files look like different films: \"Ghost\", \"The Witches\" in \"Ghost\"");
        verify(tmdb, never()).search("The Witches", null);
        verify(tmdb, never()).alternativeTitles(ghost);
        verify(tmdb, never()).movie(ghost);
    }

    @Test
    void withTmdbNotUsableNoLookupIsMadeAndTheFilmIsFlagged() {
        film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");

        RepairResult result = grouping.repair(false);

        assertThat(result).isEqualTo(new RepairResult(1, false));
        verifyNoInteractions(tmdb);
        assertThat(saved()).singleElement().extracting(GroupingChange::getKind).isEqualTo(GroupingChange.FLAG);
    }

    @Test
    void aGroupTheOwnerTaggedIsNotLookedUpAndTheFilmIsFlagged() {
        film("Elf", ELF, "Elf.m4v", "Enchanted {tmdb-10719}.m4v");

        RepairResult result = grouping.repair(true);

        assertThat(result).isEqualTo(new RepairResult(1, false));
        verifyNoInteractions(tmdb);
    }

    @Test
    void aSequelSplitMakesNoTmdbCallAndKeepsTheOldLogWording() {
        Film rocky = film("Rocky IV", 1001L, "Rocky IV.m4v", "Rocky V.m4v");
        stubSplit(rocky, rocky.getEditions().get(1));

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        verifyNoInteractions(tmdb);
        assertThat(saved()).singleElement().extracting(GroupingChange::getSummary)
            .isEqualTo("Moved \"Rocky V\" out of \"Rocky IV\" into films of their own");
    }

    @Test
    void anUnexpectedErrorInOneLookupLeavesThatFilmFlaggedAndTheLoopGoesOn() {
        film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        Film ghost = film("Ghost", 500L, "Ghost.m4v", "The Witches.m4v");
        when(tmdb.movie(ELF)).thenReturn(Optional.of(movie(ELF, "Elf", "Elf")));
        when(tmdb.alternativeTitles(ELF)).thenReturn(List.of());
        when(tmdb.search("Enchanted", null)).thenThrow(new IllegalStateException("boom"));
        when(tmdb.movie(500L)).thenReturn(Optional.empty());
        when(tmdb.alternativeTitles(500L)).thenReturn(List.of());
        when(tmdb.search("The Witches", null)).thenReturn(List.of(
            new SearchHit(10166, "The Witches", "The Witches", 1990, 20, null)));
        ArgumentCaptor<Film> created = stubSplit(ghost, ghost.getEditions().get(1));

        RepairResult result = grouping.repair(true);

        assertThat(result).isEqualTo(new RepairResult(2, false));
        assertThat(created.getValue().getTitle()).isEqualTo("The Witches");
        assertThat(saved()).extracting(GroupingChange::getKind).containsExactly(GroupingChange.FLAG,
            GroupingChange.SPLIT);
    }

    @Test
    void aFailedSplitIsLoggedAndTheRepairGoesOn() {
        Film elf = film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        film("Blade Runner", 78L, "Blade Runner.m4v", "Blader Runner.m4v");
        tmdbKnowsElf();
        when(tmdb.search("Enchanted", null)).thenReturn(List.of(ENCHANTED_2007));
        when(films.findById(elf.getId())).thenThrow(new IllegalStateException("boom"));
        when(tmdb.movie(78L)).thenReturn(Optional.empty());
        when(tmdb.alternativeTitles(78L)).thenReturn(List.of());
        when(tmdb.search("Blader Runner", null)).thenReturn(List.of());

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        assertThat(saved()).singleElement().extracting(GroupingChange::getSummary).isEqualTo(
            "Files look like different films: \"Blade Runner\", \"Blader Runner\" in \"Blade Runner\"");
    }

    @Test
    void aTitleEqualToOnlyTheReferencesOriginalTitleIsFlaggedNotSplit() {
        film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        when(tmdb.movie(ELF)).thenReturn(Optional.of(movie(ELF, "Elf", "Enchanted")));
        when(tmdb.alternativeTitles(ELF)).thenReturn(List.of());
        when(tmdb.search("Enchanted", null)).thenReturn(List.of(ENCHANTED_2007));

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        assertThat(saved()).singleElement().extracting(GroupingChange::getKind).isEqualTo(GroupingChange.FLAG);
    }

    @Test
    void aTitleEqualToOnlyTheReferencesTitleIsFlaggedNotSplit() {
        film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        when(tmdb.movie(ELF)).thenReturn(Optional.of(movie(ELF, "Enchanted", "Elf")));
        when(tmdb.alternativeTitles(ELF)).thenReturn(List.of());
        when(tmdb.search("Enchanted", null)).thenReturn(List.of(ENCHANTED_2007));

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(1, false));

        assertThat(saved()).singleElement().extracting(GroupingChange::getKind).isEqualTo(GroupingChange.FLAG);
    }

    @Test
    void nothingIsAskedOfTmdbWhenNoFilmIsFlagged() {
        film("Elf", ELF, "Elf.m4v", "Elf (HD).m4v");

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(0, false));

        verifyNoInteractions(tmdb);
    }

    @Test
    void aFilmTheOwnerSettledIsNeverRead() {
        Film elf = film("Elf", ELF, "Elf.m4v", "Enchanted.m4v");
        elf.setEditionsConfirmed(true);

        assertThat(grouping.repair(true)).isEqualTo(new RepairResult(0, false));

        verifyNoInteractions(tmdb);
        assertThat(saved()).isEmpty();
    }
}
