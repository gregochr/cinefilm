package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.library.LibraryGrouping;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.library.ScanRun;
import gregochr.cinefilm.library.ScanRunRepository;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexMedia;
import gregochr.cinefilm.tmdb.TmdbApi;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The Plex import grouping the library in Plex's order, against H2 with a fake Plex: every part of every version,
 * the different-film guard, unfolding and joining, names, defaults and watch position.
 */
class PlexGroupingFlowTest extends ApiTestBase {

    private static final String PREFIX = "/Volumes/Media Library/Movies/";
    private static final Instant ADDED = NOW.minus(Duration.ofDays(100));

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
    private LibraryGrouping grouping;

    @Autowired
    private GroupingChangeRepository changes;

    @Autowired
    private EntityManager em;

    private PlexImportService service;

    @BeforeEach
    void setUp() {
        ScanRun scan = new ScanRun();
        scan.setStartedAt(NOW);
        scan.setFinishedAt(NOW);
        scan.setTrigger("admin");
        scanRuns.save(scan);
        when(plex.configured()).thenReturn(true);
        when(plex.movieSections()).thenReturn(List.of("1"));
        when(plex.collections("1")).thenReturn(List.of());
        when(tmdb.enabled()).thenReturn(false);
        service = new PlexImportService(plex, tmdb, ingest, writer, artwork, scanRuns, importRuns, new LibraryLock(),
            Runnable::run, CLOCK, PREFIX, mock(ApplicationEventPublisher.class), grouping);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private static Film film(String title, Long tmdbId) {
        Film film = TestData.film(null, title, 2000, ADDED);
        film.setTmdbId(tmdbId);
        film.setMatchStatus(tmdbId == null ? MatchStatus.PENDING : MatchStatus.MATCHED);
        return film;
    }

    /** An edition of {@code minutes} in all, split evenly over its files (part numbers 1..n in the order given). */
    private static Edition edition(Film film, String name, long minutes, String... paths) {
        Edition edition = TestData.edition(film, null, name, minutes * MIN, null);
        int part = 1;
        for (String path : paths) {
            MediaFile file = TestData.file(edition, path);
            file.setPartNumber(part++);
            file.setDurationMs(minutes * MIN / paths.length);
        }
        return edition;
    }

    private Film saved(Film film) {
        return films.saveAndFlush(film);
    }

    private static PlexMedia media(String... names) {
        return new PlexMedia(Arrays.stream(names).map(n -> PREFIX + n).toList());
    }

    private static PlexFilm item(String title, Long tmdbId, String editionTitle, int views, long offset,
                                 PlexMedia... media) {
        return new PlexFilm("1", title, title, 2000, null, editionTitle, ADDED, ADDED, null, null, tmdbId, views,
            offset, views > 0 ? NOW.minus(Duration.ofDays(1)) : null, List.of(media));
    }

    private void importItems(PlexFilm... items) {
        when(plex.films("1")).thenReturn(List.of(items));
        service.start();
        em.flush();
        em.clear();
        assertThat(importRuns.findFirstByFinishedAtNotNullOrderByIdDesc().orElseThrow().getError()).isNull();
    }

    private Film film(Long id) {
        return films.findById(id).orElseThrow();
    }

    private static List<String> paths(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getPath).toList();
    }

    private static Edition edition(Film film, String name) {
        return film.getEditions().stream().filter(e -> e.getName().equals(name)).findFirst().orElseThrow();
    }

    private List<String> flags() {
        return changes.findAll().stream().filter(c -> c.getKind().equals(GroupingChange.FLAG))
            .map(GroupingChange::getSummary).toList();
    }

    // ---- two versions of two parts ----------------------------------------------------------------------------

