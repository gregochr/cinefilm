package gregochr.cinefilm.settings;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.settings.SettingsDtos.Settings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerSettingsServiceTest {

    @Mock
    private ServerSettingRepository repository;

    @Mock
    private LibraryLoader loader;

    private ServerSettingsService service;
    private ServerSetting row;

    @BeforeEach
    void setUp() {
        service = new ServerSettingsService(repository, loader);
        row = new ServerSetting();
        // Lenient: validation fails before the row is read in some tests.
        lenient().when(repository.findById(ServerSetting.ONLY_ROW)).thenReturn(Optional.of(row));
    }

    private FilmView playedTo(long id, long positionMs) {
        Film film = film(id, "Film " + id, 2000, NOW);
        Edition edition = edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film, state(edition, positionMs, false, NOW, null));
    }

    @Test
    void theMinimumDefaultsToFiveMinutes() {
        assertThat(service.minWatchMs()).isEqualTo(5 * MIN);
    }

    @Test
    void currentCountsFilmsInProgressUnderTheStoredMinimumHiddenFilmsIncluded() {
        FilmView hidden = playedTo(3, 20 * MIN);
        hidden.states().values().forEach(s -> s.setHiddenFromContinueWatching(true));
        when(loader.loadAll()).thenReturn(List.of(playedTo(1, 4 * MIN), playedTo(2, 5 * MIN), hidden));

        assertThat(service.current()).isEqualTo(new Settings(5, 2));
    }

    @Test
    void updateStoresTheMinimumAndRecomputesTheCount() {
        when(loader.loadAll()).thenReturn(List.of(playedTo(1, 4 * MIN), playedTo(2, 5 * MIN)));
        when(repository.save(row)).thenReturn(row);

        Settings result = service.update(4);

        assertThat(result).isEqualTo(new Settings(4, 2));
        assertThat(service.minWatchMs()).isEqualTo(4 * MIN);
        verify(repository).save(row);
    }

    @Test
    void theBoundsZeroAndThirtyAreAccepted() {
        when(loader.loadAll()).thenReturn(List.of());
        when(repository.save(row)).thenReturn(row);

        assertThat(service.update(0).continueWatchingMinMinutes()).isZero();
        assertThat(service.update(30).continueWatchingMinMinutes()).isEqualTo(30);
        assertThat(service.minWatchMs()).isEqualTo(30 * MIN);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 31, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @NullSource
    void aMinimumOutsideZeroToThirtyOrMissingIsABadRequestAndChangesNothing(Integer minutes) {
        assertThatThrownBy(() -> service.update(minutes)).isInstanceOf(BadRequestException.class)
            .hasMessage("continueWatchingMinMinutes must be a whole number from 0 to 30");

        assertThat(row.getContinueWatchingMinMinutes()).isEqualTo(5);
    }

    @Test
    void aMissingRowIsAServerError() {
        when(repository.findById(ServerSetting.ONLY_ROW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.minWatchMs()).isInstanceOf(IllegalStateException.class);
    }
}
