package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.FieldError;
import gregochr.cinefilm.config.FieldErrorsException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.EditionsView;
import gregochr.cinefilm.library.EditionDtos.FileRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import gregochr.cinefilm.library.EditionDtos.SaveResult;
import gregochr.cinefilm.playback.PlaybackState;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Edit editions over real repositories: what the panel loads and what a save does to the library. */
class EditionArrangerTest extends ApiTestBase {

    private static final String PT1 = "Towers/The Two Towers {edition-Extended Edition} - pt1.m4v";
    private static final String PT2 = "Towers/The Two Towers {edition-Extended Edition} - pt2.m4v";
    private static final String THEATRICAL = "Towers/The Two Towers {edition-Theatrical Cut}.m4v";

    @Autowired
    private EditionArranger arranger;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private GroupingChangeRepository groupingChanges;

    @Autowired
    private AdminService admin;

    @Autowired
    private LibraryLock lock;

    @AfterEach
    void releaseTheLock() {
        lock.release();
    }

    /** pt1 and pt2 each in an edition of their own (as the scan leaves them when it cannot tell) and the theatrical. */
    private Film towers() {
        Film film = film(null, "The Two Towers", 2002, NOW);
        file(edition(film, null, "Original", 114 * MIN, 5 * MIN), PT1);
        file(edition(film, null, "Original", 121 * MIN, null), PT2);
        file(edition(film, null, "Original", 179 * MIN, 160 * MIN), THEATRICAL);
        return save(film);
    }

    private static Long editionId(Film film, int index) {
        return film.getEditions().get(index).getId();
    }

    private static Long fileId(Film film, int edition) {
        return film.getEditions().get(edition).getMediaFiles().get(0).getId();
    }

    private static FileRequest f(Long fileId, Integer part) {
        return new FileRequest(fileId, part);
    }

    private static EditionRequest ed(Long id, String name, boolean isDefault, FileRequest... files) {
        return new EditionRequest(id, name, isDefault, List.of(files));
    }

    private SaveRequest typical(Film film) {
        return new SaveRequest(List.of(
            ed(editionId(film, 0), "Extended Edition", false, f(fileId(film, 0), 1), f(fileId(film, 1), 2)),
            ed(editionId(film, 2), "Theatrical Cut", true, f(fileId(film, 2), null))), List.of());
    }

    // ---- what the panel loads ----

    @Test
    void theViewListsEveryFileWithItsFactsTheEditionsAndTheSuggestion() {
        Film film = towers();

        EditionsView view = arranger.view(film.getId());

        assertThat(view.files()).hasSize(3);
        assertThat(view.files().get(0)).isEqualTo(new EditionDtos.EditionFileDto(fileId(film, 0),
            "The Two Towers {edition-Extended Edition} - pt1.m4v", 114 * MIN, 1920, 1080, "HEVC", null, "PRESENT",
            editionId(film, 0), 1));
        assertThat(view.editions()).extracting(EditionDtos.EditionRefDto::isDefault)
            .as("the scan's pick (the first Original) until the owner chooses").containsExactly(true, false, false);
        assertThat(view.suggestion().reason()).isEqualTo("From the edition tags and part numbers");
        assertThat(view.suggestion().editions()).extracting(EditionDtos.ArrangedEdition::name)
            .containsExactly("Extended Edition", "Theatrical Cut");
        assertThat(view.suggestion().editions().get(0).id()).isEqualTo(editionId(film, 0));
        assertThat(view.suggestion().editions().get(0).files()).containsExactly(
            new EditionDtos.ArrangedFile(fileId(film, 0), 1), new EditionDtos.ArrangedFile(fileId(film, 1), 2));
        assertThat(view.suggestion().defaultName()).isEqualTo("Extended Edition");
    }

