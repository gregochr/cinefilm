package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.time.Duration;
import java.util.List;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Applying TMDB's film stores its languages and stamps them from the injected clock. */
class LibraryIngestLanguagesTest extends ApiTestBase {

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private EntityManager em;

    private static MovieDetails movie(String original, List<String> spoken) {
        return new MovieDetails(949, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0, List.of(),
            List.of(), null, null, original, spoken);
    }

    @Test
    void theLanguagesAreStoredAndStampedWithTheClock() {
        Film film = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(film, movie("en", List.of("en", "es")));

        assertThat(film.getOriginalLanguage()).isEqualTo("en");
        assertThat(film.getSpokenLanguages()).isEqualTo("en,es");
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void aFilmTmdbListsNoLanguagesForIsStampedWithAnEmptyListAndNoOriginal() {
        Film film = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(film, movie(null, List.of()));

        assertThat(film.getOriginalLanguage()).isNull();
        assertThat(film.getSpokenLanguages()).isEmpty();
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void aFixMatchReplacesTheOldFilmsLanguagesAndRestamps() {
        Film film = film(null, "Heat", 1995, NOW);
        film.setOriginalLanguage("fr");
        film.setSpokenLanguages("fr,de");
        film.setLanguagesFetchedAt(NOW.minus(Duration.ofDays(9)));

        ingest.applyMovie(film, movie("en", List.of("en")));

        assertThat(film.getOriginalLanguage()).isEqualTo("en");
        assertThat(film.getSpokenLanguages()).isEqualTo("en");
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void languagesTheBackfillSavedForTheOldTmdbFilmAreOverwrittenWhenARematchAppliesAFilmWithNone() {
        Film saved = film(null, "Heat", 1995, NOW);
        saved.setTmdbId(111L);
        Long id = films.saveAndFlush(saved).getId();
        em.clear();
        Film loaded = films.findById(id).orElseThrow();
        // Meanwhile the backfill saves the old TMDB film's languages: the entity loaded null and will set null.
        assertThat(films.saveLanguages(id, 111L, "fr", "fr,de", NOW.minusSeconds(5))).isEqualTo(1);

        ingest.applyMovie(loaded, movie(null, List.of()));
        em.flush();
        em.clear();

        Film result = films.findById(id).orElseThrow();
        assertThat(result.getTmdbId()).isEqualTo(949L);
        assertThat(result.getOriginalLanguage()).isNull();
        assertThat(result.getSpokenLanguages()).isEmpty();
        assertThat(result.getLanguagesFetchedAt()).isEqualTo(NOW);
        assertThat(films.saveLanguages(id, 111L, "fr", "fr", NOW)).as("a late backfill for the old id").isZero();
    }

    @Test
    void aFilmNotSavedYetIsLeftToItsInsert() {
        Film fresh = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(fresh, movie("en", List.of("en")));
        Long id = films.saveAndFlush(fresh).getId();
        em.clear();

        Film result = films.findById(id).orElseThrow();
        assertThat(result.getOriginalLanguage()).isEqualTo("en");
        assertThat(result.getLanguagesFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void aMovieBuiltWithoutLanguagesStoresNoneButStillStamps() {
        Film film = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(film, new MovieDetails(949, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0,
            List.of(), List.of(), null));

        assertThat(film.getOriginalLanguage()).isNull();
        assertThat(film.getSpokenLanguages()).isEmpty();
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void applyingAMovieWithANewTmdbIdKeepsTheNewLanguagesNotTheOldOnes() {
        Film film = film(null, "Heat", 1995, NOW);
        film.setTmdbId(111L);
        film.setOriginalLanguage("fr");
        film.setSpokenLanguages("fr,de");
        film.setLanguagesFetchedAt(NOW.minus(Duration.ofDays(9)));

        ingest.applyMovie(film, movie("en", List.of("en", "es")));

        assertThat(film.getTmdbId()).isEqualTo(949L);
        assertThat(film.getOriginalLanguage()).isEqualTo("en");
        assertThat(film.getSpokenLanguages()).isEqualTo("en,es");
        assertThat(film.getLanguagesFetchedAt()).isEqualTo(NOW);
    }
}
