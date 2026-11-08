package gregochr.cinefilm.playback;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressResponse;
import gregochr.cinefilm.playback.PlaybackDtos.WatchedResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    private static final long FILM_ID = 7L;
    private static final long WITH_MARKER = 70L;
    private static final long WITHOUT_MARKER = 71L;

    @Mock
    private EditionRepository editions;

    @Mock
    private PlaybackStateRepository states;

    @Mock
    private PreferencesService preferences;

    private PlaybackService service;
    private Edition withMarker;
    private Edition withoutMarker;

    @BeforeEach
    void setUp() {
        service = new PlaybackService(editions, states, preferences, CLOCK);
        Film film = film(FILM_ID, "Blade Runner", 1982, NOW);
        withMarker = edition(film, WITH_MARKER, "The Final Cut", 100 * MIN, 90 * MIN);
        withoutMarker = edition(film, WITHOUT_MARKER, "Theatrical Cut", 100 * MIN, null);
    }

    /** Makes the repository know the edition; with no stored playback state yet unless a test adds one. */
    private Edition use(Edition edition) {
        when(editions.findById(edition.getId())).thenReturn(Optional.of(edition));
        return edition;
    }

    private void stored(PlaybackState state) {
        when(states.findById(state.getEditionId())).thenReturn(Optional.of(state));
    }

    private PlaybackState saved() {
        ArgumentCaptor<PlaybackState> captor = ArgumentCaptor.forClass(PlaybackState.class);
        verify(states).save(captor.capture());
        return captor.getValue();
    }

    // reportProgress: validation

    @Test
    void progressWithoutAPositionIsRejectedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.reportProgress(WITH_MARKER, null))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("positionMs must be a non-negative number");
        verify(states, never()).save(any());
    }

    @Test
    void aNegativePositionIsRejected() {
        assertThatThrownBy(() -> service.reportProgress(WITH_MARKER, -1L))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("positionMs must be a non-negative number");
        verify(states, never()).save(any());
    }

    @Test
    void aPositionOfZeroIsAllowed() {
        use(withMarker);

        ProgressResponse response = service.reportProgress(WITH_MARKER, 0L);

        assertThat(response).isEqualTo(new ProgressResponse(0, false));
    }

    @Test
    void progressForAnUnknownEditionIsNotFound() {
        assertThatThrownBy(() -> service.reportProgress(999L, 5L))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    // reportProgress: the watched rule at its boundaries

    @Test
    void oneMillisecondBeforeTheCreditsMarkerIsNotWatched() {
        use(withMarker);

        ProgressResponse response = service.reportProgress(WITH_MARKER, 90 * MIN - 1);

        assertThat(response).isEqualTo(new ProgressResponse(90 * MIN - 1, false));
        PlaybackState state = saved();
        assertThat(state.getEditionId()).isEqualTo(WITH_MARKER);
        assertThat(state.getPositionMs()).isEqualTo(90 * MIN - 1);
        assertThat(state.isWatched()).isFalse();
        assertThat(state.getWatchedAt()).isNull();
        assertThat(state.getLastPlayedAt()).isEqualTo(NOW);
    }

    @Test
    void exactlyAtTheCreditsMarkerIsWatchedAndResetsThePosition() {
        use(withMarker);

        ProgressResponse response = service.reportProgress(WITH_MARKER, 90 * MIN);

        assertThat(response).isEqualTo(new ProgressResponse(0, true));
        PlaybackState state = saved();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getPositionMs()).isZero();
        assertThat(state.getWatchedAt()).isEqualTo(NOW);
        assertThat(state.getLastPlayedAt()).isEqualTo(NOW);
    }

    @Test
    void withoutAMarkerNinetyTwoPercentOfTheRuntimeDecides() {
        use(withoutMarker);

        assertThat(service.reportProgress(WITHOUT_MARKER, 92 * MIN - 1).watched()).isFalse();
        assertThat(service.reportProgress(WITHOUT_MARKER, 92 * MIN).watched()).isTrue();
    }

    @Test
    void progressRemembersWhichEditionOfTheFilmWasPlayed() {
        use(withoutMarker);

        service.reportProgress(WITHOUT_MARKER, MIN);

        verify(preferences).rememberLastEdition(FILM_ID, WITHOUT_MARKER);
    }

    // reportProgress: an existing state

    @Test
    void creditsPlayingOutOnAWatchedEditionKeepTheOriginalWatchedAt() {
        use(withMarker);
        Instant firstWatched = NOW.minus(Duration.ofDays(400));
        stored(state(withMarker, 0, true, firstWatched, firstWatched));

        ProgressResponse response = service.reportProgress(WITH_MARKER, 99 * MIN);

        assertThat(response).isEqualTo(new ProgressResponse(0, true));
        PlaybackState state = saved();
        assertThat(state.getWatchedAt()).isEqualTo(firstWatched);
        assertThat(state.getLastPlayedAt()).isEqualTo(NOW);
    }

    @Test
    void aRewatchBelowTheMarkerKeepsTheEditionWatchedAndSavesTheNewPosition() {
        use(withMarker);
        Instant firstWatched = NOW.minus(Duration.ofDays(400));
        stored(state(withMarker, 0, true, firstWatched, firstWatched));

        ProgressResponse response = service.reportProgress(WITH_MARKER, 5 * MIN);

        assertThat(response).isEqualTo(new ProgressResponse(5 * MIN, true));
        assertThat(saved().getWatchedAt()).isEqualTo(firstWatched);
    }

    // setWatched

    @Test
    void setWatchedWithoutAValueIsRejected() {
        assertThatThrownBy(() -> service.setWatched(WITH_MARKER, null))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("watched must be true or false");
    }

    @Test
    void setWatchedForAnUnknownEditionIsNotFound() {
        assertThatThrownBy(() -> service.setWatched(999L, true))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void markingWatchedStampsTheTimeAndResetsThePosition() {
        use(withMarker);
        stored(state(withMarker, 10 * MIN, false, NOW.minusSeconds(60), null));

        WatchedResponse response = service.setWatched(WITH_MARKER, true);

        assertThat(response).isEqualTo(new WatchedResponse(true));
        PlaybackState state = saved();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getWatchedAt()).isEqualTo(NOW);
        assertThat(state.getPositionMs()).isZero();
    }

    @Test
    void markingAnAlreadyWatchedEditionWatchedKeepsTheOriginalTime() {
        use(withMarker);
        Instant firstWatched = NOW.minus(Duration.ofDays(30));
        stored(state(withMarker, 0, true, firstWatched, firstWatched));

        service.setWatched(WITH_MARKER, true);

        assertThat(saved().getWatchedAt()).isEqualTo(firstWatched);
    }

    @Test
    void markingWatchedWithNoStateCreatesOne() {
        use(withoutMarker);

        service.setWatched(WITHOUT_MARKER, true);

        PlaybackState state = saved();
        assertThat(state.getEditionId()).isEqualTo(WITHOUT_MARKER);
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getWatchedAt()).isEqualTo(NOW);
    }

    @Test
    void unwatchingClearsTheFlagTheTimeAndThePosition() {
        use(withMarker);
        Instant watchedAt = NOW.minus(Duration.ofDays(2));
        stored(state(withMarker, 3 * MIN, true, watchedAt, watchedAt));

        WatchedResponse response = service.setWatched(WITH_MARKER, false);

        assertThat(response).isEqualTo(new WatchedResponse(false));
        PlaybackState state = saved();
        assertThat(state.isWatched()).isFalse();
        assertThat(state.getWatchedAt()).isNull();
        assertThat(state.getPositionMs()).isZero();
    }
}