    @Test
    void twoVersionsOfTwoPartsBecomeTwoEditionsWithOrderedParts() {
        Film towers = film("The Two Towers", 121L);
        edition(towers, "Extended", 60, "Two Towers {edition-Extended} - pt1.m4v");
        edition(towers, "Extended too", 60, "Two Towers {edition-Extended} - pt2.m4v");
        edition(towers, "Theatrical", 60, "Two Towers {edition-Theatrical} - pt1.m4v");
        edition(towers, "Theatrical too", 60, "Two Towers {edition-Theatrical} - pt2.m4v");
        Long id = saved(towers).getId();

        importItems(item("The Two Towers", 121L, null, 0, 0,
            media("Two Towers {edition-Extended} - pt1.m4v", "Two Towers {edition-Extended} - pt2.m4v"),
            media("Two Towers {edition-Theatrical} - pt1.m4v", "Two Towers {edition-Theatrical} - pt2.m4v")));

        Film after = film(id);
        assertThat(after.getEditions()).hasSize(2);
        Edition first = after.getEditions().stream().filter(e -> paths(e).get(0).contains("Extended")).findFirst()
            .orElseThrow();
        Edition second = after.getEditions().stream().filter(e -> paths(e).get(0).contains("Theatrical")).findFirst()
            .orElseThrow();
        assertThat(paths(first)).containsExactly("Two Towers {edition-Extended} - pt1.m4v",
            "Two Towers {edition-Extended} - pt2.m4v");
        assertThat(paths(second)).containsExactly("Two Towers {edition-Theatrical} - pt1.m4v",
            "Two Towers {edition-Theatrical} - pt2.m4v");
        assertThat(first.getRuntimeMs()).isEqualTo(120 * MIN);
        assertThat(first.getGroupingSource()).isEqualTo(GroupingSource.PLEX);
        assertThat(second.getName()).isNotEqualTo(first.getName());
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void partsKeepPlexsOrderWhateverTheirPathsSayAndASecondImportChangesNothing() {
        Film film = film("Alien", 348L);
        edition(film, "Original", 60, "Alien - pt1.m4v");
        edition(film, "Original", 60, "Alien - pt2.m4v");
        Long id = saved(film).getId();
        PlexFilm plexAlien = item("Alien", 348L, null, 0, 0, media("Alien - pt2.m4v", "Alien - pt1.m4v"));

        importItems(plexAlien);

        Film after = film(id);
        assertThat(after.getEditions()).hasSize(1);
        assertThat(paths(after.getEditions().get(0))).containsExactly("Alien - pt2.m4v", "Alien - pt1.m4v");
        assertThat(after.getEditions().get(0).originalParts()).extracting(MediaFile::getPartNumber)
            .containsExactly(1, 2);
        long rows = changes.count();
        Long editionId = after.getEditions().get(0).getId();

        importItems(plexAlien);

        assertThat(film(id).getEditions()).extracting(Edition::getId).containsExactly(editionId);
        assertThat(changes.count()).isEqualTo(rows);
    }

    // ---- the guard --------------------------------------------------------------------------------------------

    @Test
    void aFileWhoseNameIsAnotherFilmIsSplitOffAndKeepsItsOwnFilm() {
        Film four = film("Rocky IV", 1374L);
        edition(four, "Original", 91, "Rocky IV.m4v");
        Film five = film("Rocky V", null);
        edition(five, "Original", 104, "Rocky V.m4v");
        Long fourId = saved(four).getId();
        Long fiveId = saved(five).getId();

        importItems(item("Rocky IV", 1374L, null, 0, 0, media("Rocky IV.m4v"), media("Rocky V.m4v")));

        assertThat(films.count()).isEqualTo(2);
        assertThat(film(fourId).getEditions()).hasSize(1);
        assertThat(paths(film(fourId).getEditions().get(0))).containsExactly("Rocky IV.m4v");
        assertThat(film(fiveId).getEditions()).hasSize(1);
        assertThat(film(fiveId).getTmdbId()).isNull();
        assertThat(flags()).anyMatch(f -> f.contains("Rocky V.m4v") && f.contains("Rocky IV"));
    }

    @Test
    void aFilmThatAlreadyHoldsAnotherTitleIsSplitAfterTheImportByTheRepairPass() {
        Film four = film("Rocky IV", 1374L);
        edition(four, "Original", 91, "Rocky IV.m4v");
        edition(four, "Original", 104, "Rocky V.m4v");
        Long fourId = saved(four).getId();

        importItems(item("Rocky IV", 1374L, null, 0, 0, media("Rocky IV.m4v"), media("Rocky V.m4v")));

        assertThat(films.count()).isEqualTo(2);
        Film kept = film(fourId);
        assertThat(kept.getEditions()).hasSize(1);
        assertThat(paths(kept.getEditions().get(0))).containsExactly("Rocky IV.m4v");
    }

    @Test
    void filmsWithTheSamePlexIdButDifferentComparisonTitlesAreNotMerged() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        Film other = film("Heat DC", 949L);
        edition(other, "Original", 100, "Heat (DC).m4v");
        Long heatId = saved(heat).getId();
        Long otherId = saved(other).getId();

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v")));

        assertThat(films.count()).isEqualTo(2);
        assertThat(film(heatId).getEditions()).hasSize(1);
        assertThat(film(otherId).getEditions()).hasSize(1);
    }

