package gregochr.cinefilm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * With {@code cinefilm.sample-video} set, every seeded edition plays that one real file (stored relative to the library
 * root) instead of a placeholder.
 */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@TestPropertySource(properties = {"cinefilm.sample-video=/media/sample.mp4", "cinefilm.library.root=/media"})
@Transactional
class DevSeedSampleVideoTest {

    @Autowired
    private FilmRepository films;

    @Test
    void everySeededEditionPointsAtTheConfiguredSampleVideo() {
        List<Film> library = films.findAll();

        assertThat(library).isNotEmpty();
        assertThat(library.stream().flatMap(f -> f.getEditions().stream()).map(Edition::originalFile))
            .isNotEmpty()
            .allSatisfy(file -> assertThat(file.getPath()).isEqualTo("sample.mp4"));
    }
}
