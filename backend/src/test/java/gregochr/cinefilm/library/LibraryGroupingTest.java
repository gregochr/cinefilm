package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/** The scan's grouping passes over a library held in H2: the owner's examples, and that a second pass does nothing. */
class LibraryGroupingTest extends ApiTestBase {

    private static final Instant MONDAY = NOW.minus(Duration.ofDays(5));

    @Autowired
    private LibraryGrouping grouping;

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private GroupingChangeRepository changes;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbApi tmdb;

    private Film film(String title, MatchStatus status, Long tmdbId, String editionName, long minutes, String path) {
        Film film = TestData.film(null, title, 2000, NOW);
        film.setMatchStatus(status);
        film.setTmdbId(tmdbId);
        build(film, editionName, minutes, path, 1);
        return films.saveAndFlush(film);
    }

    private static Edition build(Film film, String name, long minutes, String path, int part) {
        Edition edition = TestData.edition(film, null, name, minutes * MIN, null);
        MediaFile file = TestData.file(edition, path);
        file.setPartNumber(part);
        return edition;
    }

    /** Adds an edition to a film that is already saved. */
    private Edition addEdition(Film film, String name, long minutes, String path, int part) {
        Edition edition = build(film, name, minutes, path, part);
        em.persist(edition);
        em.flush();
        return edition;
    }

    private void reset() {
        em.flush();
        em.clear();
    }

    private List<String> report(String kind) {
        return changes.findAll().stream().filter(c -> c.getKind().equals(kind)).map(GroupingChange::getSummary)
            .toList();
    }

    // ---- Gladiator: two cuts are one film with two editions, default Theatrical

    @Test
    void gladiatorsTwoCutsAreOneFilmWithTwoEditionsAndTheTheatricalOneIsTheDefault() {
        film("Gladiator", MatchStatus.PENDING, null, "Extended Cut", 175,
            "Gladiator (2000) {edition-Extended Cut}.m4v");
        film("Gladiator", MatchStatus.PENDING, null, "Theatrical Cut", 155,
            "Gladiator (2000) {edition-Theatrical Cut}.m4v");
        reset();

        int merged = grouping.group();
        int defaults = grouping.refreshDefaults();
        reset();

        assertThat(merged).isEqualTo(1);
        assertThat(defaults).isEqualTo(1);
        Film one = films.findAll().stream().sorted(Comparator.comparing(Film::getId)).findFirst().orElseThrow();
        assertThat(films.count()).isEqualTo(1);
        assertThat(one.getEditions()).extracting(Edition::getName).containsExactlyInAnyOrder("Extended Cut",
            "Theatrical Cut");
        Edition theatrical = one.getEditions().stream().filter(e -> e.getName().equals("Theatrical Cut"))
            .findFirst().orElseThrow();
        assertThat(preferences.findById(one.getId()).orElseThrow().getDefaultEditionId())
            .isEqualTo(theatrical.getId());
        assertThat(report(GroupingChange.JOIN)).containsExactly(
            "Grouped the files of \"Gladiator\" into one film with separate editions");
    }

    @Test
    void aSecondPassOverTheSameLibraryChangesNothingAndWritesNothing() {
        film("Gladiator", MatchStatus.PENDING, null, "Extended Cut", 175,
            "Gladiator (2000) {edition-Extended Cut}.m4v");
        film("Gladiator", MatchStatus.PENDING, null, "Theatrical Cut", 155,
            "Gladiator (2000) {edition-Theatrical Cut}.m4v");
        reset();
        grouping.repair(false);
        grouping.group();
        grouping.refreshDefaults();
        reset();
        long rows = changes.count();

        assertThat(grouping.repair(false).done()).isZero();
        assertThat(grouping.group()).isZero();
        assertThat(grouping.refreshDefaults()).isZero();

        assertThat(changes.count()).isEqualTo(rows);
    }

    @Test
    void theOwnersChosenDefaultIsNotTouched() {
        Film gladiator = film("Gladiator", MatchStatus.MATCHED, 98L, "Extended Cut", 175, "G {edition-Ext}.m4v");
        Edition extended = gladiator.getEditions().get(0);
        Edition theatrical = addEdition(gladiator, "Theatrical Cut", 155, "G {edition-Theatrical}.m4v", 1);
        films.saveAndFlush(gladiator);
        FilmPreference chosen = new FilmPreference(gladiator.getId());
        chosen.setDefaultEditionId(extended.getId());
        chosen.setDefaultEditionChosen(true);
        preferences.save(chosen);
        reset();

        assertThat(grouping.refreshDefaults()).isZero();
        reset();

        assertThat(preferences.findById(gladiator.getId()).orElseThrow().getDefaultEditionId())
            .isEqualTo(extended.getId());
        assertThat(theatrical.getId()).isNotEqualTo(extended.getId());
    }

