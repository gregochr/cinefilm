package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link EditionRepository#findAllWithFilesIncludingEmpty()} on PostgreSQL: it joins every edition to all of its
 * files, which repeats the edition once per file in SQL; each edition must still come back once. This is what lets
 * the query go without {@code distinct} (which made Postgres sort the whole library on every read).
 */
class EditionListIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private EditionRepository editions;

    @BeforeEach
    void clean() {
        films.deleteAll();
    }

    private Film film(String title, int files) {
        Film film = TestData.film(null, title, 2002, NOW);
        Edition edition = TestData.edition(film, null, "Original", 90 * MIN, null);
        for (int part = 1; part <= files; part++) {
            MediaFile file = TestData.file(edition, title + " - pt" + part + ".m4v");
            file.setPartNumber(part);
        }
        return films.saveAndFlush(film);
    }

    @Test
    void anEditionWithSeveralFilesComesBackOnceWithAllOfThem() {
        film("Towers", 3);

        List<Edition> all = editions.findAllWithFilesIncludingEmpty();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getMediaFiles()).hasSize(3);
    }

    @Test
    void everyEditionComesBackOnceWhetherItHasManyFilesOneOrNone() {
        film("Towers", 3);
        film("Taken", 1);
        film("Bare", 0);

        List<Edition> all = editions.findAllWithFilesIncludingEmpty();

        assertThat(all).extracting(Edition::getId).doesNotHaveDuplicates().hasSize(3);
        assertThat(all).extracting(e -> e.getMediaFiles().size()).containsExactlyInAnyOrder(3, 1, 0);
    }
}