    @Test
    void filmsWithTheSamePlexIdAndTheSameComparisonTitleAreStillMerged() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        Film other = film("Heat cut", 949L);
        edition(other, "Original", 100, "Heat (Director's Cut).m4v");
        Long heatId = saved(heat).getId();
        saved(other);

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v"), media("Heat (Director's Cut).m4v")));

        assertThat(films.count()).isEqualTo(1);
        assertThat(film(heatId).getEditions()).hasSize(2);
    }

    @Test
    void untaggedVersionsMoreThanFifteenPercentApartKeepBothEditionsAndAreFlaggedOnce() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        edition(heat, "Original", 130, "Heat HD.m4v");
        Long heatId = saved(heat).getId();

        PlexFilm plexHeat = item("Heat", 949L, null, 1, 0, media("Heat.m4v"), media("Heat HD.m4v"));

        importItems(plexHeat);

        Film after = film(heatId);
        assertThat(after.getEditions()).hasSize(2);
        assertThat(after.getEditions()).extracting(Edition::getGroupingSource).containsOnly(GroupingSource.PLEX);
        assertThat(after.getEditions()).allMatch(e -> states.findById(e.getId()).orElseThrow().isWatched());
        assertThat(flags()).hasSize(1).allMatch(f -> f.startsWith("Runtimes differ by 23%")
            && f.contains("Heat HD.m4v"));

        importItems(plexHeat);