    @Test
    void aFilmWithOneEditionHasNoDefaultToChooseAndAnUnchosenWrongOneIsCorrected() {
        film("Solo", MatchStatus.MATCHED, 1L, "Original", 100, "Solo.m4v");
        Film two = film("Duo", MatchStatus.MATCHED, 2L, "Extended", 130, "Duo - ext.m4v");
        Edition plain = addEdition(two, "Original", 100, "Duo.m4v", 1);
        films.saveAndFlush(two);
        FilmPreference stale = new FilmPreference(two.getId());
        stale.setDefaultEditionId(two.getEditions().get(0).getId());
        preferences.save(stale);
        reset();

        assertThat(grouping.refreshDefaults()).isEqualTo(1);
        reset();

        assertThat(preferences.findAll()).hasSize(1);
        assertThat(preferences.findById(two.getId()).orElseThrow().getDefaultEditionId()).isEqualTo(plain.getId());
    }

    @Test
    void aFlaggedFilmHoldingAnotherTitleIsNotMergedIntoACleanOne() {
        film("Alien", MatchStatus.MATCHED, 348L, "A", 117, "Alien {edition-A}.m4v");
        Film mixed = film("Alien", MatchStatus.PENDING, null, "B", 116, "Alien {edition-B}.m4v");
        addEdition(mixed, "Original", 124, "Prometheus.m4v", 1);
        reset();

        assertThat(grouping.group()).isZero();

        assertThat(films.count()).isEqualTo(2);
        assertThat(changes.count()).isZero();
    }

    @Test
    void aMixedFilmIsNotMergedThroughAPartJoin() {
        Film a = film("Alien", MatchStatus.MATCHED, 348L, "Original", 60, "Alien - pt1.m4v");
        Film b = film("Alien 2", MatchStatus.PENDING, null, "Original", 57, "Alien - pt2.m4v");
        Long prometheus = addEdition(b, "Original", 124, "Prometheus.m4v", 1).getId();
        Long partOne = a.getEditions().get(0).getId();
        Long partTwo = b.getEditions().get(0).getId();
        reset();

        assertThat(grouping.group()).isZero();
        reset();

        assertThat(films.count()).isEqualTo(2);
        assertThat(editions.findById(prometheus).orElseThrow().getFilm().getId()).isEqualTo(b.getId());
        assertThat(editions.findById(partTwo).orElseThrow().getFilm().getId()).isEqualTo(b.getId());
        assertThat(editions.findById(partOne).orElseThrow().originalParts()).hasSize(1);
        assertThat(report(GroupingChange.JOIN)).isEmpty();
    }

    @Test
    void twoFilmsThatEachHoldOnlyOneAlienPartAreStillJoined() {
        Film a = film("Alien", MatchStatus.MATCHED, 348L, "Original", 60, "Alien - pt1.m4v");
        film("Alien 2", MatchStatus.PENDING, null, "Original", 57, "Alien - pt2.m4v");
        Long partOne = a.getEditions().get(0).getId();
        reset();

        assertThat(grouping.group()).isEqualTo(1);
        reset();

        assertThat(films.count()).isEqualTo(1);
        assertThat(editions.findById(partOne).orElseThrow().originalParts()).hasSize(2);
        assertThat(report(GroupingChange.JOIN)).hasSize(1);
    }

    // ---- existing editions named "Original"

    @Test
    void existingRowsNamedOriginalTakeTheirNamesFromTheirTagsAndGladiatorIsOneFilmDefaultTheatrical() {
        film("Gladiator", MatchStatus.PENDING, null, "Original", 175, "Gladiator (2000) {edition-Extended Cut}.m4v");
        film("Gladiator", MatchStatus.PENDING, null, "Original", 155,
            "Gladiator (2000) {edition-Theatrical Cut}.m4v");
        reset();

        grouping.group();
        grouping.refreshDefaults();
        reset();

        Film one = films.findAll().get(0);
        assertThat(films.count()).isEqualTo(1);
        assertThat(one.getEditions()).extracting(Edition::getName).containsExactlyInAnyOrder("Extended Cut",
            "Theatrical Cut");
        Edition theatrical = one.getEditions().stream().filter(e -> e.getName().equals("Theatrical Cut"))
            .findFirst().orElseThrow();
        assertThat(preferences.findById(one.getId()).orElseThrow().getDefaultEditionId())
            .isEqualTo(theatrical.getId());
        long rows = changes.count();

        assertThat(grouping.renameOriginals()).isZero();
        assertThat(grouping.group()).isZero();
        assertThat(grouping.refreshDefaults()).isZero();
        assertThat(changes.count()).isEqualTo(rows);
    }

