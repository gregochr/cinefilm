package gregochr.cinefilm.seed;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.FilmRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** A sample video outside the library root cannot be served: the seed stores its bare name and says so. */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@TestPropertySource(properties = {"cinefilm.sample-video=/somewhere/else/sample.mp4",
    "cinefilm.library.root=/media"})
@Transactional
class DevSeedSampleOutsideRootTest {

    @Autowired
    private FilmRepository films;

    @Test
    void theSeededPathIsTheFileNameNotAnAbsolutePath() {
        assertThat(films.findAll().stream().flatMap(f -> f.getEditions().stream()).map(Edition::originalFile))
            .isNotEmpty()
            .allSatisfy(file -> assertThat(file.getPath()).isEqualTo("sample.mp4"));
    }
}
