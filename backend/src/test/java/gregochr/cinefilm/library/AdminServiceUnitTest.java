package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.EditionOwner;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.CandidateDto;
import gregochr.cinefilm.library.AdminDtos.LastScanDto;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRefresher;
import gregochr.cinefilm.ratings.RatingsRefresher;
import gregochr.cinefilm.subtitle.SidecarCheckRefresher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The attention list's new fields (the candidates' score and the confident flag), with every collaborator mocked. */
@ExtendWith(MockitoExtension.class)
class AdminServiceUnitTest {

    @Mock
    private ScanService scans;

    @Mock
    private ScanRunRepository runs;

    @Mock
    private FilmRepository films;

    @Mock
    private MediaFileRepository files;

    @Mock
    private RatingsRefresher ratings;

    @Mock
    private OpenSubtitlesRefresher subtitleSearch;

    @Mock
    private SidecarCheckRefresher subtitleChecks;

    @Mock
    private GroupingChangeRepository changes;

    @Mock
    private EditionRepository editions;

    private AdminService admin;

    @BeforeEach
    void setUp() {
        admin = new AdminService(scans, runs, films, files, new LibraryRoot("/media/films"), ratings, subtitleSearch,
            subtitleChecks, changes, editions);
    }

    private static String candidate(long id, int score) {
        return "{\"tmdbId\":" + id + ",\"title\":\"T" + id + "\",\"year\":2000,\"runtimeMinutes\":90,"
            + "\"posterPath\":null,\"score\":" + score + "}";
    }

    private static Film unmatched(long id, String title, String candidatesJson) {
        Film film = film(id, title, 2000, NOW);
        film.setMatchStatus(MatchStatus.NEEDS_MATCH);
        film.setMatchCandidates(candidatesJson);
        return film;
    }

