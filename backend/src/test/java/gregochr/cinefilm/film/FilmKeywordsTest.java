package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Saving a film's keywords: replaced and stamped for its TMDB id, and refused for a gone or rematched film. */
@ExtendWith(MockitoExtension.class)
class FilmKeywordsTest {

    @Mock
    private FilmRepository films;

    private Film heat() {
        Film film = film(1L, "Heat", 1995, NOW);
        film.setTmdbId(949L);
        return film;
    }

    @Test
    void theKeywordsReplaceTheOldOnesAndTheFilmIsStamped() {
        Film film = heat();
        film.getKeywords().add("old");
        when(films.findById(1L)).thenReturn(Optional.of(film));

        boolean saved = new FilmKeywords(films).save(1L, 949L, List.of("heist", "christmas"), NOW);

        assertThat(saved).isTrue();
        assertThat(film.getKeywords()).containsExactly("heist", "christmas");
        assertThat(film.getKeywordsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void noKeywordsStillStampsTheFilm() {
        Film film = heat();
        when(films.findById(1L)).thenReturn(Optional.of(film));

        boolean saved = new FilmKeywords(films).save(1L, 949L, List.of(), NOW);

        assertThat(saved).isTrue();
        assertThat(film.getKeywords()).isEmpty();
        assertThat(film.getKeywordsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void aKeywordLongerThanTheColumnIsCut() {
        Film film = heat();
        when(films.findById(1L)).thenReturn(Optional.of(film));

        new FilmKeywords(films).save(1L, 949L, List.of("x".repeat(150)), NOW);

        assertThat(film.getKeywords()).containsExactly("x".repeat(100));
    }

    @Test
    void aFilmRematchedMeanwhileIsLeftAlone() {
        Film film = heat();
        film.getKeywords().add("kept");
        when(films.findById(1L)).thenReturn(Optional.of(film));

        boolean saved = new FilmKeywords(films).save(1L, 111L, List.of("heist"), NOW);

        assertThat(saved).isFalse();
        assertThat(film.getKeywords()).containsExactly("kept");
        assertThat(film.getKeywordsFetchedAt()).isNull();
    }

    @Test
    void aFilmThatHasGoneIsRefused() {
        when(films.findById(1L)).thenReturn(Optional.empty());

        assertThat(new FilmKeywords(films).save(1L, 949L, List.of("heist"), NOW)).isFalse();
    }
}
