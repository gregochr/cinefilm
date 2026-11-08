package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LibraryLoaderTest {

    @Mock
    private FilmRepository films;

    @Mock
    private PlaybackStateRepository states;

    private LibraryLoader loader;
    private Film heat;
    private Film alien;
    private Edition heatCut;

    @BeforeEach
    void setUp() {
        loader = new LibraryLoader(films, states);
        heat = film(1L, "Heat", 1995, NOW);
        heatCut = edition(heat, 10L, "Theatrical Cut", 170 * MIN, null);
        alien = film(2L, "Alien", 1979, NOW);
        edition(alien, 20L, "Theatrical Cut", 117 * MIN, null);
    }

    @Test
    void loadAllWrapsEveryFilmWithThePlaybackStateOfItsEditions() {
        PlaybackState heatState = state(heatCut, 64 * MIN, false, NOW, null);
        when(films.findAll()).thenReturn(List.of(heat, alien));
        when(states.findAll()).thenReturn(List.of(heatState));

        List<FilmView> views = loader.loadAll();

        assertThat(views).extracting(FilmView::film).containsExactly(heat, alien);
        assertThat(views.get(0).state(heatCut)).containsSame(heatState);
        assertThat(views.get(1).state(alien.getEditions().get(0))).isEmpty();
    }

    @Test
    void viewsWrapOnlyTheFilmsGivenButStillSeeTheirState() {
        PlaybackState heatState = state(heatCut, 0, true, NOW, NOW);
        when(states.findAll()).thenReturn(List.of(heatState));

        List<FilmView> views = loader.views(List.of(heat));

        assertThat(views).hasSize(1);
        assertThat(views.get(0).film()).isSameAs(heat);
        assertThat(views.get(0).watched()).isTrue();
    }

    @Test
    void anEmptyLibraryLoadsNothing() {
        when(films.findAll()).thenReturn(List.of());
        when(states.findAll()).thenReturn(List.of());

        assertThat(loader.loadAll()).isEmpty();
    }
}