    @Test
    void aPlainEditionWordNamesTheEditionAndAnUntaggedOneStaysOriginal() {
        film("Alien", MatchStatus.MATCHED, 348L, "Original", 116, "Alien Director's Cut.m4v");
        film("Heat", MatchStatus.MATCHED, 949L, "Original", 170, "Heat.m4v");
        reset();

        assertThat(grouping.renameOriginals()).isEqualTo(1);
        reset();

        assertThat(editions.findAll()).extracting(Edition::getName).containsExactlyInAnyOrder("Director's Cut",
            "Original");
    }

    @Test
    void aConfirmedFilmAndAPlexOrOwnerEditionAreNotRenamed() {
        Film confirmed = film("Gladiator", MatchStatus.MATCHED, 98L, "Original", 175, "G {edition-Extended}.m4v");
        confirmed.setEditionsConfirmed(true);
        Film plex = film("Alien", MatchStatus.MATCHED, 348L, "Original", 116, "A {edition-Final}.m4v");
        plex.getEditions().get(0).setGroupingSource(GroupingSource.PLEX);
        Film owner = film("Dune", MatchStatus.MATCHED, 1L, "Original", 150, "D {edition-Long}.m4v");
        owner.getEditions().get(0).setGroupingSource(GroupingSource.OWNER);
        films.saveAndFlush(confirmed);
        films.saveAndFlush(plex);
        films.saveAndFlush(owner);
        reset();

        assertThat(grouping.renameOriginals()).isZero();
        reset();

        assertThat(editions.findAll()).extracting(Edition::getName).containsOnly("Original");
    }

    @Test
    void anEditionIsNamedFromItsFirstPartWhateverOrderTheFilesAreRead() {
        Film film = film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers {edition-Extended} - pt1.m4v");
        Edition edition = film.getEditions().get(0);
        MediaFile second = TestData.file(edition, "Towers {edition-Extended} - pt2.m4v");
        edition.getMediaFiles().get(0).setPartNumber(2);
        second.setPartNumber(1);
        em.persist(second);
        em.flush();
        reset();

        assertThat(grouping.renameOriginals()).isEqualTo(1);
        reset();

        assertThat(editions.findAll()).extracting(Edition::getName).containsExactly("Extended");
    }

    @Test
    void aMultiPartEditionInOrderIsNamedToo() {
        Film film = film("Dune", MatchStatus.MATCHED, 1L, "Original", 90, "Dune {edition-Long} - pt1.m4v");
        MediaFile second = TestData.file(film.getEditions().get(0), "Dune {edition-Long} - pt2.m4v");
        second.setPartNumber(2);
        em.persist(second);
        em.flush();
        reset();

        assertThat(grouping.renameOriginals()).isEqualTo(1);
    }

    // ---- The Two Towers: Extended pt1 + pt2 + Theatrical Cut

    @Test
    void twoTowersExtendedPartsJoinIntoOneEditionBesideTheTheatricalCutInOneFilm() {
        Film pt1 = film("The Two Towers", MatchStatus.MATCHED, 121L, "Extended", 90,
            "LOTR/The Two Towers (2002) {edition-Extended} - pt1.m4v");
        film("The Two Towers - pt2", MatchStatus.NEEDS_MATCH, null, "Extended", 110,
            "LOTR/The Two Towers (2002) {edition-Extended} - pt2.m4v.m4v");
        film("The Two Towers", MatchStatus.PENDING, null, "Theatrical Cut", 179,
            "LOTR/The Two Towers (2002) {edition-Theatrical Cut}.m4v");
        Long extended = pt1.getEditions().get(0).getId();
        reset();

        int done = grouping.group();
        grouping.refreshDefaults();
        reset();

        assertThat(done).isEqualTo(2);
        assertThat(films.findAll()).extracting(Film::getId).containsExactly(pt1.getId());
        Film film = films.findById(pt1.getId()).orElseThrow();
        assertThat(film.getEditions()).hasSize(2);
        Edition joined = editions.findById(extended).orElseThrow();
        assertThat(joined.originalParts()).extracting(MediaFile::getPath, MediaFile::getPartNumber).containsExactly(
            org.assertj.core.groups.Tuple.tuple("LOTR/The Two Towers (2002) {edition-Extended} - pt1.m4v", 1),
            org.assertj.core.groups.Tuple.tuple("LOTR/The Two Towers (2002) {edition-Extended} - pt2.m4v.m4v", 2));
        assertThat(joined.getRuntimeMs()).isEqualTo(200 * MIN);
        assertThat(report(GroupingChange.JOIN)).containsExactlyInAnyOrder(
            "Grouped the files of \"The Two Towers\" into one film with separate editions",
            "Joined 2 files of \"The Two Towers\" into one edition \"Extended\"");
        assertThat(report(GroupingChange.JOIN)).noneMatch(s -> s.toLowerCase().contains("pt1"));
    }

