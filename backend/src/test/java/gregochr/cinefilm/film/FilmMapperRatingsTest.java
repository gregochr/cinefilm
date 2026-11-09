package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.FilmDtos.ImdbRating;
import gregochr.cinefilm.film.FilmDtos.Ratings;
import gregochr.cinefilm.film.FilmDtos.RottenTomatoesRating;
import gregochr.cinefilm.transcode.EncoderMode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The ratings block of the detail DTO: which sources are null and where the IMDb link comes from. */
class FilmMapperRatingsTest {

    private final Film heat = film(1L, "Heat", 1995, NOW);

    private Ratings ratings() {
        return FilmMapper.detail(view(heat), null, false, EncoderMode.SOFTWARE).ratings();
    }

    @Test
    void aFilmWithNeitherScoreHasTwoNullSourcesButStillHasTheBlock() {
        assertThat(ratings()).isEqualTo(new Ratings(null, null));
    }

    @Test
    void bothScoresCarryTheirVotesAndLinks() {
        heat.setImdbId("tt0113277");
        heat.setImdbRating(new BigDecimal("8.3"));
        heat.setImdbVotes(1234567);
        heat.setRtScore(84);
        heat.setRtUrl("https://www.rottentomatoes.com/m/heat_1995");

        assertThat(ratings()).isEqualTo(new Ratings(
            new ImdbRating(new BigDecimal("8.3"), 1234567, "https://www.imdb.com/title/tt0113277/"),
            new RottenTomatoesRating(84, "https://www.rottentomatoes.com/m/heat_1995")));
    }

    @Test
    void imdbIsNullWhenThereIsNoImdbScoreEvenIfTheFilmHasAnImdbId() {
        heat.setImdbId("tt0113277");
        heat.setRtScore(84);

        assertThat(ratings().imdb()).isNull();
        assertThat(ratings().rottenTomatoes()).isEqualTo(new RottenTomatoesRating(84, null));
    }

    @Test
    void rottenTomatoesIsNullWhenThereIsNoScoreEvenIfThereIsAPageAndAScoreOfZeroStillShows() {
        heat.setImdbId("tt0113277");
        heat.setImdbRating(new BigDecimal("7.0"));
        heat.setRtUrl("https://www.rottentomatoes.com/m/heat_1995");

        assertThat(ratings().rottenTomatoes()).isNull();

        heat.setRtScore(0);

        assertThat(ratings().rottenTomatoes()).isEqualTo(
            new RottenTomatoesRating(0, "https://www.rottentomatoes.com/m/heat_1995"));
    }

    @Test
    void theImdbScoreWithoutVotesOrAnIdHasNullVotesAndNoLink() {
        heat.setImdbRating(new BigDecimal("6.5"));

        assertThat(ratings().imdb()).isEqualTo(new ImdbRating(new BigDecimal("6.5"), null, null));
    }
}
