package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.FileRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import gregochr.cinefilm.search.SearchIndexCache;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;

/**
 * A save that makes a new film (Not this film) must change what the search index serves. The data is committed for
 * real, because the cache is invalidated by an event that fires only once the work has committed.
 */
class EditionArrangerSearchIndexTest extends ApiTestBase {

    @Autowired
    private EditionArranger arranger;

    @Autowired
    private SearchIndexCache cache;

    @Autowired
    private GroupingChangeRepository groupingChanges;

    @AfterEach
    void cleanUp() {
        states.deleteAll();
        preferences.deleteAll();
        groupingChanges.deleteAll();
        films.deleteAll();
        cache.invalidate();
    }

    @Test
    void aFileMarkedNotThisFilmChangesTheSearchIndexEtag() {
        Film built = film(null, "Rocky IV", 1985, NOW);
        var edition = edition(built, null, "Original", 91 * MIN, null);
        file(edition, "Rocky IV.m4v");
        MediaFile intruder = file(edition, "Rocky V.m4v");
        intruder.setPartNumber(2);
        Film saved = save(built);
        Long editionId = saved.getEditions().get(0).getId();
        List<MediaFile> parts = saved.getEditions().get(0).originalParts();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        cache.invalidate();
        String before = cache.get().etag();

        arranger.save(saved.getId(), new SaveRequest(List.of(new EditionRequest(editionId, "Original", true,
            List.of(new FileRequest(parts.get(0).getId(), null)))), List.of(parts.get(1).getId())));

        assertThat(cache.get().etag()).isNotEqualTo(before);
        assertThat(films.findAll()).extracting(Film::getTitle).contains("Rocky IV", "Rocky V");
    }
}