    @Test
    void existingRowsWherePartOneAndTwoAreSeparateFilmsAreJoinedWithTheirStateFolded() {
        Film pt1 = film("The Two Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        film("Towers - pt2", MatchStatus.NEEDS_MATCH, null, "Original", 110, "Towers - pt2.m4v");
        saveState(TestData.state(pt1.getEditions().get(0), 0, true, MONDAY, MONDAY));
        Long survivor = pt1.getEditions().get(0).getId();
        reset();

        grouping.group();
        reset();

        PlaybackState state = states.findById(survivor).orElseThrow();
        assertThat(state.isWatched()).as("part 1 watched, part 2 unwatched").isFalse();
        assertThat(state.getPositionMs()).as("resume at the start of the second part").isEqualTo(90 * MIN);
        assertThat(films.count()).isEqualTo(1);
        assertThat(films.findAll().get(0).getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
    }

    @Test
    void partTwosResumeBecomesAGlobalPosition() {
        Film pt1 = film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        Film pt2 = film("Towers 2", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        saveState(TestData.state(pt2.getEditions().get(0), 25 * MIN, false, MONDAY, null));
        Long survivor = pt1.getEditions().get(0).getId();
        reset();

        grouping.group();
        reset();

        assertThat(states.findById(survivor).orElseThrow().getPositionMs()).isEqualTo(115 * MIN);
    }

    @Test
    void preferencesAreRepointedAndTheAbsorbedEditionIsGone() {
        Film pt1 = film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        Film pt2 = film("Towers 2", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        Long absorbed = pt2.getEditions().get(0).getId();
        FilmPreference preference = new FilmPreference(pt1.getId());
        preference.setLastEditionId(absorbed);
        preferences.save(preference);
        reset();

        grouping.group();
        reset();

        assertThat(preferences.findById(pt1.getId()).orElseThrow().getLastEditionId())
            .isEqualTo(pt1.getEditions().get(0).getId());
        assertThat(editions.findById(absorbed)).isEmpty();
    }

    // ---- what must not be joined

    @Test
    void aFilmTheOwnerSettledIsNeverRegrouped() {
        Film pt1 = film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        Film pt2 = film("Towers 2", MatchStatus.MATCHED, 122L, "Original", 110, "Towers - pt2.m4v");
        pt2.setEditionsConfirmed(true);
        films.saveAndFlush(pt2);
        reset();

        assertThat(grouping.group()).isZero();
        assertThat(grouping.repair(false).done()).isZero();

        assertThat(films.count()).isEqualTo(2);
        assertThat(changes.count()).isZero();
        assertThat(pt1.getId()).isNotEqualTo(pt2.getId());
    }

    @Test
    void aDuplicatePartOneIsNotJoined() {
        film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        film("Towers 2", MatchStatus.PENDING, null, "Original", 90, "Towers - pt1.mkv");
        film("Towers 3", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        reset();

        assertThat(grouping.group()).isZero();

        assertThat(films.count()).isEqualTo(3);
        assertThat(changes.count()).isZero();
    }

    @Test
    void anUntaggedFileAndAPartTwoAreNotJoined() {
        film("Towers", MatchStatus.MATCHED, 121L, "Original", 179, "Towers.m4v");
        film("Towers 2", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        reset();

        assertThat(grouping.group()).isZero();

        assertThat(films.count()).isEqualTo(2);
        assertThat(editions.count()).isEqualTo(2);
    }

    @Test
    void aPartWithNoDurationYetIsNotJoinedUntilItHasOne() {
        Film pt1 = film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        Film pt2 = film("Towers 2", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        MediaFile second = pt2.getEditions().get(0).getMediaFiles().get(0);
        second.setDurationMs(null);
        mediaFiles.saveAndFlush(second);
        reset();

        assertThat(grouping.group()).isZero();
        assertThat(films.count()).isEqualTo(2);

        MediaFile probed = mediaFiles.findById(second.getId()).orElseThrow();
        probed.setDurationMs(110 * MIN);
        mediaFiles.saveAndFlush(probed);
        reset();

        assertThat(grouping.group()).isEqualTo(1);
        assertThat(films.count()).isEqualTo(1);
        assertThat(pt1.getId()).isNotNull();
    }

    // ---- repair

    @Test
    void rockyFourAndFiveInOneFilmAreSplitAndTheSecondRunWritesNothing() {
        Film rocky = film("Rocky IV", MatchStatus.MATCHED, 1001L, "Original", 91, "Rocky IV (1985).m4v");
        Edition five = addEdition(rocky, "Original", 104, "Rocky V (1990).m4v", 1);
        films.saveAndFlush(rocky);
        saveState(TestData.state(five, 30 * MIN, false, MONDAY, null));
        FilmPreference preference = new FilmPreference(rocky.getId());
        preference.setLastEditionId(five.getId());
        preference.setDefaultEditionId(five.getId());
        preference.setQuality("720p");
        preferences.save(preference);
        Long fiveId = five.getId();
        reset();

        int first = grouping.repair(false).done();
        reset();

        assertThat(first).isEqualTo(1);
        assertThat(films.count()).isEqualTo(2);
        Film kept = films.findById(rocky.getId()).orElseThrow();
        assertThat(kept.getEditions()).extracting(e -> e.originalFile().getPath()).containsExactly(
            "Rocky IV (1985).m4v");
        Film moved = films.findAll().stream().filter(f -> !f.getId().equals(rocky.getId())).findFirst()
            .orElseThrow();
        assertThat(moved.getTitle()).isEqualTo("Rocky V");
        assertThat(moved.getYear()).isEqualTo(1990);
        assertThat(moved.getMatchStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(moved.getTmdbId()).isNull();
        assertThat(moved.getEditions()).extracting(Edition::getId).containsExactly(fiveId);
        assertThat(states.findById(fiveId).orElseThrow().getPositionMs()).isEqualTo(30 * MIN);
        FilmPreference after = preferences.findById(rocky.getId()).orElseThrow();
        assertThat(after.getLastEditionId()).isNull();
        assertThat(after.getDefaultEditionId()).isNull();
        assertThat(after.getQuality()).isEqualTo("720p");
        assertThat(report(GroupingChange.SPLIT)).containsExactly(
            "Moved \"Rocky V\" out of \"Rocky IV\" into films of their own");
        long rows = changes.count();

        assertThat(grouping.repair(false).done()).isZero();
        assertThat(grouping.group()).isZero();

        assertThat(changes.count()).isEqualTo(rows);
        assertThat(films.count()).isEqualTo(2);
    }

    @Test
    void streamChoicesPointingIntoTheMovedEditionAreForgotten() {
        Film rocky = film("Rocky IV", MatchStatus.MATCHED, 1001L, "Original", 91, "Rocky IV.m4v");
        Edition five = addEdition(rocky, "Original", 104, "Rocky V.m4v", 1);
        films.saveAndFlush(rocky);
        MediaFile movedFile = five.getMediaFiles().get(0);
        FilmPreference preference = new FilmPreference(rocky.getId());
        preference.setAudioStreamId(movedFile.getAudioStreams().get(0).getId());
        preference.setSubtitle(String.valueOf(movedFile.getSubtitleStreams().get(0).getId()));
        preferences.save(preference);
        reset();

        grouping.repair(false);
        reset();

        FilmPreference after = preferences.findById(rocky.getId()).orElseThrow();
        assertThat(after.getAudioStreamId()).isNull();
        assertThat(after.getSubtitle()).isNull();
    }

    @Test
    void aStreamChoiceInTheFilmThatStaysIsKept() {
        Film rocky = film("Rocky IV", MatchStatus.MATCHED, 1001L, "Original", 91, "Rocky IV.m4v");
        addEdition(rocky, "Original", 104, "Rocky V.m4v", 1);
        films.saveAndFlush(rocky);
        MediaFile stays = rocky.getEditions().get(0).getMediaFiles().get(0);
        FilmPreference preference = new FilmPreference(rocky.getId());
        preference.setAudioStreamId(stays.getAudioStreams().get(1).getId());
        preference.setSubtitle("off");
        preference.setLastEditionId(rocky.getEditions().get(0).getId());
        preferences.save(preference);
        Long audio = preference.getAudioStreamId();
        reset();

        grouping.repair(false);
        reset();

        FilmPreference after = preferences.findById(rocky.getId()).orElseThrow();
        assertThat(after.getAudioStreamId()).isEqualTo(audio);
        assertThat(after.getSubtitle()).isEqualTo("off");
        assertThat(after.getLastEditionId()).isEqualTo(rocky.getEditions().get(0).getId());
    }

    @Test
    void alienAndAlienDirectorsCutAreOneFilmAndNotSplit() {
        Film alien = film("Alien", MatchStatus.MATCHED, 348L, "Original", 117, "Alien.m4v");
        addEdition(alien, "Director's Cut", 116, "Alien Director's Cut.m4v", 1);
        films.saveAndFlush(alien);
        reset();

        assertThat(grouping.repair(false).done()).isZero();

        assertThat(films.count()).isEqualTo(1);
        assertThat(changes.count()).isZero();
    }

    @Test
    void aMultiPartEditionWhoseTitlesDifferIsUnfoldedBeforeTheOtherTitleMoves() {
        Film rocky = film("Rocky IV", MatchStatus.MATCHED, 1001L, "Original", 90, "Rocky IV.m4v");
        Edition edition = rocky.getEditions().get(0);
        MediaFile stray = TestData.file(edition, "Rocky V.m4v");
        stray.setDurationMs(110 * MIN);
        stray.setPartNumber(2);
        edition.recomputeRuntime();
        em.persist(stray);
        em.flush();
        saveState(TestData.state(edition, 100 * MIN, false, MONDAY, null));
        Long keptEdition = edition.getId();
        Long strayId = stray.getId();
        reset();

        grouping.repair(false);
        reset();

        Film kept = films.findById(rocky.getId()).orElseThrow();
        assertThat(kept.getEditions()).extracting(Edition::getId).containsExactly(keptEdition);
        assertThat(kept.getEditions().get(0).originalParts()).extracting(MediaFile::getPath)
            .containsExactly("Rocky IV.m4v");
        assertThat(kept.getEditions().get(0).getRuntimeMs()).isEqualTo(90 * MIN);
        Film moved = films.findAll().stream().filter(f -> !f.getId().equals(rocky.getId())).findFirst()
            .orElseThrow();
        Edition movedEdition = moved.getEditions().get(0);
        assertThat(movedEdition.originalParts()).extracting(MediaFile::getId).containsExactly(strayId);
        assertThat(movedEdition.getRuntimeMs()).isEqualTo(110 * MIN);
        assertThat(states.findById(movedEdition.getId()).orElseThrow().getPositionMs()).isEqualTo(10 * MIN);
        assertThat(states.findById(keptEdition)).isEmpty();
    }

    // ---- TMDB evidence (the real fixtures: Elf holding Enchanted, TMDB's exact "Enchanted" hits)

    private static final SearchHit ENCHANTED_2007 = new SearchHit(4523, "Enchanted", "Enchanted", 2007, 30, null);
    private static final SearchHit ENCHANTED_1998 = new SearchHit(404277, "Enchanted", "Enchanted", 1998, 1, null);
    private static final SearchHit ELLA_ENCHANTED = new SearchHit(14442, "Ella Enchanted", "Ella Enchanted", 2004,
        9, null);

    private static MovieDetails details(long id, String title) {
        return new MovieDetails(id, title, title, 2003, null, 97, List.of(), null, null, 1, List.of(), List.of(),
            null);
    }

    private Film elfHoldingEnchanted() {
        Film elf = film("Elf", MatchStatus.MATCHED, 10719L, "Original", 97, "Elf.m4v");
        addEdition(elf, "Original", 103, "Enchanted.m4v", 1);
        films.saveAndFlush(elf);
        return elf;
    }

    private void tmdbKnowsElfAndEnchanted() {
        when(tmdb.movie(10719L)).thenReturn(Optional.of(details(10719L, "Elf")));
        when(tmdb.alternativeTitles(10719L)).thenReturn(List.of());
        when(tmdb.search("Enchanted", null)).thenReturn(List.of(ENCHANTED_2007, ENCHANTED_1998, ELLA_ENCHANTED));
    }

    @Test
    void elfHoldingEnchantedIsSplitOnTmdbEvidenceAndTheNewFilmIsNotMergedBackToElf() {
        Film elf = elfHoldingEnchanted();
        tmdbKnowsElfAndEnchanted();
        reset();

        LibraryGrouping.RepairResult result = grouping.repair(true);
        reset();

        assertThat(result).isEqualTo(new LibraryGrouping.RepairResult(1, false));
        assertThat(films.count()).isEqualTo(2);
        Film kept = films.findById(elf.getId()).orElseThrow();
        assertThat(kept.getEditions()).extracting(e -> e.originalFile().getPath()).containsExactly("Elf.m4v");
        Film moved = films.findAll().stream().filter(f -> !f.getId().equals(elf.getId())).findFirst().orElseThrow();
        assertThat(moved.getTitle()).isEqualTo("Enchanted");
        assertThat(moved.getMatchStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(ingest.pending()).extracting(LibraryIngest.Pending::title).containsExactly("Enchanted");
        assertThat(report(GroupingChange.SPLIT)).containsExactly("Moved \"Enchanted\" out of \"Elf\" into a film of "
            + "its own (TMDB lists it as a different film)");
        assertThat(report(GroupingChange.FLAG)).isEmpty();

        ingest.applyOutcome(moved.getId(), new MatchOutcome(Status.MATCHED, details(10719L, "Elf"), List.of()));
        reset();

        assertThat(films.count()).isEqualTo(2);
        assertThat(films.findById(moved.getId()).orElseThrow().getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);

        ingest.applyOutcome(moved.getId(), new MatchOutcome(Status.MATCHED, details(4523L, "Enchanted"),
            List.of()));
        reset();

        Film matched = films.findById(moved.getId()).orElseThrow();
        assertThat(matched.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(matched.getTmdbId()).isEqualTo(4523L);
    }

    @Test
    void aSecondRepairAfterATmdbSplitAsksNothingAndChangesNothing() {
        elfHoldingEnchanted();
        tmdbKnowsElfAndEnchanted();
        reset();
        grouping.repair(true);
        reset();
        long rows = changes.count();
        clearInvocations(tmdb);

        assertThat(grouping.repair(true)).isEqualTo(new LibraryGrouping.RepairResult(0, false));

        assertThat(changes.count()).isEqualTo(rows);
        assertThat(films.count()).isEqualTo(2);
        verifyNoMoreInteractions(tmdb);
    }

    @Test
    void aTmdbOutageKeepsTheFilmFlaggedReportsItAndMakesNoFurtherSearch() {
        elfHoldingEnchanted();
        film("Ghost", MatchStatus.MATCHED, 251L, "Original", 127, "Ghost.m4v");
        Film ghost = films.findAll().stream().filter(f -> f.getTitle().equals("Ghost")).findFirst().orElseThrow();
        addEdition(ghost, "Original", 88, "The Witches.m4v", 1);
        films.saveAndFlush(ghost);
        when(tmdb.movie(10719L)).thenReturn(Optional.of(details(10719L, "Elf")));
        when(tmdb.alternativeTitles(10719L)).thenReturn(List.of());
        when(tmdb.search("Enchanted", null)).thenThrow(new TmdbUnavailableException("down", null));
        reset();

        LibraryGrouping.RepairResult result = grouping.repair(true);

        assertThat(result).isEqualTo(new LibraryGrouping.RepairResult(2, true));
        assertThat(films.count()).isEqualTo(2);
        assertThat(report(GroupingChange.FLAG)).containsExactly(
            "Files look like different films: \"Elf\", \"Enchanted\" in \"Elf\"",
            "Files look like different films: \"Ghost\", \"The Witches\" in \"Ghost\"");
        verify(tmdb, never()).search("The Witches", null);
        verify(tmdb, never()).alternativeTitles(251L);
    }

    @Test
    void withTmdbNotUsableRepairMakesNoTmdbCallAndTheFilmStaysFlagged() {
        elfHoldingEnchanted();
        reset();

        assertThat(grouping.repair(false)).isEqualTo(new LibraryGrouping.RepairResult(1, false));

        verifyNoMoreInteractions(tmdb);
        assertThat(films.count()).isEqualTo(1);
        assertThat(report(GroupingChange.FLAG)).hasSize(1);
    }

    @Test
    void aGroupTaggedWithTheFilmsOwnIdIsNeverLookedUpWhateverTmdbWouldSay() {
        Film elf = film("Elf", MatchStatus.MATCHED, 10719L, "Original", 97, "Elf.m4v");
        addEdition(elf, "Original", 103, "Enchanted {tmdb-10719}.m4v", 1);
        films.saveAndFlush(elf);
        tmdbKnowsElfAndEnchanted();
        reset();

        assertThat(grouping.repair(true)).isEqualTo(new LibraryGrouping.RepairResult(1, false));

        verifyNoMoreInteractions(tmdb);
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void aBladeRunnerCopyWithAnEditionTagStaysFlaggedAndIsNotLookedUp() {
        Film blade = film("Blade Runner", MatchStatus.MATCHED, 78L, "Original", 117, "Blade Runner.m4v");
        addEdition(blade, "The Final Cut", 117, "Blader Runner (1982) {edition-The Final Cut}.m4v", 1);
        films.saveAndFlush(blade);
        reset();

        assertThat(grouping.repair(true)).isEqualTo(new LibraryGrouping.RepairResult(1, false));

        verifyNoMoreInteractions(tmdb);
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void aSequelSplitMakesNoTmdbCallEvenWhenTmdbIsUsable() {
        Film rocky = film("Rocky IV", MatchStatus.MATCHED, 1001L, "Original", 91, "Rocky IV (1985).m4v");
        addEdition(rocky, "Original", 104, "Rocky V (1990).m4v", 1);
        films.saveAndFlush(rocky);
        reset();

        assertThat(grouping.repair(true)).isEqualTo(new LibraryGrouping.RepairResult(1, false));

        verifyNoMoreInteractions(tmdb);
        assertThat(report(GroupingChange.SPLIT)).containsExactly(
            "Moved \"Rocky V\" out of \"Rocky IV\" into films of their own");
    }

    @Test
    void filesThatLookLikeDifferentFilmsAreFlaggedOnceNotEveryScan() {
        Film mixed = film("Heat", MatchStatus.MATCHED, 949L, "Original", 170, "Collateral.m4v");
        addEdition(mixed, "Original", 117, "Alien.m4v", 1);
        films.saveAndFlush(mixed);
        reset();

        assertThat(grouping.repair(false).done()).isEqualTo(1);
        assertThat(grouping.repair(false).done()).isZero();

        assertThat(report(GroupingChange.FLAG)).containsExactly(
            "Files look like different films: \"Alien\", \"Collateral\" in \"Heat\"");
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void aFilmWithOnlyOnePartPerGroupIsLeftAloneByTheJoin() {
        film("Heat", MatchStatus.MATCHED, 949L, "Original", 170, "Heat.m4v");
        reset();

        assertThat(grouping.group()).isZero();
        assertThat(grouping.refreshDefaults()).isZero();
    }

    // ---- failures never stop a pass

    @Test
    void aFailedFilmMergeIsSkippedAndTheRestOfThePassStillRuns() {
        film("Gladiator", MatchStatus.PENDING, null, "Extended Cut", 175, "G {edition-Extended Cut}.m4v");
        film("Gladiator", MatchStatus.PENDING, null, "Theatrical Cut", 155, "G {edition-Theatrical Cut}.m4v");
        film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        film("Towers 2", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        reset();
        LibraryIngest failing = mock(LibraryIngest.class);
        doThrow(new IllegalStateException("boom")).when(failing).merge(anyLong(), anyLong());
        EditionJoin failingJoin = mock(EditionJoin.class);
        doThrow(new IllegalArgumentException("boom")).when(failingJoin).join(anyList());
        LibraryGrouping broken = new LibraryGrouping(films, editions, mediaFiles, preferences, changes,
            failingJoin, failing, tmdb, transactions, CLOCK);

        assertThat(broken.group()).isZero();

        assertThat(films.count()).isEqualTo(4);
        assertThat(changes.count()).isZero();
    }

    @Test
    void aFailedSplitIsSkipped() {
        Film rocky = film("Rocky IV", MatchStatus.MATCHED, 1001L, "Original", 90, "Rocky IV.m4v");
        Edition edition = rocky.getEditions().get(0);
        MediaFile stray = TestData.file(edition, "Rocky V.m4v");
        stray.setPartNumber(2);
        em.persist(stray);
        em.flush();
        reset();
        EditionJoin failingJoin = mock(EditionJoin.class);
        doThrow(new IllegalStateException("boom")).when(failingJoin).unfold(anyLong(), anyList());
        LibraryGrouping broken = new LibraryGrouping(films, editions, mediaFiles, preferences, changes,
            failingJoin, ingest, tmdb, transactions, CLOCK);

        assertThat(broken.repair(false).done()).isZero();

        assertThat(changes.count()).isZero();
    }

    @Test
    void factsSkipSettledFilmsAndDescribeEveryOriginal() {
        Film open = film("Heat", MatchStatus.MATCHED, 949L, "Original", 170, "Heat.m4v");
        Film settled = film("Alien", MatchStatus.MATCHED, 348L, "Original", 117, "Alien.m4v");
        settled.setEditionsConfirmed(true);
        films.saveAndFlush(settled);
        open.getEditions().get(0).setGroupingSource(GroupingSource.PLEX);
        films.saveAndFlush(open);
        MediaFile premade = TestData.file(open.getEditions().get(0), "Heat.720.m4v");
        premade.setKind(gregochr.cinefilm.film.MediaKind.PREMADE_720);
        em.persist(premade);
        em.flush();
        reset();

        List<FileFact> facts = grouping.facts();

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.path()).isEqualTo("Heat.m4v");
            assertThat(fact.filmTitle()).isEqualTo("Heat");
            assertThat(fact.filmTmdbId()).isEqualTo(949L);
            assertThat(fact.editionSource()).isEqualTo(GroupingSource.PLEX);
            assertThat(fact.joinable()).isTrue();
            assertThat(fact.extraFiles()).isTrue();
            assertThat(fact.parsed().comparisonTitle()).isEqualTo("heat");
        });
    }

    @Test
    void joiningPartFilmsKeepsTheOwnersForcedNeedChoiceOfTheAbsorbedOne() {
        Film pt1 = film("Towers", MatchStatus.MATCHED, 121L, "Original", 90, "Towers - pt1.m4v");
        Film pt2 = film("Towers 2", MatchStatus.PENDING, null, "Original", 110, "Towers - pt2.m4v");
        pt2.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(pt2);
        reset();

        grouping.group();
        reset();

        assertThat(films.findAll()).singleElement().satisfies(f -> {
            assertThat(f.getId()).isEqualTo(pt1.getId());
            assertThat(f.getForcedNeedOwner()).isEqualTo(ForcedNeedChoice.NEEDED);
        });
    }
}