    private List<AttentionItem> attentionFor(Film... unmatched) {
        when(films.findNeedingMatch()).thenReturn(List.of(unmatched));
        when(files.findOriginals(Presence.PRESENT, Playability.PROTECTED)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.NEEDS_CONVERSION)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.UNREADABLE)).thenReturn(List.of());
        when(films.findAll()).thenReturn(List.of());
        return admin.attention().items();
    }

    @Test
    void theCandidatesCarryTheirScoreAndTheConfidentFlagComesFromTheRule() {
        Film sure = unmatched(1, "Sure", "[" + candidate(10, 85) + "," + candidate(11, 70) + "]");
        Film unsure = unmatched(2, "Unsure", "[" + candidate(20, 80) + "," + candidate(21, 75) + "]");

        List<AttentionItem> items = attentionFor(sure, unsure);

        assertThat(items).extracting(AttentionItem::title, AttentionItem::confident)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Sure", true),
                org.assertj.core.api.Assertions.tuple("Unsure", false));
        assertThat(items.get(0).candidates()).extracting(CandidateDto::score).containsExactly(85, 70);
        assertThat(items.get(1).candidates()).extracting(CandidateDto::score).containsExactly(80, 75);
    }

    @Test
    void aMatchedFilmWithNoTmdbIdIsListedAsNeedingAMatchWithNoCandidates() {
        Film noId = film(7L, "Slum Dog Millionaire", 2008, NOW);
        noId.setMatchStatus(MatchStatus.MATCHED);

        List<AttentionItem> items = attentionFor(noId);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("needs-match");
            assertThat(item.detail()).isEqualTo("No match found on TMDB");
            assertThat(item.candidates()).isEmpty();
        });
    }

    @Test
    void aTaggedMatchTheGuardRefusedIsOneCandidateAtScore100AndIsConfident() {
        List<AttentionItem> items = attentionFor(unmatched(1, "Refused", "[" + candidate(10, 100) + "]"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.confident()).isTrue();
            assertThat(item.candidates()).containsExactly(new CandidateDto(10, "T10", 2000, 90, null, 100));
        });
    }

    @Test
    void aSingleCandidateAtTheAcceptScoreIsNotConfidentButOneAboveItIs() {
        assertThat(attentionFor(unmatched(1, "At", "[" + candidate(10, 60) + "]")))
            .extracting(AttentionItem::confident).containsExactly(false);
        assertThat(attentionFor(unmatched(2, "Above", "[" + candidate(10, 61) + "]")))
            .extracting(AttentionItem::confident).containsExactly(true);
    }

    @Test
    void aWeakOrMissingOrUnreadableCandidateListIsNeverConfident() {
        Film weak = unmatched(1, "Weak", "[" + candidate(10, 59) + "]");
        Film none = unmatched(2, "None", null);
        Film broken = unmatched(3, "Broken", "not json");

        assertThat(attentionFor(weak, none, broken)).extracting(AttentionItem::confident)
            .containsExactly(false, false, false);
    }

    @Test
    void everyOtherKindIsNotConfident() {
        MediaFile drm = file(edition(film(5L, "Drm", 2002, NOW), 50L, "Original", 100, null), "/media/films/Drm.m4v");
        drm.setPresence(Presence.PRESENT);
        when(films.findNeedingMatch()).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.PROTECTED)).thenReturn(List.of(drm));
        when(files.findOriginals(Presence.PRESENT, Playability.NEEDS_CONVERSION)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.UNREADABLE)).thenReturn(List.of());
        when(films.findAll()).thenReturn(List.of());

        List<AttentionItem> items = admin.attention().items();

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("protected");
            assertThat(item.confident()).isFalse();
        });
    }

    private static Film partsOf(long id, String title, String secondCodec) {
        Film film = film(id, title, 2002, NOW);
        Edition edition = edition(film, id * 10, "Original", 200 * 60_000L, null);
        MediaFile one = file(edition, title + " pt1.m4v");
        one.setId(id * 100 + 1);
        one.setPartNumber(1);
        MediaFile two = file(edition, title + " pt2.m4v");
        two.setId(id * 100 + 2);
        two.setPartNumber(2);
        two.setVideoCodec(secondCodec);
        return film;
    }

    private static Film hidden(long id, String title) {
        Film film = film(id, title, 1999, NOW);
        MediaFile file = file(edition(film, id * 10, "Original", 100 * 60_000L, null), title + ".m4v");
        file.setId(id * 100);
        file.setPresence(Presence.MISSING);
        return film;
    }

    /** Stubs every read the old computation ({@code attention()} and {@code status()}) and the cheap one make. */
    private void library(List<Film> all) {
        long unmatched = all.stream().filter(f -> f.getMatchStatus() == MatchStatus.NEEDS_MATCH).count();
        when(films.countNeedingMatch()).thenReturn(unmatched);
        when(films.count()).thenReturn((long) all.size());
        List<Edition> every = all.stream().flatMap(f -> f.getEditions().stream()).toList();
        when(editions.findAllWithFilesLeavingFilmLazy()).thenReturn(every);
        when(editions.findEditionOwners()).thenReturn(every.stream()
            .map(e -> new EditionOwner(e.getId(), e.getFilm().getId(), e.getFilm().isEditionsConfirmed())).toList());
    }

    /** Stubs the reads {@code attention()} and {@code status()} make beyond {@link #library}, for the parity tests. */
    private void oldComputation(List<Film> all) {
        when(films.findAll()).thenReturn(all);
        List<Film> needing = all.stream().filter(f -> f.getMatchStatus() == MatchStatus.NEEDS_MATCH).toList();
        when(films.findNeedingMatch()).thenReturn(needing);
    }

    private static long count(List<AttentionItem> items, String... kinds) {
        return items.stream().filter(item -> List.of(kinds).contains(item.kind())).count();
    }

    private static List<Film> mixedLibrary() {
        Film unmatched = unmatched(1, "Unmatched", null);
        Film ambiguous = filmOf(2, "Heat", false, "a/Heat.m4v", "b/Heat.m4v");
        Film confirmed = filmOf(3, "Alien", true, "a/Alien.m4v", "b/Alien.m4v");
        Film different = filmOf(4, "Mixed", false, "a/Heat.m4v", "b/Alien.m4v");
        Film partsDiffer = partsOf(5, "Towers", "H264");
        Film partsMatch = partsOf(6, "Return", "HEVC");
        Film missing = hidden(7, "Gone");
        Film bare = film(8L, "Bare", 2001, NOW);
        return List.of(unmatched, ambiguous, confirmed, different, partsDiffer, partsMatch, missing, bare);
    }

    private static int idBase = 2000;

    /** A film with one edition per path (each with one file), ids set as the database would. */
    private static Film filmOf(long id, String title, boolean confirmed, String... paths) {
        Film film = film(id, title, 2000, NOW);
        film.setEditionsConfirmed(confirmed);
        for (String path : paths) {
            Edition edition = edition(film, (long) idBase++, "Edition " + path, 100 * 60_000L, null);
            file(edition, path).setId((long) idBase++);
        }
        return film;
    }

    @Test
    void theSummaryFiguresAreTheOldComputationsFigures() {
        List<Film> all = mixedLibrary();
        library(all);
        oldComputation(all);

        AdminService.SummaryFigures figures = admin.summaryFigures();
        List<AttentionItem> items = admin.attention().items();

        assertThat(figures.needsMatch()).isEqualTo(count(items, "needs-match")).isEqualTo(1);
        assertThat(figures.severalFiles()).isEqualTo(count(items, "multiple-files", "parts-mismatch")).isEqualTo(3);
        assertThat(figures.browsableFilms()).isEqualTo(admin.status().counts().films()).isEqualTo(7);
    }

    @Test
    void theLastFinishedScanIsReadDirectlyAndIsWhatTheStatusReports() {
        library(List.of());
        when(films.findAll()).thenReturn(List.of());
        ScanRun run = new ScanRun();
        run.setStartedAt(NOW.minusSeconds(60));
        run.setFinishedAt(NOW);
        run.setTrigger("nightly");
        run.setFilesSeen(10);
        run.setNewFiles(2);
        run.setMissingFiles(1);
        run.setError("Library folder is not readable");
        when(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).thenReturn(Optional.of(run));

        assertThat(admin.summaryFigures().lastScan()).isEqualTo(admin.status().lastScan())
            .isEqualTo(new LastScanDto(NOW.minusSeconds(60), NOW, "nightly", 10, 2, 1,
                "Library folder is not readable"));
    }

    @Test
    void noFinishedScanIsNull() {
        library(List.of());

        assertThat(admin.summaryFigures().lastScan()).isNull();
    }

    @Test
    void aFilmWithNoEditionShowsAndIsNeverListed() {
        library(List.of(film(1L, "Bare", 2000, NOW)));

        AdminService.SummaryFigures figures = admin.summaryFigures();

        assertThat(figures.browsableFilms()).isEqualTo(1);
        assertThat(figures.severalFiles()).isZero();
        assertThat(figures.needsMatch()).isZero();
    }

    @Test
    void aFilmWhoseEveryEditionIsHiddenIsNotBrowsableButAFilmWithOneShownEditionIs() {
        Film oneShown = filmOf(1, "Mixed", true, "a.m4v", "b.m4v");
        oneShown.getEditions().get(0).getMediaFiles().get(0).setPresence(Presence.MISSING);
        List<Film> all = List.of(oneShown, hidden(2, "Gone"));
        library(all);
        when(films.findAll()).thenReturn(all);

        assertThat(admin.summaryFigures().browsableFilms()).isEqualTo(1)
            .isEqualTo(admin.status().counts().films());
    }

    @Test
    void anEditionAddedBetweenTheTwoReadsHasNoOwnerYetAndIsLeftOut() {
        Film film = filmOf(1, "New", false, "a/New.m4v");
        when(films.countNeedingMatch()).thenReturn(0L);
        when(films.count()).thenReturn(1L);
        when(editions.findAllWithFilesLeavingFilmLazy()).thenReturn(film.getEditions());
        when(editions.findEditionOwners()).thenReturn(List.of());

        AdminService.SummaryFigures figures = admin.summaryFigures();

        assertThat(figures.severalFiles()).isZero();
        assertThat(figures.browsableFilms()).isEqualTo(1);
    }

    @Test
    void itReadsNoFilmRowAndAsksForTheEditionsOnce() {
        library(mixedLibrary());

        admin.summaryFigures();

        verify(films).countNeedingMatch();
        verify(films).count();
        verifyNoMoreInteractions(films);
        verify(editions).findAllWithFilesLeavingFilmLazy();
        verify(editions).findEditionOwners();
        verifyNoMoreInteractions(editions);
        verifyNoInteractions(files);
    }
}
