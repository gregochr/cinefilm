package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** References are keyed by film, IMDb id and file id: one fetched for another IMDb id is never found or in the way. */
class OpenSubtitlesReferenceRepositoryTest extends ApiTestBase {

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    private Long film() {
        return films.saveAndFlush(gregochr.cinefilm.TestData.film(null, "Taken", 2008, NOW)).getId();
    }

    private OpenSubtitlesReference reference(Long filmId, String imdbId, long fileId, int failed) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(filmId);
        reference.setImdbId(imdbId);
        reference.setOsFileId(fileId);
        reference.setCuesText("");
        reference.setCueCount(0);
        reference.setFailedDownloads(failed);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    @Test
    void aFinalUnusableReferenceForTheOldImdbIdIsNotFoundForTheNewOneAndDoesNotBlockItsRow() {
        Long filmId = film();
        OpenSubtitlesReference old = reference(filmId, "tt0000001", 5011, 3);

        assertThat(references.findByFilmIdAndImdbIdAndOsFileId(filmId, "tt0000002", 5011)).isEmpty();
        OpenSubtitlesReference fresh = reference(filmId, "tt0000002", 5011, 0);

        assertThat(fresh.getId()).isNotEqualTo(old.getId());
        assertThat(references.findByFilmIdAndImdbIdAndOsFileId(filmId, "tt0000001", 5011).orElseThrow().isFinal())
            .isTrue();
        assertThat(references.findByFilmIdAndImdbIdAndOsFileId(filmId, "tt0000002", 5011).orElseThrow().isFinal())
            .isFalse();
    }

    @Test
    void theSameFilmImdbIdAndFileCannotBeStoredTwice() {
        Long filmId = film();
        reference(filmId, "tt0000001", 5011, 0);

        assertThatThrownBy(() -> reference(filmId, "tt0000001", 5011, 0))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