    @Test
    void theStoredDefaultIsTheCurrentDefaultWhileItExists() {
        Film film = towers();
        FilmPreference preference = new FilmPreference(film.getId());
        preference.setDefaultEditionId(editionId(film, 2));
        preferences.save(preference);

        EditionsView view = arranger.view(film.getId());

        assertThat(view.editions()).extracting(EditionDtos.EditionRefDto::isDefault)
            .containsExactly(false, false, true);
        assertThat(view.suggestion().defaultName()).isEqualTo("Theatrical Cut");
    }

    @Test
    void aStoredDefaultThatIsGoneFallsBackToTheScansPick() {
        Film film = towers();
        FilmPreference preference = new FilmPreference(film.getId());
        preference.setDefaultEditionId(-5L);
        preferences.save(preference);

        assertThat(arranger.view(film.getId()).editions()).extracting(EditionDtos.EditionRefDto::isDefault)
            .containsExactly(true, false, false);
    }

    @Test
    void aFilmTheOwnerSettledIsShownAsArranged() {
        Film film = towers();
        film.setEditionsConfirmed(true);
        films.save(film);

        EditionsView view = arranger.view(film.getId());

        assertThat(view.suggestion().reason()).isEqualTo("As you last arranged it");
        assertThat(view.suggestion().editions()).extracting(EditionDtos.ArrangedEdition::name)
            .containsExactly("Original", "Original", "Original");
    }

    @Test
    void aFilmWithoutFilesHasAnEmptyView() {
        Film film = save(film(null, "Empty", 2000, NOW));

        EditionsView view = arranger.view(film.getId());

        assertThat(view.files()).isEmpty();
        assertThat(view.editions()).isEmpty();
        assertThat(view.suggestion().editions()).isEmpty();
    }

