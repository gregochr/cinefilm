package gregochr.cinefilm.playback;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.playback.PlaybackDtos.PreferencesRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreferencesServiceTest {

    private static final long FILM_ID = 5L;
    private static final long FINAL_CUT = 50L;
    private static final long THEATRICAL = 51L;
    private static final long COMMENTARY_ID = 501L;
    private static final long MAIN_AUDIO_ID = 500L;

    @Mock
    private FilmRepository films;

    @Mock
    private FilmPreferenceRepository stored;

    private PreferencesService service;
    private Film film;
    private Edition finalCut;
    private Edition theatrical;

    @BeforeEach
    void setUp() {
        service = new PreferencesService(films, stored);
        film = film(FILM_ID, "Blade Runner", 1982, NOW);
        finalCut = edition(film, FINAL_CUT, "The Final Cut", 117 * MIN, null);
        theatrical = edition(film, THEATRICAL, "Theatrical Cut", 117 * MIN, null);
        MediaFile original = file(finalCut, "/nowhere/final.mp4");
        original.getAudioStreams().get(0).setId(MAIN_AUDIO_ID);
        original.getAudioStreams().get(1).setId(COMMENTARY_ID);
    }

    private void storedChoices(FilmPreference choices) {
        when(stored.findById(FILM_ID)).thenReturn(Optional.of(choices));
    }

    private FilmPreference savedChoices() {
        ArgumentCaptor<FilmPreference> captor = ArgumentCaptor.forClass(FilmPreference.class);
        verify(stored).save(captor.capture());
        return captor.getValue();
    }

    // resolve

    @Test
    void withNothingStoredTheFirstEditionAutoQualityDefaultAudioAndNoSubtitlesApply() {
        Preferences resolved = service.resolve(film);

        assertThat(resolved).isEqualTo(new Preferences(FINAL_CUT, "auto", MAIN_AUDIO_ID, "off"));
    }

    @Test
    void theLastPlayedEditionBeatsTheDefaultEdition() {
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setLastEditionId(THEATRICAL);
        choices.setDefaultEditionId(FINAL_CUT);
        storedChoices(choices);

        assertThat(service.resolve(film).editionId()).isEqualTo(THEATRICAL);
    }

    @Test
    void aMissingLastEditionFallsBackToTheDefaultEdition() {
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setLastEditionId(123_456L);
        choices.setDefaultEditionId(THEATRICAL);
        storedChoices(choices);

        assertThat(service.resolve(film).editionId()).isEqualTo(THEATRICAL);
    }

    @Test
    void whenNoStoredEditionExistsAnymoreTheFirstEditionIsUsed() {
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setLastEditionId(123_456L);
        choices.setDefaultEditionId(654_321L);
        storedChoices(choices);

        assertThat(service.resolve(film).editionId()).isEqualTo(FINAL_CUT);
    }

    @Test
    void storedQualityAudioAndSubtitleAreReturnedAsTheyAre() {
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setQuality("720p");
        choices.setAudioStreamId(COMMENTARY_ID);
        choices.setSubtitle("12");
        storedChoices(choices);

        assertThat(service.resolve(film)).isEqualTo(new Preferences(FINAL_CUT, "720p", COMMENTARY_ID, "12"));
    }

    @Test
    void aFilmWithoutEditionsResolvesToNoEditionAndNoAudio() {
        Film empty = film(6L, "Empty", 2000, NOW);
        when(stored.findById(6L)).thenReturn(Optional.empty());

        assertThat(service.resolve(empty)).isEqualTo(new Preferences(null, "auto", null, "off"));
    }

    @Test
    void anEditionWithoutAnOriginalFileHasNoDefaultAudio() {
        Film bare = film(8L, "No File", 2000, NOW);
        edition(bare, 80L, "Theatrical Cut", 100 * MIN, null);

        assertThat(service.resolve(bare).audioStreamId()).isNull();
    }

    @Test
    void withoutADefaultFlaggedTrackTheFirstAudioTrackIsTheDefault() {
        Film other = film(9L, "No Default", 2000, NOW);
        Edition only = edition(other, 90L, "Theatrical Cut", 100 * MIN, null);
        MediaFile original = file(only, "/nowhere/other.mp4");
        original.getAudioStreams().forEach(a -> a.setDefault(false));
        original.getAudioStreams().get(0).setId(900L);
        original.getAudioStreams().get(1).setId(901L);

        assertThat(service.resolve(other).audioStreamId()).isEqualTo(900L);
    }

    @Test
    void anOriginalFileWithoutAudioTracksHasNoDefaultAudio() {
        Film other = film(10L, "Silent", 1925, NOW);
        Edition only = edition(other, 100L, "Theatrical Cut", 60 * MIN, null);
        MediaFile original = file(only, "/nowhere/silent.mp4");
        original.getAudioStreams().clear();

        assertThat(service.resolve(other).audioStreamId()).isNull();
    }

    // update

    @Test
    void updatingAnUnknownFilmIsNotFound() {
        assertThatThrownBy(() -> service.update(404L, new PreferencesRequest(null, null, null, null)))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such film");
    }

    @Test
    void anEditionOfAnotherFilmIsRejected() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));

        assertThatThrownBy(() -> service.update(FILM_ID, new PreferencesRequest(999L, null, null, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Edition does not belong to this film");
        verify(stored).findById(FILM_ID);
        verifyNoMoreInteractions(stored);
    }

    @Test
    void choosingAnEditionRemembersItAndMakesItTheDefaultWhenThereIsNone() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));
        storedChoices(new FilmPreference(FILM_ID));

        Preferences result = service.update(FILM_ID, new PreferencesRequest(THEATRICAL, null, null, null));

        FilmPreference saved = savedChoices();
        assertThat(saved.getLastEditionId()).isEqualTo(THEATRICAL);
        assertThat(saved.getDefaultEditionId()).isEqualTo(THEATRICAL);
        assertThat(result.editionId()).isEqualTo(THEATRICAL);
    }

    @Test
    void choosingAnotherEditionLeavesAnExistingDefaultAlone() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setDefaultEditionId(THEATRICAL);
        storedChoices(choices);

        service.update(FILM_ID, new PreferencesRequest(FINAL_CUT, null, null, null));

        FilmPreference saved = savedChoices();
        assertThat(saved.getLastEditionId()).isEqualTo(FINAL_CUT);
        assertThat(saved.getDefaultEditionId()).isEqualTo(THEATRICAL);
    }

    @Test
    void qualityIsLowerCasedAndStored() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));
        storedChoices(new FilmPreference(FILM_ID));

        Preferences result = service.update(FILM_ID, new PreferencesRequest(null, "Original", null, null));

        assertThat(savedChoices().getQuality()).isEqualTo("original");
        assertThat(result.quality()).isEqualTo("original");
    }

    @Test
    void everyListedQualityIsAccepted() {
        assertThat(PreferencesService.QUALITIES)
            .containsExactly("auto", "original", "1080p", "720p", "480p", "360p", "240p");
    }

    @Test
    void anUnknownQualityIsRejectedWithTheAllowedList() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));

        assertThatThrownBy(() -> service.update(FILM_ID, new PreferencesRequest(null, "4k", null, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("quality must be one of [auto, original, 1080p, 720p, 480p, 360p, 240p]");
        verify(stored).findById(FILM_ID);
        verifyNoMoreInteractions(stored);
    }

    @Test
    void audioAndSubtitleChoicesAreStored() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));

        service.update(FILM_ID, new PreferencesRequest(null, null, COMMENTARY_ID, "12"));

        FilmPreference saved = savedChoices();
        assertThat(saved.getAudioStreamId()).isEqualTo(COMMENTARY_ID);
        assertThat(saved.getSubtitle()).isEqualTo("12");
    }

    @Test
    void fieldsLeftOutOfTheRequestStayAsTheyWere() {
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setQuality("720p");
        choices.setSubtitle("12");
        choices.setAudioStreamId(COMMENTARY_ID);
        storedChoices(choices);

        service.update(FILM_ID, new PreferencesRequest(null, null, null, null));

        FilmPreference saved = savedChoices();
        assertThat(saved.getQuality()).isEqualTo("720p");
        assertThat(saved.getSubtitle()).isEqualTo("12");
        assertThat(saved.getAudioStreamId()).isEqualTo(COMMENTARY_ID);
        assertThat(saved.getLastEditionId()).isNull();
    }

    // rememberLastEdition

    @Test
    void rememberingAnEditionCreatesThePreferencesWhenThereAreNone() {
        service.rememberLastEdition(FILM_ID, THEATRICAL);

        FilmPreference saved = savedChoices();
        assertThat(saved.getFilmId()).isEqualTo(FILM_ID);
        assertThat(saved.getLastEditionId()).isEqualTo(THEATRICAL);
    }

    @Test
    void rememberingAnEditionKeepsTheOtherChoices() {
        FilmPreference choices = new FilmPreference(FILM_ID);
        choices.setQuality("1080p");
        storedChoices(choices);

        service.rememberLastEdition(FILM_ID, FINAL_CUT);

        FilmPreference saved = savedChoices();
        assertThat(saved.getLastEditionId()).isEqualTo(FINAL_CUT);
        assertThat(saved.getQuality()).isEqualTo("1080p");
    }
}
