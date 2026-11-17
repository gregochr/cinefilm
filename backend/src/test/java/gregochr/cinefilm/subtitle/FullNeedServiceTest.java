package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.FullSubtitleDto;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.FullNeedRow;
import gregochr.cinefilm.film.FilmRepository.OsFullState;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.AudioRow;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.FullNeedDtos.EditionAudioDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullSubtitleStateDto;
import gregochr.cinefilm.subtitle.PendingDownloads.Pending;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Mock
    private EditionRepository editions;

    @Mock
    private FullSubtitleService fullSubtitles;

    @Mock
    private PendingDownloads pending;

    private final LibraryLock lock = new LibraryLock();
    private final List<String> events = new ArrayList<>();
    private FullNeedService service;
    private PlatformTransactionManager manager;

    @BeforeEach
    void setUp() {
        manager = new PlatformTransactionManager() {
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
        service = withSwitches(OpenSubtitlesSwitches.on());
    }

    private FullNeedService withSwitches(OpenSubtitlesSwitches switches) {
        return new FullNeedService(films, files, editions, fullSubtitles, pending, switches, lock, manager);
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
            null, List.of(new EditionAudioDto("Original", "French")), null, "fr", true, false, false, 10L, null));
        assertThat(list.films().get(1).reason()).isEqualTo("OWNER");
        assertThat(list.films().get(1).ownerChoice()).isEqualTo("NEEDED");
        assertThat(list.counts().needed()).isEqualTo(2);
    }

    @Test
    void eachFilmSaysWhatItPlaysAsFullSubtitlesFromOneFetchOfTheirEditions() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "Owner", "fr", null), row(2, "Download", "fr", null),
            row(3, "Both", "fr", null), row(4, "None", "fr", null)));
        when(files.findAudioRows(List.of(1L, 2L, 3L, 4L))).thenReturn(List.of());
        Edition mine = edition(film(1L, "Owner", 2000, NOW), 10L, "Original", 100, null);
        Edition fetched = edition(film(2L, "Download", 2000, NOW), 20L, "Original", 100, null);
        Edition cut = edition(film(3L, "Both", 2000, NOW), 30L, "Cut", 100, null);
        Edition theatrical = edition(film(3L, "Both", 2000, NOW), 31L, "Theatrical", 100, null);
        Edition silent = edition(film(4L, "None", 2000, NOW), 40L, "Original", 100, null);
        List<Edition> all = List.of(mine, fetched, cut, theatrical, silent);
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L, 3L, 4L))).thenReturn(all);
        when(fullSubtitles.forEditions(all)).thenReturn(Map.of(
            10L, new FullSubtitleDto("SIDECAR", 1, List.of(), List.of()),
            20L, new FullSubtitleDto("DOWNLOAD", 2, List.of(), List.of()),
            30L, new FullSubtitleDto("DOWNLOAD", 3, List.of(), List.of()),
            31L, new FullSubtitleDto("SIDECAR", 4, List.of(), List.of())));

        List<FullNeedFilmDto> listed = service.list().films();

        assertThat(listed).extracting(FullNeedFilmDto::fullSubtitle).containsExactly(
            new FullSubtitleStateDto("SIDECAR"), new FullSubtitleStateDto("DOWNLOAD"),
            new FullSubtitleStateDto("SIDECAR"), null);
        verify(editions).findWithFilesByFilmIdIn(List.of(1L, 2L, 3L, 4L));
    }

    @Test
    void choosingAnswersWithWhatTheFilmPlays() {
        Film amelie = stored(7L, "fr", null);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of());
        Edition original = edition(amelie, 70L, "Original", 100, null);
        when(editions.findWithFilesByFilmIdIn(List.of(7L))).thenReturn(List.of(original));
        when(fullSubtitles.forEditions(List.of(original)))
            .thenReturn(Map.of(70L, new FullSubtitleDto("DOWNLOAD", 1, List.of(), List.of())));

        FullNeedFilmDto answer = service.choose(7L, new ChoiceRequest("NEEDED"));

        assertThat(answer.fullSubtitle()).isEqualTo(new FullSubtitleStateDto("DOWNLOAD"));
        assertThat(answer.subtitled()).isTrue();
    }

    @Test
    void choosingAnswersNotSubtitledWhenTheFilmDoesNotShowInBrowseEvenThoughASubtitlePlays() {
        Film amelie = stored(7L, "fr", null);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of());
        Edition original = edition(amelie, 70L, "Original", 100, null);
        file(original, "amelie.mp4").setPresence(Presence.MISSING);
        when(editions.findWithFilesByFilmIdIn(List.of(7L))).thenReturn(List.of(original));
        when(fullSubtitles.forEditions(List.of(original)))
            .thenReturn(Map.of(70L, new FullSubtitleDto("DOWNLOAD", 1, List.of(), List.of())));

        FullNeedFilmDto answer = service.choose(7L, new ChoiceRequest("NEEDED"));

        assertThat(answer.fullSubtitle()).isEqualTo(new FullSubtitleStateDto("DOWNLOAD"));
        assertThat(answer.subtitled()).isFalse();
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
        assertThat(films.get(2).need()).isEqualTo("NEEDED");
        assertThat(films.get(2).reason()).isEqualTo("ORIGINAL_AUDIO_ASSUMED");
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
        assertThat(films.get(1).need()).isEqualTo("NEEDED");
        assertThat(films.get(1).reason()).isEqualTo("ORIGINAL_AUDIO_ASSUMED");
        // The picker searches the French cut (what makes the film needed), not the English one that comes first.
        assertThat(films).extracting(FullNeedFilmDto::editionId).containsExactly(11L, null);
        assertThat(films).extracting(FullNeedFilmDto::editionName).containsExactly("Director's Cut", null);
    }

    @Test
    void thePickerEditionIsTheFirstForeignOneElseTheFirstUnlabelledElseTheFirst() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "A", "fr", null), row(2, "B", "fr", null),
            row(3, "C", "fr", null), row(4, "D", "fr", null)));
        when(files.findAudioRows(List.of(1L, 2L, 3L, 4L))).thenReturn(List.of(
            // A: English, unlabelled, French: the French one.
            track(1, 10, "Theatrical", 100, 1, Presence.PRESENT, "English", true),
            silent(1, 11, "Extended", 110, 1),
            track(1, 12, "Cut", 120, 1, Presence.PRESENT, "French", true),
            // B: English, then unlabelled: the unlabelled one.
            track(2, 20, "Theatrical", 200, 1, Presence.PRESENT, "eng", true),
            silent(2, 21, "Extended", 210, 1),
            // C: every edition English: the first.
            track(3, 30, "Theatrical", 300, 1, Presence.PRESENT, "English", true),
            track(3, 31, "Extended", 310, 1, Presence.PRESENT, "en", true),
            // D: one edition: its name is not needed to tell cuts apart.
            track(4, 40, "Original", 400, 1, Presence.PRESENT, "French", true)));

        List<FullNeedFilmDto> films = service.list().films();

        assertThat(films).extracting(FullNeedFilmDto::editionId).containsExactly(12L, 21L, 30L, 40L);
        assertThat(films).extracting(FullNeedFilmDto::editionName)
            .containsExactly("Cut", "Extended", "Theatrical", null);
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
            List.of(new EditionAudioDto("Original", "French")), null, "fr", false, false, false, 70L, null));
        assertThat(events).containsExactly("begin, lock subtitle", "commit, lock subtitle");
        assertThat(lock.holder()).isNull();
    }

    @Test
    void choosingNoneExistStoresItAndLeavesTheRowDoneNotUnresolvedNorBeingChecked() {
        Film amelie = stored(7L, "fr", null);
        amelie.setImdbId("tt7");
        amelie.setOsFullSearchedImdbId("tt7");
        amelie.setOsFullCandidates("[]");
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of(
            track(7, 70, "Original", 700, 1, Presence.PRESENT, "French", true)));

        FullNeedFilmDto dto = service.choose(7L, new ChoiceRequest("NONE_EXIST"));

        assertThat(amelie.getFullNeedOwner()).isEqualTo(FullNeedChoice.NONE_EXIST);
        assertThat(dto.need()).isEqualTo("NEEDED");
        assertThat(dto.reason()).isEqualTo("NONE_EXIST");
        assertThat(dto.ownerChoice()).isEqualTo("NONE_EXIST");
        assertThat(dto.unresolved()).isFalse();
        assertThat(dto.beingChecked()).isFalse();
        assertThat(dto.subtitled()).isFalse();
        verifyNoInteractions(pending);
    }

    @Test
    void noneExistFilmsAreStillNeededButNeverSearchedOrDownloadedAutomatically() {
        when(films.findFullNeedRows()).thenReturn(List.of(
            row(1, "Amelie", "fr", null),
            row(2, "Lantern", "zh", FullNeedChoice.NONE_EXIST)));
        when(files.findAudioRows(List.of(1L, 2L))).thenReturn(List.of(
            track(1, 10, "Original", 100, 1, Presence.PRESENT, "French", true)));
        Film lantern = stored(2L, "zh", FullNeedChoice.NONE_EXIST);
        when(films.findById(2L)).thenReturn(Optional.of(lantern));

        assertThat(service.neededFilmIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(service.searchFilmIds()).containsExactly(1L);
        assertThat(service.isNeeded(2L)).isFalse();
        assertThat(service.needOf(lantern)).isEqualTo("NEEDED");
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
    void aSubtitleChangeHoldingTheLockIsNotCalledAScan() {
        lock.tryAcquire(LibraryLock.SUBTITLE);

        assertThatThrownBy(() -> service.choose(7L, new ChoiceRequest("NEEDED")))
            .isInstanceOf(ConflictException.class)
            .hasMessage("Subtitles are being changed or checked; try again in a moment");

        assertThat(lock.holder()).isEqualTo(LibraryLock.SUBTITLE);
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
            .hasMessage("The choice must be NEEDED, NOT_NEEDED, NONE_EXIST or null");

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
        // The scheduler asks for the need only: nothing about the pipeline's facts or what the films play.
        verifyNoInteractions(pending, editions, fullSubtitles);
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

    /** Six in-scope films in every state the rule tells apart; ids 1..6, search states as in the comments. */
    private void sixFilmsInEveryState() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "NoImdb", "fr", null),
            row(2, "NotSearched", "fr", null), row(3, "Coming", "fr", null), row(4, "Stuck", "fr", null),
            row(5, "Plays", "fr", null), row(6, "Heat", "en", FullNeedChoice.NOT_NEEDED)));
        when(files.findAudioRows(List.of(1L, 2L, 3L, 4L, 5L, 6L))).thenReturn(List.of());
        when(films.findOpenSubtitlesFullStates()).thenReturn(List.of(
            new OsFullState(2L, "tt2", null, false), new OsFullState(3L, "tt3", "tt3", true),
            new OsFullState(4L, "tt4", "tt4", true), new OsFullState(5L, "tt5", "tt5", true),
            new OsFullState(6L, "tt6", "tt6", true)));
        Edition playing = edition(film(5L, "Plays", 2000, NOW), 50L, "Original", 100, null);
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L, 3L, 4L, 5L, 6L))).thenReturn(List.of(playing));
        when(fullSubtitles.forEditions(List.of(playing)))
            .thenReturn(Map.of(50L, new FullSubtitleDto("DOWNLOAD", 1, List.of(), List.of())));
    }

    @Test
    void theFlagsAndCountsComeFromTheRuleForEveryState() {
        sixFilmsInEveryState();
        when(pending.full(Map.of(3L, "tt3", 4L, "tt4")))
            .thenReturn(new Pending(java.util.Set.of(3L)));

        FullNeedListDto list = service.list();

        // 1: no IMDb id, unresolved. 2: not searched yet, being checked. 3: a candidate and an edition due, being
        // checked. 4: searched, nothing to try, unresolved. 5: a subtitle plays. 6: not needed.
        assertThat(list.films()).extracting(FullNeedFilmDto::unresolved)
            .containsExactly(true, false, false, true, false, false);
        assertThat(list.films()).extracting(FullNeedFilmDto::beingChecked)
            .containsExactly(false, true, true, false, false, false);
        assertThat(list.counts().unresolved()).isEqualTo(2);
        assertThat(list.counts().beingChecked()).isEqualTo(2);
        verify(pending).full(Map.of(3L, "tt3", 4L, "tt4"));
    }

    @Test
    void aSearchedFilmTheSchedulerWouldNotDownloadForIsUnresolved() {
        sixFilmsInEveryState();
        when(pending.full(Map.of(3L, "tt3", 4L, "tt4")))
            .thenReturn(new Pending(java.util.Set.of()));

        FullNeedListDto list = service.list();

        assertThat(list.films().get(2).unresolved()).isTrue();
        assertThat(list.films().get(2).beingChecked()).isFalse();
        assertThat(list.films().get(3).unresolved()).isTrue();
        assertThat(list.films().get(3).beingChecked()).isFalse();
    }

    @Test
    void unresolvedOrBeingCheckedIsExactlyNeededWithNoFullSubtitle() {
        sixFilmsInEveryState();
        when(pending.full(Map.of(3L, "tt3", 4L, "tt4")))
            .thenReturn(new Pending(java.util.Set.of(3L)));

        List<FullNeedFilmDto> listed = service.list().films();

        assertThat(listed.stream().filter(f -> f.unresolved() || f.beingChecked()).map(FullNeedFilmDto::filmId))
            .containsExactlyElementsOf(listed.stream()
                .filter(f -> "NEEDED".equals(f.need()) && f.fullSubtitle() == null)
                .map(FullNeedFilmDto::filmId).toList());
        assertThat(listed.stream().filter(f -> f.unresolved() && f.beingChecked())).isEmpty();
    }

    @Test
    void theOriginalLanguageIsTmdbsCode() {
        sixFilmsInEveryState();
        when(pending.full(Map.of(3L, "tt3", 4L, "tt4"))).thenReturn(Pending.NONE);

        assertThat(service.list().films()).extracting(FullNeedFilmDto::originalLanguage)
            .containsExactly("fr", "fr", "fr", "fr", "fr", "en");
    }

    @Test
    void choosingAnswersTheFlagsToo() {
        Film amelie = stored(7L, "fr", null);
        amelie.setImdbId("tt7");
        amelie.setOsFullSearchedImdbId("tt7");
        amelie.setOsFullCandidates("[]");
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(amelie));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of());
        when(pending.full(Map.of(7L, "tt7"))).thenReturn(new Pending(java.util.Set.of(7L)));

        FullNeedFilmDto dto = service.choose(7L, new ChoiceRequest("NEEDED"));

        assertThat(dto.beingChecked()).isTrue();
        assertThat(dto.unresolved()).isFalse();
        assertThat(dto.originalLanguage()).isEqualTo("fr");
    }

    @Test
    void choosingAFilmNotYetSearchedIsBeingCheckedAndOneWithoutAnImdbIdIsUnresolved() {
        Film searchable = stored(7L, "fr", null);
        searchable.setImdbId("tt7");
        Film bare = stored(8L, "fr", null);
        when(films.findByIdForUpdate(7L)).thenReturn(Optional.of(searchable));
        when(films.findByIdForUpdate(8L)).thenReturn(Optional.of(bare));
        when(files.findAudioRows(List.of(7L))).thenReturn(List.of());
        when(files.findAudioRows(List.of(8L))).thenReturn(List.of());

        FullNeedFilmDto notSearched = service.choose(7L, new ChoiceRequest("NEEDED"));
        FullNeedFilmDto noImdb = service.choose(8L, new ChoiceRequest("NEEDED"));

        assertThat(notSearched.beingChecked()).isTrue();
        assertThat(notSearched.unresolved()).isFalse();
        assertThat(noImdb.beingChecked()).isFalse();
        assertThat(noImdb.unresolved()).isTrue();
    }

    @Test
    void withSearchOffANeverSearchedFilmIsUnresolvedNotBeingChecked() {
        sixFilmsInEveryState();
        when(pending.full(Map.of(3L, "tt3", 4L, "tt4"))).thenReturn(new Pending(java.util.Set.of(3L)));
        service = withSwitches(new OpenSubtitlesSwitches(false, true));

        FullNeedListDto list = service.list();

        // Film 2 (never searched) is now unresolved; film 3's due download still counts while downloads are on.
        assertThat(list.films()).extracting(FullNeedFilmDto::unresolved)
            .containsExactly(true, true, false, true, false, false);
        assertThat(list.films()).extracting(FullNeedFilmDto::beingChecked)
            .containsExactly(false, false, true, false, false, false);
        assertThat(list.counts().unresolved()).isEqualTo(3);
        assertThat(list.counts().beingChecked()).isEqualTo(1);
    }

    @Test
    void withDownloadsOffADueDownloadIsIgnoredAndANeverSearchedFilmIsStillBeingChecked() {
        sixFilmsInEveryState();
        when(pending.full(Map.of(3L, "tt3", 4L, "tt4"))).thenReturn(new Pending(java.util.Set.of(3L)));
        service = withSwitches(new OpenSubtitlesSwitches(true, false));

        FullNeedListDto list = service.list();

        assertThat(list.films()).extracting(FullNeedFilmDto::unresolved)
            .containsExactly(true, false, true, true, false, false);
        assertThat(list.films()).extracting(FullNeedFilmDto::beingChecked)
            .containsExactly(false, true, false, false, false, false);
    }
}
