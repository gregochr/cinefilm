package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.FullNeedRow;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.AudioRow;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.FullNeedDtos.EditionAudioDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The full-need service: which films are in scope, the audio each edition shows, and the owner's choice. */
@ExtendWith(MockitoExtension.class)
class FullNeedServiceTest {

    private static final Instant ASKED = NOW.minusSeconds(3600);

    @Mock
    private FilmRepository films;

    @Mock
    private MediaFileRepository files;

    private final LibraryLock lock = new LibraryLock();
    private final List<String> events = new ArrayList<>();
    private FullNeedService service;

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
        service = new FullNeedService(films, files, lock, manager);
    }

    private static FullNeedRow row(long id, String title, String original, FullNeedChoice owner) {
        return new FullNeedRow(id, title, 2000, id * 10, original, original, ASKED, owner);
    }

    /** One audio track of one file of an edition. */
    private static AudioRow track(long film, long edition, String name, long file, int part, Presence presence,
                                  String language, boolean isDefault) {
        return new AudioRow(film, edition, name, file, part, presence, language, isDefault);
    }

    /** A file with no audio track at all (the left join's single row). */
    private static AudioRow silent(long film, long edition, String name, long file, int part) {
        return new AudioRow(film, edition, name, file, part, Presence.PRESENT, null, null);
    }

    private static Film stored(long id, String original, FullNeedChoice owner) {
        Film film = film(id, "Amelie", 2001, NOW);
        film.setTmdbId(id * 10);
        film.setOriginalLanguage(original);
        film.setSpokenLanguages(original);
        film.setLanguagesFetchedAt(ASKED);
        film.setFullNeedOwner(owner);
        return film;
    }

    @Test
    void onlyFilmsNotMadeInEnglishOrWithAnOwnerChoiceAreListedAndTheirAudioIsReadInOneQuery() {
        when(films.findFullNeedRows()).thenReturn(List.of(
            row(1, "Amelie", "fr", null),
            row(2, "Heat", "en", null),
            row(3, "Metropolis", "xx", null),
            row(4, "Notes", null, null),
            row(5, "Taken", "en", FullNeedChoice.NEEDED)));
        when(files.findAudioRows(List.of(1L, 5L))).thenReturn(List.of(
            track(1, 10, "Original", 100, 1, Presence.PRESENT, "French", true),
            track(5, 50, "Original", 500, 1, Presence.PRESENT, "English", true)));

        FullNeedListDto list = service.list();

        assertThat(list.films()).extracting(FullNeedFilmDto::filmId).containsExactly(1L, 5L);
        assertThat(list.films().get(0)).isEqualTo(new FullNeedFilmDto(1L, "Amelie", 2000, "NEEDED", "FOREIGN_AUDIO",
            null, List.of(new EditionAudioDto("Original", "French"))));
        assertThat(list.films().get(1).reason()).isEqualTo("OWNER");
        assertThat(list.films().get(1).ownerChoice()).isEqualTo("NEEDED");
        assertThat(list.counts().needed()).isEqualTo(2);
    }

    @Test
    void anEmptyScopeAsksForNoAudio() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(2, "Heat", "en", null)));

        FullNeedListDto list = service.list();

        assertThat(list.films()).isEmpty();
        assertThat(list.counts().needed()).isZero();
        verifyNoInteractions(files);
    }

    @Test
    void anEditionsAudioIsPartOnesDefaultTrackElseItsFirstTrack() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "A", "fr", null), row(2, "B", "fr", null),
            row(3, "C", "fr", null)));
        when(files.findAudioRows(List.of(1L, 2L, 3L))).thenReturn(List.of(
            // A: the default track is second; a later part's audio is ignored.
            track(1, 10, "Original", 100, 1, Presence.PRESENT, "English", false),
            track(1, 10, "Original", 100, 1, Presence.PRESENT, "French", true),
            track(1, 10, "Original", 101, 2, Presence.PRESENT, "German", true),
            // B: no default track, so the first track.
            track(2, 20, "Original", 200, 1, Presence.PRESENT, "English", false),
            track(2, 20, "Original", 200, 1, Presence.PRESENT, "French", false),
            // C: no audio at all.
            silent(3, 30, "Original", 300, 1)));

        List<FullNeedFilmDto> films = service.list().films();

        assertThat(films.get(0).editions()).containsExactly(new EditionAudioDto("Original", "French"));
        assertThat(films.get(0).need()).isEqualTo("NEEDED");
        assertThat(films.get(1).editions()).containsExactly(new EditionAudioDto("Original", "English"));
        assertThat(films.get(1).need()).isEqualTo("NOT_NEEDED");
        assertThat(films.get(1).reason()).isEqualTo("ENGLISH_AUDIO");
        assertThat(films.get(2).editions()).containsExactly(new EditionAudioDto("Original", null));
        assertThat(films.get(2).need()).isEqualTo("UNSURE");
    }

    @Test
    void onlyEditionsWhosePartOneIsPresentCountAndAnyForeignEditionMakesTheFilmNeeded() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "A", "fr", null), row(2, "B", "fr", null)));
        when(files.findAudioRows(List.of(1L, 2L))).thenReturn(List.of(
            track(1, 10, "Theatrical", 100, 1, Presence.PRESENT, "English", true),
            track(1, 11, "Director's Cut", 110, 1, Presence.PRESENT, "French", true),
            // B's only edition has part 1 missing (part 2 is present, but part 1 decides).
            track(2, 20, "Original", 200, 1, Presence.MISSING, "French", true),
            track(2, 20, "Original", 201, 2, Presence.PRESENT, "French", true)));

        List<FullNeedFilmDto> films = service.list().films();

        assertThat(films.get(0).editions()).extracting(EditionAudioDto::name)
            .containsExactly("Theatrical", "Director's Cut");
        assertThat(films.get(0).need()).isEqualTo("NEEDED");
        assertThat(films.get(1).editions()).isEmpty();
        assertThat(films.get(1).need()).isEqualTo("UNSURE");
    }

    @Test
    void aFilmTmdbHasNotBeenAskedAboutIsUnknownWhenTheOwnerChoseButAudioCannotHelp() {
        FullNeedRow unasked = new FullNeedRow(1L, "Unasked", null, null, null, null, null, FullNeedChoice.NOT_NEEDED);
        when(films.findFullNeedRows()).thenReturn(List.of(unasked));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());

        FullNeedFilmDto dto = service.list().films().get(0);

        assertThat(dto.need()).isEqualTo("NOT_NEEDED");
        assertThat(dto.reason()).isEqualTo("OWNER");
        assertThat(dto.editions()).isEmpty();
    }

    @Test
    void aFilmWithALanguageButNoTmdbIdIsUnknownAndCounted() {
        when(films.findFullNeedRows()).thenReturn(List.of(
            new FullNeedRow(1L, "Rematched", 2000, null, "fr", "fr", ASKED, null)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());

        FullNeedListDto list = service.list();

        assertThat(list.films().get(0).need()).isEqualTo("UNKNOWN");
        assertThat(list.films().get(0).reason()).isEqualTo("NO_LANGUAGES");
        assertThat(list.counts().unknown()).isEqualTo(1);
    }

    @Test
    void choosingStoresTheChoiceAndAnswersTheRowWithTheLockHeldUntilTheCommit() {
        Film amelie = stored(7L, "fr", null);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of(
            track(7, 70, "Original", 700, 1, Presence.PRESENT, "French", true)));

        FullNeedFilmDto dto = service.choose(7L, new ChoiceRequest("NOT_NEEDED"));

        assertThat(amelie.getFullNeedOwner()).isEqualTo(FullNeedChoice.NOT_NEEDED);
        assertThat(dto).isEqualTo(new FullNeedFilmDto(7L, "Amelie", 2001, "NOT_NEEDED", "OWNER", "NOT_NEEDED",
            List.of(new EditionAudioDto("Original", "French"))));
        assertThat(events).containsExactly("begin, lock subtitle", "commit, lock subtitle");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void clearingTheChoiceGivesTheAutomaticAnswerBack() {
        Film amelie = stored(7L, "fr", FullNeedChoice.NOT_NEEDED);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of(
            track(7, 70, "Original", 700, 1, Presence.PRESENT, "French", true)));

        FullNeedFilmDto dto = service.choose(7L, new ChoiceRequest(null));

        assertThat(amelie.getFullNeedOwner()).isNull();
        assertThat(dto.ownerChoice()).isNull();
        assertThat(dto.need()).isEqualTo("NEEDED");
        assertThat(dto.reason()).isEqualTo("FOREIGN_AUDIO");
    }

    @Test
    void aMissingBodyClearsTheChoiceToo() {
        Film amelie = stored(7L, "fr", FullNeedChoice.NEEDED);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of());

        service.choose(7L, null);

        assertThat(amelie.getFullNeedOwner()).isNull();
    }

    @Test
    void aBusyLibraryLockIsAConflictAndTheHoldersLockIsNotTakenOverOrReleased() {
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> service.choose(7L, new ChoiceRequest("NEEDED")))
            .isInstanceOf(ConflictException.class).hasMessage("A scan is running");

        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        assertThat(events).isEmpty();
        verifyNoInteractions(films, files);
    }

    @Test
    void anUnknownFilmIs404AndTheLockIsFreedAfterTheRollback() {
        when(films.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.choose(9L, new ChoiceRequest("NEEDED")))
            .isInstanceOf(NotFoundException.class).hasMessage("No such film");

        assertThat(lock.holder()).isNull();
    }

    @Test
    void anyOtherChoiceIs400BeforeTheLockIsTaken() {
        assertThatThrownBy(() -> service.choose(7L, new ChoiceRequest("MAYBE")))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("The choice must be NEEDED, NOT_NEEDED or null");

        assertThat(lock.holder()).isNull();
        verifyNoInteractions(films, files);
    }

    @Test
    void theNeedOfOneFilmReadsItsAudioWithOneQuery() {
        Film amelie = stored(7L, "fr", null);
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of(
            track(7, 70, "Original", 700, 1, Presence.PRESENT, "French", true)));

        assertThat(service.needOf(amelie)).isEqualTo("NEEDED");
        verify(files).findAudioRows(List.of(7L));
    }

    @Test
    void aFilmTmdbAnsweredWithNoOriginalLanguageIsEnglishWhateverItsSpokenLanguagesAndUnknownWithNone() {
        Film silent = stored(10L, null, null);
        silent.setSpokenLanguages("fr");
        Film nothing = stored(11L, null, null);
        nothing.setSpokenLanguages(null);

        assertThat(service.needOf(silent)).isEqualTo("NOT_NEEDED");
        assertThat(service.needOf(nothing)).isEqualTo("UNKNOWN");
    }

    @Test
    void theNeedOfAnEnglishFilmNeedsNoAudioAndAnUnaskedFilmIsUnknown() {
        Film heat = stored(8L, "en", null);
        Film unasked = film(9L, "Unasked", 2000, NOW);

        assertThat(service.needOf(heat)).isEqualTo("NOT_NEEDED");
        assertThat(service.needOf(unasked)).isEqualTo("UNKNOWN");
        verifyNoInteractions(files);
    }

    @Test
    void theNeededFilmIdsAreOnlyThoseWhoseNeedIsNeeded() {
        when(films.findFullNeedRows()).thenReturn(List.of(
            row(1, "Amelie", "fr", null),
            row(2, "Heat", "en", null),
            row(3, "Taken", "en", FullNeedChoice.NOT_NEEDED),
            row(4, "Oldboy", "ko", null),
            row(5, "Dubbed", "fr", null)));
        when(files.findAudioRows(List.of(1L, 3L, 4L, 5L))).thenReturn(List.of(
            track(1, 10, "Original", 100, 1, Presence.PRESENT, "French", true),
            track(4, 40, "Original", 400, 1, Presence.PRESENT, "Korean", true),
            track(5, 50, "Original", 500, 1, Presence.PRESENT, "English", true)));

        assertThat(service.neededFilmIds()).containsExactlyInAnyOrder(1L, 4L);
    }

    @Test
    void aFilmIsNeededWhenItsNeedIsNeededAndNeverWhenItIsNotOrHasGone() {
        Film amelie = stored(7L, "fr", null);
        Film heat = stored(8L, "en", null);
        when(films.findById(7L)).thenReturn(Optional.of(amelie));
        when(films.findById(8L)).thenReturn(Optional.of(heat));
        when(films.findById(9L)).thenReturn(Optional.empty());
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of(
            track(7, 70, "Original", 700, 1, Presence.PRESENT, "French", true)));

        assertThat(service.isNeeded(7L)).isTrue();
        assertThat(service.isNeeded(8L)).isFalse();
        assertThat(service.isNeeded(9L)).isFalse();
    }
}