        assertThat(film(heatId).getEditions()).hasSize(2);
        assertThat(flags()).hasSize(1);
    }

    // ---- reporting and names ----------------------------------------------------------------------------------

    @Test
    void aVersionThatIsOnlyPartlyPairedIsNotJoinedAndIsReported() {
        Film film = film("Alien", 348L);
        edition(film, "Original", 60, "Alien - pt1.m4v");
        edition(film, "Original", 60, "Alien - pt2.m4v");
        Long id = saved(film).getId();

        importItems(item("Alien", 348L, null, 0, 0,
            media("Alien - pt1.m4v", "Alien - pt2.m4v", "Alien - pt3.m4v")));

        assertThat(film(id).getEditions()).hasSize(2);
        assertThat(flags()).anyMatch(f -> f.startsWith("Only some of the files Plex lists"));
        assertThat(flags()).noneMatch(f -> f.toLowerCase().contains("part"));
    }

    @Test
    void twoVersionsWithTheSameNameAreOriginalAndOriginal2() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        edition(heat, "Original", 100, "Heat HD.m4v");
        Long id = saved(heat).getId();

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v"), media("Heat HD.m4v")));

        assertThat(film(id).getEditions()).extracting(Edition::getName)
            .containsExactlyInAnyOrder("Original", "Original 2");
    }

    @Test
    void aPlexEditionTitleNamesTheVersionsAndIsCutTo32Characters() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        Long id = saved(heat).getId();

        importItems(item("Heat", 949L, "D".repeat(50), 0, 0, media("Heat.m4v")));

        assertThat(film(id).getEditions().get(0).getName()).isEqualTo("D".repeat(32));
    }

    // ---- watching ---------------------------------------------------------------------------------------------

    @Test
    void viewOffsetOnAJoinedEditionIsAPositionInTheWholeFilm() {
        Film film = film("Alien", 348L);
        edition(film, "Original", 60, "Alien - pt1.m4v");
        edition(film, "Original", 60, "Alien - pt2.m4v");
        Long id = saved(film).getId();

        importItems(item("Alien", 348L, null, 0, 100 * MIN, media("Alien - pt1.m4v", "Alien - pt2.m4v")));

        Edition joined = film(id).getEditions().get(0);
        assertThat(joined.getRuntimeMs()).isEqualTo(120 * MIN);
        PlaybackState state = states.findById(joined.getId()).orElseThrow();
        assertThat(state.getPositionMs()).isEqualTo(100 * MIN);
        assertThat(state.isWatched()).isFalse();
    }

    @Test
    void aViewOffsetNearTheEndOfTheJoinedEditionIsWatchedAgainstTheSummedRuntime() {
        Film film = film("Alien", 348L);
        edition(film, "Original", 60, "Alien - pt1.m4v");
        edition(film, "Original", 60, "Alien - pt2.m4v");
        Long id = saved(film).getId();

        importItems(item("Alien", 348L, null, 0, 115 * MIN, media("Alien - pt1.m4v", "Alien - pt2.m4v")));

        PlaybackState state = states.findById(film(id).getEditions().get(0).getId()).orElseThrow();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getPositionMs()).isZero();
    }

    // ---- unfolding a join Plex disagrees with --------------------------------------------------------------------

    @Test
    void anAutoJoinPlexDisagreesWithIsUnfoldedAndTheResumePointFollowsItsPart() {
        Film film = film("Alien", 348L);
        Edition joined = edition(film, "Original", 120, "Alien - pt1.m4v", "Alien - pt2.m4v");
        Long id = saved(film).getId();
        saveState(TestData.state(joined, 90 * MIN, false, NOW.minus(Duration.ofDays(2)), null));

        importItems(item("Alien", 348L, null, 0, 0, media("Alien - pt1.m4v"), media("Alien - pt2.m4v")));

        Film after = film(id);
        assertThat(after.getEditions()).hasSize(2);
        Edition second = after.getEditions().stream().filter(e -> paths(e).contains("Alien - pt2.m4v")).findFirst()
            .orElseThrow();
        assertThat(paths(second)).containsExactly("Alien - pt2.m4v");
        assertThat(states.findById(second.getId()).orElseThrow().getPositionMs()).isEqualTo(30 * MIN);
    }

    @Test
    void anAutoJoinThatDisagreesWithPlexIsUnfoldedThenJoinedPlexsWay() {
        Film film = film("Alien", 348L);
        Edition joined = edition(film, "Original", 120, "Alien - pt1.m4v", "Alien - pt2.m4v");
        Long id = saved(film).getId();
        saveState(TestData.state(joined, 90 * MIN, false, NOW.minus(Duration.ofDays(2)), null));

        importItems(item("Alien", 348L, null, 0, 0, media("Alien - pt2.m4v", "Alien - pt1.m4v")));

        Film after = film(id);
        assertThat(after.getEditions()).hasSize(1);
        Edition now = after.getEditions().get(0);
        assertThat(paths(now)).containsExactly("Alien - pt2.m4v", "Alien - pt1.m4v");
        assertThat(now.getGroupingSource()).isEqualTo(GroupingSource.PLEX);
        assertThat(now.getRuntimeMs()).isEqualTo(120 * MIN);
        assertThat(states.findById(now.getId())).isPresent();
        assertThat(changes.findAll()).extracting(GroupingChange::getKind).contains(GroupingChange.SPLIT,
            GroupingChange.JOIN);
    }

    // ---- the owner's films, defaults ----------------------------------------------------------------------------

    @Test
    void aFilmTheOwnerSettledIsNotRegroupedButStillGetsPlexsMetadata() {
        Film film = film("Alien", 348L);
        film.setEditionsConfirmed(true);
        edition(film, "Mine", 60, "Alien - pt1.m4v");
        edition(film, "Mine two", 60, "Alien - pt2.m4v");
        Long id = saved(film).getId();

        importItems(item("Alien Plex", 348L, "Final Cut", 0, 0, media("Alien - pt1.m4v", "Alien - pt2.m4v")));

        Film after = film(id);
        assertThat(after.getEditions()).extracting(Edition::getName).containsExactlyInAnyOrder("Mine", "Mine two");
        assertThat(after.getEditions()).extracting(Edition::getGroupingSource).containsOnly(GroupingSource.AUTO);
        assertThat(after.getTitle()).isEqualTo("Alien Plex");
        assertThat(changes.count()).isZero();
    }

    @Test
    void theFirstVersionPlexListsBecomesTheDefaultAndItSurvivesTheScansDefaultRule() {
        Film film = film("Gladiator", 98L);
        edition(film, "Original", 155, "Gladiator.m4v");
        edition(film, "Extended", 175, "Gladiator {edition-Extended}.m4v");
        Long id = saved(film).getId();

        importItems(item("Gladiator", 98L, null, 0, 0, media("Gladiator {edition-Extended}.m4v"),
            media("Gladiator.m4v")));

        Film after = film(id);
        Edition extended = after.getEditions().stream().filter(e -> paths(e).get(0).contains("Extended")).findFirst()
            .orElseThrow();
        FilmPreference preference = preferences.findById(id).orElseThrow();
        assertThat(preference.getDefaultEditionId()).isEqualTo(extended.getId());
        assertThat(preference.isDefaultEditionChosen()).isFalse();
        grouping.refreshDefaults();
        em.flush();
        em.clear();
        assertThat(preferences.findById(id).orElseThrow().getDefaultEditionId()).isEqualTo(extended.getId());
    }

    @Test
    void thePlexDefaultNeverOverridesTheOwnersChoice() {
        Film film = film("Gladiator", 98L);
        Edition plain = edition(film, "Original", 155, "Gladiator.m4v");
        edition(film, "Extended", 175, "Gladiator {edition-Extended}.m4v");
        Long id = saved(film).getId();
        FilmPreference chosen = new FilmPreference(id);
        chosen.setDefaultEditionId(plain.getId());
        chosen.setDefaultEditionChosen(true);
        preferences.save(chosen);

        importItems(item("Gladiator", 98L, null, 0, 0, media("Gladiator {edition-Extended}.m4v"),
            media("Gladiator.m4v")));

        assertThat(preferences.findById(id).orElseThrow().getDefaultEditionId()).isEqualTo(plain.getId());
    }

    @Test
    void severalPlexFilmsWithOneIdTakeTheFirstOnesVersionAsTheDefault() {
        Film film = film("Gladiator", 98L);
        edition(film, "Original", 155, "Gladiator.m4v");
        edition(film, "Extended", 175, "Gladiator {edition-Extended}.m4v");
        Long id = saved(film).getId();

        importItems(item("Gladiator", 98L, "Extended", 0, 0, media("Gladiator {edition-Extended}.m4v")),
            item("Gladiator", 98L, "Theatrical", 0, 0, media("Gladiator.m4v")));

        Edition extended = film(id).getEditions().stream().filter(e -> paths(e).get(0).contains("Extended"))
            .findFirst().orElseThrow();
        assertThat(preferences.findById(id).orElseThrow().getDefaultEditionId()).isEqualTo(extended.getId());
        assertThat(film(id).getEditions()).extracting(Edition::getName).containsExactlyInAnyOrder("Extended",
            "Theatrical");
    }

    // ---- which film the files go to ------------------------------------------------------------------------------

    @Test
    void filesInAnotherFilmMoveIntoTheLowestFilmWithThePlexIdWhenTheGuardAdmitsThem() {
        Film low = film("Heat", 949L);
        edition(low, "Original", 100, "Heat.m4v");
        Film high = film("Heat again", 949L);
        edition(high, "Original", 100, "Heat (Extended).m4v");
        Long lowId = saved(low).getId();
        saved(high);

        importItems(item("Heat", 949L, null, 0, 0, media("Heat (Extended).m4v")));

        assertThat(films.count()).isEqualTo(1);
        assertThat(film(lowId).getEditions()).hasSize(2);
    }

    @Test
    void aFilmWhoseFilesAreAnotherTitleDoesNotTakeThePlexFilmsFilesAndTheyKeepTheirOwnFilm() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        Film dc = film("Heat DC", null);
        edition(dc, "Original", 100, "Heat (DC).m4v");
        Long heatId = saved(heat).getId();
        Long dcId = saved(dc).getId();

        importItems(item("Heat DC", 949L, null, 0, 0, media("Heat (DC).m4v")));

        assertThat(films.count()).isEqualTo(2);
        assertThat(film(heatId).getEditions()).hasSize(1);
        assertThat(film(dcId).getEditions()).hasSize(1);
        assertThat(film(dcId).getTmdbId()).isEqualTo(949L);
    }

    @Test
    void aVersionWithAnUnprobedPartIsNeverComparedByRuntimeNorFlagged() {
        assertNoFlagWhenOnePartDuration(null);
    }

    @Test
    void aVersionWithAZeroDurationPartIsTreatedAsUnknownToo() {
        assertNoFlagWhenOnePartDuration(0L);
    }

    private void assertNoFlagWhenOnePartDuration(Long unknown) {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        Edition split = edition(heat, "Original", 50, "Heat HD - pt1.m4v", "Heat HD - pt2.m4v");
        split.originalParts().get(0).setDurationMs(50 * MIN);
        split.originalParts().get(1).setDurationMs(unknown);
        Long heatId = saved(heat).getId();

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v"),
            media("Heat HD - pt1.m4v", "Heat HD - pt2.m4v")));

        assertThat(film(heatId).getEditions()).hasSize(2);
        assertThat(flags()).isEmpty();
    }

    @Test
    void aMultiPartVersionWithEveryDurationKnownAndFarApartIsFlaggedOnce() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        edition(heat, "Original", 130, "Heat HD - pt1.m4v", "Heat HD - pt2.m4v");
        Long heatId = saved(heat).getId();

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v"),
            media("Heat HD - pt1.m4v", "Heat HD - pt2.m4v")));

        assertThat(film(heatId).getEditions()).hasSize(2);
        assertThat(flags()).hasSize(1).allMatch(f -> f.startsWith("Runtimes differ by 23%"));
    }

    @Test
    void filmsWithUntaggedVersionsFarApartInRuntimeAreGatheredAndMergedAndFlaggedOnce() {
        Film short1 = film("Heat", 949L);
        edition(short1, "Original", 100, "Heat.m4v");
        Film long1 = film("Heat again", 949L);
        edition(long1, "Original", 130, "Heat HD.m4v");
        Long shortId = saved(short1).getId();
        saved(long1);

        importItems(item("Heat", 949L, null, 0, 0, media("Heat HD.m4v")));
        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v")));

        assertThat(films.count()).isEqualTo(1);
        assertThat(film(shortId).getEditions()).hasSize(2);
        assertThat(flags()).hasSize(1).allMatch(f -> f.startsWith("Runtimes differ by 23%"));
    }

    // ---- what is never regrouped, and what cannot be ------------------------------------------------------------

    @Test
    void anEditionTheOwnerGroupedIsNeverUnfoldedJoinedOrRenamed() {
        Film film = film("Alien", 348L);
        Edition mine = edition(film, "Mine", 120, "Alien - pt1.m4v", "Alien - pt2.m4v");
        mine.setGroupingSource(GroupingSource.OWNER);
        Long id = saved(film).getId();

        importItems(item("Alien", 348L, "Final Cut", 0, 0, media("Alien - pt2.m4v", "Alien - pt1.m4v")));

        Edition after = film(id).getEditions().get(0);
        assertThat(film(id).getEditions()).hasSize(1);
        assertThat(after.getName()).isEqualTo("Mine");
        assertThat(after.getGroupingSource()).isEqualTo(GroupingSource.OWNER);
        assertThat(paths(after)).containsExactly("Alien - pt1.m4v", "Alien - pt2.m4v");
        assertThat(flags()).anyMatch(f -> f.contains("could not be joined"));
    }

    @Test
    void filesInAnEditionTheOwnerGroupedAreNotJoinedWithOthers() {
        Film film = film("Alien", 348L);
        edition(film, "Mine", 60, "Alien - pt1.m4v").setGroupingSource(GroupingSource.OWNER);
        edition(film, "Original", 60, "Alien - pt2.m4v");
        Long id = saved(film).getId();

        importItems(item("Alien", 348L, null, 0, 0, media("Alien - pt1.m4v", "Alien - pt2.m4v")));

        assertThat(film(id).getEditions()).hasSize(2);
        assertThat(flags()).anyMatch(f -> f.contains("could not be joined"));
    }

    @Test
    void anEditionHoldingARenditionOrAFileWithNoDurationIsNotJoined() {
        Film film = film("Alien", 348L);
        Edition withRendition = edition(film, "Original", 60, "Alien - pt1.m4v");
        MediaFile premade = TestData.file(withRendition, "Alien - pt1 720.m4v");
        premade.setKind(MediaKind.PREMADE_720);
        edition(film, "Original", 60, "Alien - pt2.m4v");
        Film other = film("Brazil", 68L);
        Edition unprobed = edition(other, "Original", 60, "Brazil - pt1.m4v");
        unprobed.getMediaFiles().get(0).setDurationMs(null);
        edition(other, "Original", 60, "Brazil - pt2.m4v");
        Long id = saved(film).getId();
        Long otherId = saved(other).getId();

        importItems(item("Alien", 348L, null, 0, 0, media("Alien - pt1.m4v", "Alien - pt2.m4v")),
            item("Brazil", 68L, null, 0, 0, media("Brazil - pt1.m4v", "Brazil - pt2.m4v")));

        assertThat(film(id).getEditions()).hasSize(2);
        assertThat(film(otherId).getEditions()).hasSize(2);
        assertThat(flags()).hasSize(2).allMatch(f -> f.contains("could not be joined"));
    }

    @Test
    void anEditionWithAnUnreadableDurationIsNotUnfoldedAndFilesPlexDoesNotListStayTogether() {
        Film film = film("Alien", 348L);
        Edition unprobed = edition(film, "Original", 120, "Alien - pt1.m4v", "Alien - pt2.m4v");
        unprobed.getMediaFiles().get(0).setDurationMs(0L);
        Film other = film("Brazil", 68L);
        edition(other, "Original", 180, "Brazil - pt1.m4v", "Brazil - pt2.m4v", "Brazil - pt3.m4v");
        Long id = saved(film).getId();
        Long otherId = saved(other).getId();

        importItems(item("Alien", 348L, null, 0, 0, media("Alien - pt1.m4v"), media("Alien - pt2.m4v")),
            item("Brazil", 68L, null, 0, 0, media("Brazil - pt1.m4v")));

        assertThat(film(id).getEditions()).hasSize(1);
        assertThat(film(otherId).getEditions()).hasSize(2);
        assertThat(film(otherId).getEditions()).anyMatch(e -> paths(e).equals(
            List.of("Brazil - pt2.m4v", "Brazil - pt3.m4v")));
    }

    @Test
    void aFlagIsWrittenOnceHoweverManyImportsFindIt() {
        Film film = film("Alien", 348L);
        edition(film, "Original", 60, "Alien - pt1.m4v");
        saved(film);
        PlexFilm alien = item("Alien", 348L, null, 0, 0, media("Alien - pt1.m4v", "Alien - pt2.m4v"));

        importItems(alien);
        importItems(alien);

        assertThat(flags()).hasSize(1);
    }

    @Test
    void threeVersionsWithOneNameAreNumbered() {
        Film heat = film("Heat", 949L);
        edition(heat, "Original", 100, "Heat.m4v");
        edition(heat, "Original", 100, "Heat HD.m4v");
        edition(heat, "Original", 100, "Heat 1080p.m4v");
        Long id = saved(heat).getId();

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v"), media("Heat HD.m4v"), media("Heat 1080p.m4v")));

        assertThat(film(id).getEditions()).extracting(Edition::getName)
            .containsExactlyInAnyOrder("Original", "Original 2", "Original 3");
    }

    @Test
    void plexsDefaultReplacesTheScansDefaultWhenTheOwnerHasNotChosen() {
        Film film = film("Gladiator", 98L);
        Edition plain = edition(film, "Original", 155, "Gladiator.m4v");
        Edition extended = edition(film, "Extended", 175, "Gladiator {edition-Extended}.m4v");
        Long id = saved(film).getId();
        FilmPreference scans = new FilmPreference(id);
        scans.setDefaultEditionId(plain.getId());
        preferences.save(scans);

        importItems(item("Gladiator", 98L, null, 0, 0, media("Gladiator {edition-Extended}.m4v"),
            media("Gladiator.m4v")));

        assertThat(preferences.findById(id).orElseThrow().getDefaultEditionId()).isEqualTo(extended.getId());
    }

    @Test
    void theFirstPlexItemsDefaultSurvivesASecondItemAddingAnEditionToItsSingleEditionFilm() {
        Film first = film("Heat", null);
        Edition theatrical = edition(first, "Original", 100, "Heat.m4v");
        Film second = film("Heat extended", null);
        edition(second, "Original", 130, "Heat (Extended).m4v");
        Long id = saved(first).getId();
        saved(second);
        PlexFilm one = item("Heat", 949L, "Theatrical", 0, 0, media("Heat.m4v"));
        PlexFilm two = item("Heat", 949L, "Extended", 0, 0, media("Heat (Extended).m4v"));

        importItems(one, two);

        assertThat(film(id).getEditions()).hasSize(2);
        FilmPreference preference = preferences.findById(id).orElseThrow();
        assertThat(preference.getDefaultEditionId()).isEqualTo(theatrical.getId());
        assertThat(preference.isDefaultEditionChosen()).isFalse();

        importItems(one, two);

        assertThat(preferences.findById(id).orElseThrow().getDefaultEditionId()).isEqualTo(theatrical.getId());
    }

    @Test
    void aSingleEditionFilmsPlexDefaultNeverOverridesTheOwnersChoice() {
        Film film = film("Heat", 949L);
        Edition mine = edition(film, "Original", 100, "Heat.m4v");
        Long id = saved(film).getId();
        FilmPreference chosen = new FilmPreference(id);
        chosen.setDefaultEditionId(mine.getId());
        chosen.setDefaultEditionChosen(true);
        preferences.save(chosen);

        importItems(item("Heat", 949L, null, 0, 0, media("Heat.m4v")));

        assertThat(preferences.findById(id).orElseThrow().getDefaultEditionId()).isEqualTo(mine.getId());
        assertThat(preferences.findById(id).orElseThrow().isDefaultEditionChosen()).isTrue();
    }
}