    @Test
    void theViewOfAnUnknownFilmIsNotFound() {
        assertThatThrownBy(() -> arranger.view(9999L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No film 9999");
    }

    // ---- saving ----

    @Test
    void savingJoinsThePartsKeepsTheOtherEditionAndRecordsTheOwnersChoices() {
        Film film = towers();
        Long extended = editionId(film, 0);
        Long absorbed = editionId(film, 1);
        Long theatrical = editionId(film, 2);
        Long part2 = fileId(film, 1);
        saveState(state(film.getEditions().get(0), 20 * MIN, false, NOW, null));
        saveState(state(film.getEditions().get(1), 10 * MIN, false, NOW, null));
        saveState(state(film.getEditions().get(2), 0, true, NOW, NOW));

        SaveResult result = arranger.save(film.getId(), typical(film));

        assertThat(result).isEqualTo(new SaveResult(film.getId(), List.of()));
        Film saved = films.findById(film.getId()).orElseThrow();
        assertThat(saved.isEditionsConfirmed()).isTrue();
        assertThat(saved.getEditions()).extracting(Edition::getId).containsExactly(extended, theatrical);
        Edition joined = saved.getEditions().get(0);
        assertThat(joined.getName()).isEqualTo("Extended Edition");
        assertThat(joined.getGroupingSource()).isEqualTo(GroupingSource.OWNER);
        assertThat(joined.originalParts()).extracting(MediaFile::getId).contains(part2).hasSize(2);
        assertThat(joined.originalParts()).extracting(MediaFile::getPartNumber).containsExactly(1, 2);
        assertThat(joined.getRuntimeMs()).isEqualTo(235 * MIN);
        assertThat(joined.getCreditsMarkerMs()).as("a time on the old timeline").isNull();
        Edition other = saved.getEditions().get(1);
        assertThat(other.getName()).isEqualTo("Theatrical Cut");
        assertThat(other.getCreditsMarkerMs()).as("its files did not change").isEqualTo(160 * MIN);
        assertThat(other.originalParts().get(0).getPartNumber()).isEqualTo(1);
        assertThat(states.findById(extended)).get().extracting(PlaybackState::getPositionMs).isEqualTo(20 * MIN);
        assertThat(states.findById(theatrical)).get().extracting(PlaybackState::isWatched).isEqualTo(true);
        assertThat(states.findById(absorbed)).as("an edition left with no files goes with its state").isEmpty();
        assertThat(editions.findById(absorbed)).isEmpty();
        FilmPreference preference = preferences.findById(film.getId()).orElseThrow();
        assertThat(preference.getDefaultEditionId()).isEqualTo(theatrical);
        assertThat(preference.isDefaultEditionChosen()).isTrue();
        assertThat(lock.holder()).isNull();
        assertThat(groupingChanges.findTop20ByOrderByHappenedAtDescIdDesc()).singleElement().satisfies(change -> {
            assertThat(change.getKind()).isEqualTo(GroupingChange.JOIN);
            assertThat(change.getSummary())
                .isEqualTo("You arranged \"The Two Towers\" into 2 editions with Edit editions");
            assertThat(change.getHappenedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aRenamedEditionKeepsItsResumePositionAndWatchedState() {
        Film built = film(null, "Heat", 1995, NOW);
        file(edition(built, null, "Original", 170 * MIN, null), "Heat.m4v");
        Film film = save(built);
        Long id = editionId(film, 0);
        saveState(state(film.getEditions().get(0), 90 * MIN, false, NOW, null));

        arranger.save(film.getId(), new SaveRequest(List.of(ed(id, "  Director's Cut ", true,
            f(fileId(film, 0), null))), null));

        Edition renamed = editions.findById(id).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("Director's Cut");
        assertThat(states.findById(id)).get().satisfies(state -> {
            assertThat(state.getPositionMs()).isEqualTo(90 * MIN);
            assertThat(state.isWatched()).isFalse();
        });
    }

    @Test
    void aResumePositionIsClampedToTheNewRuntimeAndAFileNotThisFilmBecomesAnUnmatchedFilm() {
        Film built = film(null, "Rocky IV", 1985, NOW);
        Edition both = edition(built, null, "Original", 91 * MIN, null);
        file(both, "Rocky IV.m4v");
        MediaFile intruder = file(both, "Rocky V (1990).m4v");
        intruder.setPartNumber(2);
        intruder.setDurationMs(104 * MIN);
        Film film = save(built);
        Long id = editionId(film, 0);
        Long rocky = film.getEditions().get(0).originalParts().get(0).getId();
        Long rockyV = film.getEditions().get(0).originalParts().get(1).getId();
        saveState(state(film.getEditions().get(0), 150 * MIN, false, NOW, null));

        SaveResult result = arranger.save(film.getId(), new SaveRequest(
            List.of(ed(id, "Original", true, f(rocky, null))), List.of(rockyV)));

        assertThat(editions.findById(id).orElseThrow().getRuntimeMs()).isEqualTo(91 * MIN);
        assertThat(states.findById(id)).get().extracting(PlaybackState::getPositionMs).isEqualTo(91 * MIN);
        assertThat(result.newFilmIds()).hasSize(1);
        Film split = films.findById(result.newFilmIds().get(0)).orElseThrow();
        assertThat(split.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(split.getTitle()).isEqualTo("Rocky V");
        assertThat(split.getYear()).isEqualTo(1990);
        assertThat(split.getMatchCandidates()).isEqualTo("[]");
        assertThat(split.isEditionsConfirmed()).isFalse();
        assertThat(split.getEditions()).singleElement().satisfies(edition -> {
            assertThat(edition.getName()).isEqualTo("Original");
            assertThat(edition.getGroupingSource()).isEqualTo(GroupingSource.OWNER);
            assertThat(edition.getRuntimeMs()).isEqualTo(104 * MIN);
            assertThat(edition.originalParts()).singleElement().satisfies(file -> {
                assertThat(file.getId()).as("the file keeps its id").isEqualTo(rockyV);
                assertThat(file.getPartNumber()).isEqualTo(1);
            });
            assertThat(states.findById(edition.getId())).isEmpty();
        });
        assertThat(admin.attention().items()).filteredOn(i -> i.kind().equals("needs-match"))
            .extracting(AttentionItem::filmId).containsExactly(split.getId());
        assertThat(groupingChanges.findTop20ByOrderByHappenedAtDescIdDesc()).singleElement().satisfies(change -> {
            assertThat(change.getKind()).isEqualTo(GroupingChange.SPLIT);
            assertThat(change.getSummary()).isEqualTo(
                "You moved 1 file out of \"Rocky IV\" and arranged it into 1 edition with Edit editions");
        });
    }

    @Test
    void aNewEditionStartsFreshAndTheEditionItReplacesIsDeleted() {
        Film film = towers();
        Long first = editionId(film, 0);
        Long second = editionId(film, 1);
        saveState(state(film.getEditions().get(0), 20 * MIN, false, NOW, null));
        saveState(state(film.getEditions().get(1), 30 * MIN, false, NOW, null));

        arranger.save(film.getId(), new SaveRequest(List.of(
            ed(first, "Original", true, f(fileId(film, 0), null)),
            ed(null, "Second Copy", false, f(fileId(film, 1), null)),
            ed(editionId(film, 2), "Theatrical Cut", false, f(fileId(film, 2), null))), List.of()));

        Film saved = films.findById(film.getId()).orElseThrow();
        assertThat(saved.getEditions()).extracting(Edition::getName)
            .containsExactlyInAnyOrder("Original", "Second Copy", "Theatrical Cut");
        Edition created = saved.getEditions().stream().filter(e -> e.getName().equals("Second Copy")).findFirst()
            .orElseThrow();
        assertThat(created.getId()).isNotEqualTo(second);
        assertThat(created.getGroupingSource()).isEqualTo(GroupingSource.OWNER);
        assertThat(created.getRuntimeMs()).isEqualTo(121 * MIN);
        assertThat(states.findById(created.getId())).as("a new edition has no history").isEmpty();
        assertThat(editions.findById(second)).isEmpty();
        assertThat(states.findById(second)).isEmpty();
        assertThat(states.findById(first)).isPresent();
    }

    @Test
    void anEmptiedEditionTakesItsRenditionsAndTheRememberedChoicesFollowTheDefault() {
        Film built = film(null, "Heat", 1995, NOW);
        file(edition(built, null, "Original", 170 * MIN, null), "Heat - pt1.m4v");
        Edition b = edition(built, null, "Original 2", 170 * MIN, null);
        file(b, "Heat - pt2.m4v");
        MediaFile rendition = file(b, "Heat - pt2 - 720.m4v");
        rendition.setKind(MediaKind.PREMADE_720);
        Film film = save(built);
        Long first = editionId(film, 0);
        Long emptied = editionId(film, 1);
        MediaFile pt2 = film.getEditions().get(1).originalParts().get(0);
        Long streamInPart2 = pt2.getAudioStreams().get(0).getId();
        Long streamInPart1 = film.getEditions().get(0).originalParts().get(0).getAudioStreams().get(0).getId();
        FilmPreference preference = new FilmPreference(film.getId());
        preference.setDefaultEditionId(emptied);
        preference.setLastEditionId(emptied);
        preference.setAudioStreamId(streamInPart2);
        preferences.save(preference);
        saveState(state(film.getEditions().get(1), 5 * MIN, false, NOW, null));

        arranger.save(film.getId(), new SaveRequest(List.of(ed(first, "Heat", true,
            f(film.getEditions().get(0).originalParts().get(0).getId(), 1), f(pt2.getId(), 2))), List.of()));

        assertThat(editions.findById(emptied)).isEmpty();
        assertThat(states.findById(emptied)).isEmpty();
        FilmPreference after = preferences.findById(film.getId()).orElseThrow();
        assertThat(after.getDefaultEditionId()).isEqualTo(first);
        assertThat(after.getLastEditionId()).isEqualTo(first);
        assertThat(after.getAudioStreamId()).as("part 2's stream becomes part 1's").isEqualTo(streamInPart1);
    }

    @Test
    void aFilmWithoutRememberedChoicesGetsThemFromTheSave() {
        Film built = film(null, "Heat", 1995, NOW);
        file(edition(built, null, "Original", 170 * MIN, null), "Heat.m4v");
        Film film = save(built);
        assertThat(preferences.findById(film.getId())).isEmpty();

        arranger.save(film.getId(), new SaveRequest(List.of(ed(editionId(film, 0), "Original", true,
            f(fileId(film, 0), 1))), List.of()));

        FilmPreference preference = preferences.findById(film.getId()).orElseThrow();
        assertThat(preference.getDefaultEditionId()).isEqualTo(editionId(film, 0));
        assertThat(preference.isDefaultEditionChosen()).isTrue();
        assertThat(preference.getLastEditionId()).isNull();
    }

    @Test
    void aSaveDuringAScanIs409AndChangesNothing() {
        Film film = towers();
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> arranger.save(film.getId(), typical(film))).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");

        assertThat(lock.holder()).as("the scan keeps its lock").isEqualTo(LibraryLock.SCAN);
        assertThat(films.findById(film.getId()).orElseThrow().getEditions()).hasSize(3);
    }

    @Test
    void aBrokenArrangementIs400WithFieldErrorsAndChangesNothing() {
        Film film = towers();
        SaveRequest broken = new SaveRequest(List.of(
            ed(editionId(film, 0), "Extended Edition", false, f(fileId(film, 0), 1), f(fileId(film, 1), 3)),
            ed(editionId(film, 2), "Theatrical Cut", true, f(fileId(film, 2), null))), List.of());

        assertThatThrownBy(() -> arranger.save(film.getId(), broken)).isInstanceOfSatisfying(
            FieldErrorsException.class, e -> assertThat(e.errors()).containsExactly(
                new FieldError("editions[0].parts", "Parts 1 and 3 — part 2 is missing.")));

        assertThat(films.findById(film.getId()).orElseThrow().getEditions()).hasSize(3);
        assertThat(films.findById(film.getId()).orElseThrow().isEditionsConfirmed()).isFalse();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anEditionThatIsNotTheFilmsOrIsListedTwiceIs400() {
        Film film = towers();
        Long stranger = editionId(towers(), 0);
        SaveRequest foreign = new SaveRequest(List.of(
            ed(stranger, "Extended Edition", true, f(fileId(film, 0), 1), f(fileId(film, 1), 2)),
            ed(editionId(film, 2), "Theatrical Cut", false, f(fileId(film, 2), null))), List.of());
        SaveRequest twice = new SaveRequest(List.of(
            ed(editionId(film, 0), "A", true, f(fileId(film, 0), 1), f(fileId(film, 1), 2)),
            ed(editionId(film, 0), "B", false, f(fileId(film, 2), null))), List.of());

        assertThatThrownBy(() -> arranger.save(film.getId(), foreign)).isInstanceOf(BadRequestException.class)
            .hasMessage("Edition " + stranger + " is not an edition of this film");
        assertThatThrownBy(() -> arranger.save(film.getId(), twice)).isInstanceOf(BadRequestException.class)
            .hasMessage("Edition " + editionId(film, 0) + " is listed twice");
    }

    @Test
    void savingAnUnknownFilmIs404AndReleasesTheLock() {
        assertThatThrownBy(() -> arranger.save(9999L, new SaveRequest(List.of(), List.of())))
            .isInstanceOf(NotFoundException.class).hasMessage("No film 9999");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFileNameThatParsesToNoTitleStillGetsAFilm() {
        Film built = film(null, "Heat", 1995, NOW);
        Edition only = edition(built, null, "Original", 170 * MIN, null);
        file(only, "Heat.m4v");
        MediaFile odd = file(only, "[1995].m4v");
        odd.setPartNumber(2);
        Film film = save(built);
        List<MediaFile> parts = film.getEditions().get(0).originalParts();

        SaveResult result = arranger.save(film.getId(), new SaveRequest(
            List.of(ed(editionId(film, 0), "Original", true, f(parts.get(0).getId(), null))),
            List.of(parts.get(1).getId())));

        Film split = films.findById(result.newFilmIds().get(0)).orElseThrow();
        assertThat(split.getTitle()).isNotBlank();
        assertThat(split.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
    }
}
