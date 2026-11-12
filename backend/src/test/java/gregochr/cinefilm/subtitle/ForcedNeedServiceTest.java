package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.ForcedSubtitleDto;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.CountsDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The forced-need service: the three-query read, the counts, and the owner's choice under the library lock. */
@ExtendWith(MockitoExtension.class)
class ForcedNeedServiceTest {

    private static final Instant ASKED = NOW.minusSeconds(3600);

    @Mock
    private FilmRepository films;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private MediaFileRepository files;

    @Mock
    private ForcedSubtitleService forcedSubtitles;

    private final LibraryLock lock = new LibraryLock();
    private final List<String> events = new ArrayList<>();
    private ForcedNeedService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                events.add("begin, lock " + lock.holder());
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                events.add("commit, lock " + lock.holder());
            }

            @Override
            public void rollback(TransactionStatus status) {
                events.add("rollback, lock " + lock.holder());
            }
        };
        service = new ForcedNeedService(films, sidecars, files, forcedSubtitles, lock, manager);
    }

    private static NeedRow row(long id, String title, Long tmdbId, String original, String spoken, Instant asked,
                               ForcedNeedChoice owner) {
        return new NeedRow(id, title, 2000, tmdbId, original, spoken, asked, owner);
    }

    private static NeedRow english(long id, String title) {
        return row(id, title, id * 10, "en", "en", ASKED, null);
    }

    private static Film stored(long id, String original, String spoken, ForcedNeedChoice owner) {
        Film film = film(id, "Taken", 2008, NOW);
        film.setTmdbId(8681L);
        film.setOriginalLanguage(original);
        film.setSpokenLanguages(spoken);
        film.setLanguagesFetchedAt(ASKED);
        film.setForcedNeedOwner(owner);
        return film;
    }

    /** A film with one edition (id {@code filmId * 10}), as playback sees it. */
    private static Film withEdition(Film film) {
        edition(film, film.getId() * 10, "Original", 90 * MIN, null);
        return film;
    }

    /** What playback says it serves for the film's editions: its first edition has a subtitle, or nothing does. */
    private void playbackServes(Film film, boolean serves) {
        Map<Long, ForcedSubtitleDto> served = serves
            ? Map.of(film.getEditions().get(0).getId(), new ForcedSubtitleDto("SIDECAR", false, 1L)) : Map.of();
        when(forcedSubtitles.forEditions(film.getEditions())).thenReturn(served);
    }

    @Test
    void theListIsBuiltFromExactlyThreeQueriesInTheRepositoriesOrder() {
        when(films.findNeedRows()).thenReturn(List.of(english(1, "A"), english(2, "B"), english(3, "C")));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of(2L));
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of(3L));
        Film two = withEdition(stored(2L, "en", "en", null));
        when(films.findAllById(Set.of(2L))).thenReturn(List.of(two));
        playbackServes(two, true);

        NeedListDto list = service.list();

        assertThat(list.films()).extracting(FilmNeedDto::filmId).containsExactly(1L, 2L, 3L);
        assertThat(list.films()).extracting(FilmNeedDto::hasForcedSubtitle).containsExactly(false, true, false);
        assertThat(list.films()).extracting(FilmNeedDto::reason)
            .containsExactly("ENGLISH_ONLY", "SIDECAR", "EMBEDDED_FORCED");
        verify(films).findNeedRows();
        verify(sidecars).findFilmIdsWithPresentSidecar();
        verify(files).findFilmIdsWithEmbeddedForced();
        verify(films).findAllById(Set.of(2L));
        verifyNoMoreInteractions(films, sidecars, files);
    }

    @Test
    void aRowCarriesItsTitleYearOwnerChoiceLanguagesAndWhetherAFileIsAttached() {
        when(films.findNeedRows()).thenReturn(List.of(
            row(1, "Taken", 8681L, "en", "en,fr,sq", ASKED, ForcedNeedChoice.NOT_NEEDED)));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of(1L));
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());
        Film taken = withEdition(stored(1L, "en", "en", null));
        when(films.findAllById(Set.of(1L))).thenReturn(List.of(taken));
        playbackServes(taken, true);

        FilmNeedDto dto = service.list().films().get(0);

        assertThat(dto).isEqualTo(new FilmNeedDto(1L, "Taken", 2000, "NOT_NEEDED", "OWNER", "NOT_NEEDED", "en",
            List.of("en", "fr", "sq"), true));
    }

    @Test
    void aFilmWithNoAttachedFileAndNoOwnerChoiceHasANullChoiceAndNoSubtitle() {
        when(films.findNeedRows()).thenReturn(List.of(row(1, "Taken", 8681L, "en", "en,fr", ASKED, null)));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());

        FilmNeedDto dto = service.list().films().get(0);

        assertThat(dto.ownerChoice()).isNull();
        assertThat(dto.hasForcedSubtitle()).isFalse();
        assertThat(dto.need()).isEqualTo("UNSURE");
        assertThat(dto.reason()).isEqualTo("OTHER_LANGUAGES");
    }

    @Test
    void anEmbeddedTrackNeedsASubtitleButDoesNotCountAsAnAttachedFile() {
        when(films.findNeedRows()).thenReturn(List.of(english(1, "A")));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of(1L));

        FilmNeedDto dto = service.list().films().get(0);

        assertThat(dto.need()).isEqualTo("NEEDED");
        assertThat(dto.hasForcedSubtitle()).isFalse();
    }

    @Test
    void filmsTmdbHasNotBeenAskedAboutOrHasNoMatchForAreUnknown() {
        when(films.findNeedRows()).thenReturn(List.of(
            row(1, "Not asked", 10L, null, null, null, null),
            row(2, "No match", null, "en", "en", ASKED, null),
            row(3, "Gone from TMDB", 30L, null, null, ASKED, null)));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());

        List<FilmNeedDto> rows = service.list().films();

        assertThat(rows).extracting(FilmNeedDto::need).containsOnly("UNKNOWN");
        assertThat(rows).extracting(FilmNeedDto::reason).containsOnly("NO_LANGUAGES");
        assertThat(rows.get(2).spokenLanguages()).isEmpty();
        assertThat(rows.get(2).originalLanguage()).isNull();
    }

    @Test
    void whatTmdbDidGiveIsReadEvenWhenOnlyOneOfTheTwoFieldsIsThere() {
        when(films.findNeedRows()).thenReturn(List.of(
            row(1, "Only spoken", 10L, null, "en", ASKED, null),
            row(2, "Only original", 20L, "en", null, ASKED, null),
            row(3, "None listed", 30L, "en", "", ASKED, null),
            row(4, "Foreign", 40L, "fr", "fr", ASKED, null)));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());

        List<FilmNeedDto> rows = service.list().films();

        assertThat(rows).extracting(FilmNeedDto::reason).containsExactly("ENGLISH_ONLY", "NO_SPOKEN_LANGUAGES",
            "NO_SPOKEN_LANGUAGES", "FOREIGN_LANGUAGE_FILM");
        assertThat(rows).extracting(FilmNeedDto::need).containsExactly("NOT_NEEDED", "UNSURE", "UNSURE", "UNSURE");
    }

    @Test
    void theCountsSplitNeededIntoThoseWithAndWithoutAnAttachedFile() {
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of(1L));
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of(2L));
        Film hasFile = withEdition(stored(1L, "en", "en", null));
        when(films.findAllById(Set.of(1L))).thenReturn(List.of(hasFile));
        playbackServes(hasFile, true);
        when(films.findNeedRows()).thenReturn(List.of(
            english(1, "Has file"), english(2, "Embedded only"),
            row(3, "Chosen needed", 30L, "en", "en", ASKED, ForcedNeedChoice.NEEDED),
            row(4, "Unsure", 40L, "en", "en,fr", ASKED, null), row(5, "Unknown", 50L, null, null, null, null),
            row(6, "Unknown too", null, null, null, null, null),
            row(7, "Not needed", 70L, "en", "en", ASKED, null),
            row(8, "Chosen not needed", 80L, "en", "en,fr", ASKED, ForcedNeedChoice.NOT_NEEDED),
            row(9, "Foreign", 90L, "ja", "ja", ASKED, null)));

        CountsDto counts = service.list().counts();

        assertThat(counts).isEqualTo(new CountsDto(3, 2, 2, 2, 2));
    }

    @Test
    void filmsWithSidecarEvidenceThatPlaybackWouldNotServeStayNeededButCountAsMissing() {
        when(films.findNeedRows()).thenReturn(List.of(english(1, "Conflict"), english(2, "Fine")));
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of(1L, 2L));
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());
        Film conflict = withEdition(stored(1L, "en", "en", null));
        Film fine = withEdition(stored(2L, "en", "en", null));
        when(films.findAllById(Set.of(1L, 2L))).thenReturn(List.of(conflict, fine));
        when(forcedSubtitles.forEditions(List.of(conflict.getEditions().get(0), fine.getEditions().get(0))))
            .thenReturn(Map.of(fine.getEditions().get(0).getId(), new ForcedSubtitleDto("SIDECAR", false, 1L)));

        NeedListDto list = service.list();

        assertThat(list.films()).extracting(FilmNeedDto::need).containsExactly("NEEDED", "NEEDED");
        assertThat(list.films()).extracting(FilmNeedDto::reason).containsExactly("SIDECAR", "SIDECAR");
        assertThat(list.films()).extracting(FilmNeedDto::hasForcedSubtitle).containsExactly(false, true);
        assertThat(list.counts()).isEqualTo(new CountsDto(2, 1, 0, 0, 0));
    }

    @Test
    void theAnsweredRowOfAFilmWhosePlaybackServesNothingHasNoForcedSubtitleButStillSaysSidecar() {
        Film taken = withEdition(stored(7L, "en", "en", null));
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(taken));
        when(sidecars.countPresentForFilm(7L)).thenReturn(2L);
        when(files.countEmbeddedForcedForFilm(7L)).thenReturn(0L);
        playbackServes(taken, false);

        FilmNeedDto dto = service.choose(7L, new ChoiceRequest(null));

        assertThat(dto.need()).isEqualTo("NEEDED");
        assertThat(dto.reason()).isEqualTo("SIDECAR");
        assertThat(dto.hasForcedSubtitle()).isFalse();
    }

    @Test
    void anEmptyLibraryHasEmptyCounts() {
        when(films.findNeedRows()).thenReturn(List.of());
        when(sidecars.findFilmIdsWithPresentSidecar()).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());

        NeedListDto list = service.list();

        assertThat(list.films()).isEmpty();
        assertThat(list.counts()).isEqualTo(new CountsDto(0, 0, 0, 0, 0));
    }

    @Test
    void choosingStoresTheChoiceAndAnswersTheFilmsRowWithTheLockHeldUntilTheCommit() {
        Film taken = withEdition(stored(7L, "en", "en,fr", null));
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(taken));
        when(sidecars.countPresentForFilm(7L)).thenReturn(1L);
        playbackServes(taken, true);
        when(files.countEmbeddedForcedForFilm(7L)).thenReturn(0L);

        FilmNeedDto dto = service.choose(7L, new ChoiceRequest("NOT_NEEDED"));

        assertThat(taken.getForcedNeedOwner()).isEqualTo(ForcedNeedChoice.NOT_NEEDED);
        assertThat(dto).isEqualTo(new FilmNeedDto(7L, "Taken", 2008, "NOT_NEEDED", "OWNER", "NOT_NEEDED", "en",
            List.of("en", "fr"), true));
        assertThat(events).containsExactly("begin, lock subtitle", "commit, lock subtitle");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void clearingTheChoiceGivesTheAutomaticAnswerBack() {
        Film taken = stored(7L, "en", "en", ForcedNeedChoice.NEEDED);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(taken));
        when(sidecars.countPresentForFilm(7L)).thenReturn(0L);
        when(files.countEmbeddedForcedForFilm(7L)).thenReturn(0L);

        FilmNeedDto dto = service.choose(7L, new ChoiceRequest(null));

        assertThat(taken.getForcedNeedOwner()).isNull();
        assertThat(dto.ownerChoice()).isNull();
        assertThat(dto.need()).isEqualTo("NOT_NEEDED");
        assertThat(dto.reason()).isEqualTo("ENGLISH_ONLY");
        assertThat(dto.hasForcedSubtitle()).isFalse();
    }

    @Test
    void anEmbeddedForcedTrackShowsInTheRowOfTheChosenFilmToo() {
        Film taken = stored(7L, "en", "en", null);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(taken));
        when(sidecars.countPresentForFilm(7L)).thenReturn(0L);
        when(files.countEmbeddedForcedForFilm(7L)).thenReturn(2L);

        FilmNeedDto dto = service.choose(7L, null);

        assertThat(dto.need()).isEqualTo("NEEDED");
        assertThat(dto.reason()).isEqualTo("EMBEDDED_FORCED");
    }

    @Test
    void aBusyLibraryLockIsAConflictAndTheHoldersLockIsNotTakenOverOrReleased() {
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> service.choose(7L, new ChoiceRequest("NEEDED")))
            .isInstanceOf(ConflictException.class).hasMessage("A scan is running");

        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        assertThat(events).isEmpty();
        verifyNoInteractions(films, sidecars, files);
    }

    @Test
    void anUnknownFilmIs404AndTheLockIsFreedAfterTheRollback() {
        when(films.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.choose(9L, new ChoiceRequest("NEEDED")))
            .isInstanceOf(NotFoundException.class).hasMessage("No such film");

        assertThat(events).containsExactly("begin, lock subtitle", "rollback, lock subtitle");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anyOtherValueIs400BeforeTheLockIsTaken() {
        for (String bad : List.of("MAYBE", "needed", "", "UNSURE")) {
            assertThatThrownBy(() -> service.choose(7L, new ChoiceRequest(bad)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("The choice must be NEEDED, NOT_NEEDED or null");
        }

        assertThat(lock.holder()).isNull();
        assertThat(events).isEmpty();
        verifyNoInteractions(films);
    }
}
