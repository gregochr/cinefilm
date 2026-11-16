package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmDtos.FullSubtitleDto;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.FullNeedRow;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.AudioRow;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link FullNeedService#unresolvedCount()} is the cheap path the Admin summary polls: it must answer what the
 * full list's {@code counts.unresolved} says while reading the editions of the needed films only (strict stubs fail
 * the test if another id list is asked for).
 */
@ExtendWith(MockitoExtension.class)
class FullNeedServiceUnresolvedUnitTest {

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

    @Mock
    private PlatformTransactionManager manager;

    private FullNeedService service;

    @BeforeEach
    void setUp() {
        service = new FullNeedService(films, files, editions, fullSubtitles, pending, OpenSubtitlesSwitches.on(),
            new LibraryLock(), manager);
    }

    private static FullNeedRow row(long id, String original) {
        return new FullNeedRow(id, "Film " + id, 2000, id * 10, original, original, ASKED, null);
    }

    private static Edition editionOf(long film, long id) {
        Edition edition = edition(film(film, "Film " + film, 2000, NOW), id, "Original", 100, null);
        file(edition, "f" + id + ".mp4");
        return edition;
    }

    private static AudioRow english(long film, long edition) {
        return new AudioRow(film, edition, "Original", edition * 10, 1, Presence.PRESENT, "English", true);
    }

    @Test
    void noFilmsInScopeIsZero() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "en")));

        assertThat(service.unresolvedCount()).isZero();
    }

    @Test
    void filmsInScopeThatAreNotNeededAreZeroAndNoEditionsAreRead() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr")));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of(english(1, 10)));

        assertThat(service.unresolvedCount()).isZero();
    }

    @Test
    void oneNeededFilmWithNoSubtitleIsOneAndTwoAreTwo() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr")));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of());

        assertThat(service.unresolvedCount()).isEqualTo(1);
    }

    @Test
    void twoNeededFilmsWithNoSubtitleAreTwo() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr"), row(2, "de")));
        when(files.findAudioRows(List.of(1L, 2L))).thenReturn(List.of());
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L))).thenReturn(List.of());

        assertThat(service.unresolvedCount()).isEqualTo(2);
    }

    @Test
    void onlyTheNeededFilmsEditionsAreLoadedAndAPlayingSubtitleSettlesAFilm() {
        // 1 needed and plays a sidecar; 2 needed and plays none; 3 has an English dub (not needed); 4 is English.
        Edition plays = editionOf(1, 10);
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr"), row(2, "fr"), row(3, "fr"), row(4, "en")));
        when(files.findAudioRows(List.of(1L, 2L, 3L))).thenReturn(List.of(english(3, 30)));
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L))).thenReturn(List.of(plays));
        when(fullSubtitles.forEditions(List.of(plays)))
            .thenReturn(Map.of(10L, new FullSubtitleDto("SIDECAR", 1, List.of(), List.of())));

        assertThat(service.unresolvedCount()).isEqualTo(1);
    }

    @Test
    void itAnswersWhatTheFullListsCountSays() {
        Edition plays = editionOf(1, 10);
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr"), row(2, "fr"), row(3, "fr"), row(4, "en")));
        when(files.findAudioRows(List.of(1L, 2L, 3L))).thenReturn(List.of(english(3, 30)));
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L))).thenReturn(List.of(plays));
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L, 3L))).thenReturn(List.of(plays));
        when(fullSubtitles.forEditions(List.of(plays)))
            .thenReturn(Map.of(10L, new FullSubtitleDto("SIDECAR", 1, List.of(), List.of())));
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(plays));

        assertThat(service.unresolvedCount()).isEqualTo(service.list().counts().unresolved());
    }
}
