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
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.AudioRow;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedCountsDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullSubtitleStateDto;
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
 * The two figures Admin's Subtitles page shows under "Everything else": {@code subtitled} (browsable films that need
 * full subtitles and play one) and {@code inEnglish} (browsable films whose full need is not needed, in the list's
 * scope or out of it).
 */
@ExtendWith(MockitoExtension.class)
class FullNeedServiceCountsTest {

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

    private static FullNeedRow row(long id, String original, FullNeedChoice owner) {
        return new FullNeedRow(id, "Film " + id, 2000, id * 10, original, original, ASKED, owner);
    }

    /** A film TMDB was never asked about: no languages at all. */
    private static FullNeedRow unmatched(long id) {
        return new FullNeedRow(id, "Film " + id, 2000, null, null, null, null, null);
    }

    /** An edition whose only file is present, or missing (so the edition does not show in browse). */
    private static Edition editionOf(long film, long id, Presence presence) {
        Edition edition = edition(film(film, "Film " + film, 2000, NOW), id, "Original", 100, null);
        MediaFile original = file(edition, "f" + id + ".mp4");
        original.setPresence(presence);
        return edition;
    }

    private FullNeedCountsDto counts() {
        return service.list().counts();
    }

    @Test
    void anEnglishFilmTheOwnerSaidNeedsSubtitlesAndThatPlaysOneIsSubtitledNotEnglish() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "en", FullNeedChoice.NEEDED)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());
        Edition shown = editionOf(1, 10, Presence.PRESENT);
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of(shown));
        when(fullSubtitles.forEditions(List.of(shown)))
            .thenReturn(Map.of(10L, new FullSubtitleDto("SIDECAR", 1, List.of())));
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(shown));

        FullNeedCountsDto counts = counts();

        assertThat(counts.subtitled()).isEqualTo(1);
        assertThat(counts.inEnglish()).isZero();
    }

    @Test
    void theSubtitledCountIsTheRowsFlaggedAndABrowsableFilmThatPlaysASubtitleIsFlagged() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr", null), row(2, "fr", null)));
        when(files.findAudioRows(List.of(1L, 2L))).thenReturn(List.of());
        Edition plays = editionOf(1, 10, Presence.PRESENT);
        Edition waits = editionOf(2, 20, Presence.PRESENT);
        when(editions.findWithFilesByFilmIdIn(List.of(1L, 2L))).thenReturn(List.of(plays, waits));
        when(fullSubtitles.forEditions(List.of(plays, waits)))
            .thenReturn(Map.of(10L, new FullSubtitleDto("SIDECAR", 1, List.of())));
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(plays, waits));

        FullNeedListDto list = service.list();

        assertThat(list.films()).extracting(FullNeedFilmDto::subtitled).containsExactly(true, false);
        assertThat(list.counts().subtitled()).isEqualTo(1);
    }

    @Test
    void aHiddenFilmThatPlaysASidecarIsNotFlaggedSubtitledAndIsNotCounted() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr", null)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());
        Edition hidden = editionOf(1, 10, Presence.MISSING);
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of(hidden));
        when(fullSubtitles.forEditions(List.of(hidden)))
            .thenReturn(Map.of(10L, new FullSubtitleDto("SIDECAR", 1, List.of())));
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(hidden));

        FullNeedListDto list = service.list();

        assertThat(list.films().get(0).subtitled()).isFalse();
        assertThat(list.films().get(0).fullSubtitle()).isEqualTo(new FullSubtitleStateDto("SIDECAR"));
        assertThat(list.counts().subtitled()).isZero();
    }

    @Test
    void aFilmWithOneMissingEditionAndOneEditionWithNoFileRowStillShows() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "en", null)));
        Edition missing = editionOf(1, 10, Presence.MISSING);
        Edition noFiles = edition(film(1L, "Film 1", 2000, NOW), 11L, "Bare", 100, null);
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(missing, noFiles));

        assertThat(counts().inEnglish()).isEqualTo(1);
    }

    @Test
    void aNeededFilmWithNoSubtitleYetIsNeitherSubtitledNorEnglish() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr", null)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());

        FullNeedCountsDto counts = counts();

        assertThat(counts.subtitled()).isZero();
        assertThat(counts.inEnglish()).isZero();
        assertThat(counts.needed()).isEqualTo(1);
    }

    @Test
    void anOwnerNotNeededFilmCountsAsEnglishWhateverItsLanguage() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr", FullNeedChoice.NOT_NEEDED)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());

        FullNeedCountsDto counts = counts();

        assertThat(counts.inEnglish()).isEqualTo(1);
        assertThat(counts.subtitled()).isZero();
    }

    @Test
    void anEnglishDubOfAForeignFilmCountsAsEnglish() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr", null)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of(
            new AudioRow(1L, 10L, "Original", 100L, 1, Presence.PRESENT, "English", true)));

        assertThat(counts().inEnglish()).isEqualTo(1);
    }

    @Test
    void anEnglishOriginalOutOfTheListsScopeCountsAsEnglish() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "en", null), row(2, "EN", null)));

        FullNeedCountsDto counts = counts();

        assertThat(counts.inEnglish()).isEqualTo(2);
        assertThat(counts.needed()).isZero();
    }

    @Test
    void aFilmWithNoLanguageCodeXxOrNoOriginalLanguageCountsAsEnglish() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "xx", null),
            new FullNeedRow(2L, "Film 2", 2000, 20L, null, "en", ASKED, null)));

        assertThat(counts().inEnglish()).isEqualTo(2);
    }

    @Test
    void aFilmTmdbWasNeverAskedAboutIsNotCountedAsEnglish() {
        when(films.findFullNeedRows()).thenReturn(List.of(unmatched(1)));

        FullNeedCountsDto counts = counts();

        assertThat(counts.inEnglish()).isZero();
        assertThat(counts.unknown()).isZero();
        assertThat(counts.subtitled()).isZero();
    }

    @Test
    void aFilmTmdbHasNoLanguagesForIsNotCountedAsEnglishEither() {
        when(films.findFullNeedRows()).thenReturn(List.of(
            new FullNeedRow(1L, "Film 1", 2000, 10L, null, null, ASKED, null)));

        assertThat(counts().inEnglish()).isZero();
    }

    @Test
    void aFilmThatDoesNotShowInBrowseIsCountedInNeitherFigure() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "en", null), row(2, "fr", FullNeedChoice.NEEDED),
            row(3, "en", null)));
        when(files.findAudioRows(List.of(2L))).thenReturn(List.of());
        Edition hiddenEnglish = editionOf(1, 10, Presence.MISSING);
        Edition hiddenSubtitled = editionOf(2, 20, Presence.MISSING);
        Edition shown = editionOf(3, 30, Presence.PRESENT);
        when(editions.findWithFilesByFilmIdIn(List.of(2L))).thenReturn(List.of(hiddenSubtitled));
        when(fullSubtitles.forEditions(List.of(hiddenSubtitled)))
            .thenReturn(Map.of(20L, new FullSubtitleDto("SIDECAR", 1, List.of())));
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(hiddenEnglish, hiddenSubtitled, shown));

        FullNeedCountsDto counts = counts();

        assertThat(counts.inEnglish()).isEqualTo(1);
        assertThat(counts.subtitled()).isZero();
    }

    @Test
    void aFilmShowsWhenAtLeastOneEditionDoesAndWhenItHasNoEditionAtAll() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "en", null), row(2, "en", null),
            row(3, "en", null)));
        // Film 1: one edition missing, one present. Film 2: only a missing edition. Film 3: no edition rows at all.
        Edition missing = editionOf(1, 10, Presence.MISSING);
        Edition present = editionOf(1, 11, Presence.PRESENT);
        Edition onlyMissing = editionOf(2, 20, Presence.MISSING);
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(missing, present, onlyMissing));

        assertThat(counts().inEnglish()).isEqualTo(2);
    }

    @Test
    void aNotNeededFilmThatPlaysASubtitleIsNotSubtitled() {
        when(films.findFullNeedRows()).thenReturn(List.of(row(1, "fr", FullNeedChoice.NOT_NEEDED)));
        when(files.findAudioRows(List.of(1L))).thenReturn(List.of());
        Edition shown = editionOf(1, 10, Presence.PRESENT);
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of(shown));
        when(fullSubtitles.forEditions(List.of(shown)))
            .thenReturn(Map.of(10L, new FullSubtitleDto("DOWNLOAD", 1, List.of())));
        when(editions.findAllWithFilesIncludingEmpty()).thenReturn(List.of(shown));

        FullNeedCountsDto counts = counts();

        assertThat(counts.subtitled()).isZero();
        assertThat(counts.inEnglish()).isEqualTo(1);
    }
}
