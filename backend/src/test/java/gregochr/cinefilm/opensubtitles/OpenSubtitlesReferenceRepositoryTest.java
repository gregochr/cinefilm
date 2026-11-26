package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** References are keyed by film, IMDb id and file id: one fetched for another IMDb id is never found or in the way. */
class OpenSubtitlesReferenceRepositoryTest extends ApiTestBase {

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @PersistenceContext
    private EntityManager entityManager;

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

    @Test
    void fillTimedFpsFillsAMissingRateAndLeavesAnExistingOneAlone() {
        Long filmId = film();
        OpenSubtitlesReference without = reference(filmId, "tt0000001", 5011, 0);
        OpenSubtitlesReference with = reference(filmId, "tt0000001", 5012, 0);
        with.setTimedFps(23.976);
        references.saveAndFlush(with);
        OpenSubtitlesReference other = reference(filmId, "tt0000001", 5013, 0);

        assertThat(references.fillTimedFps(without.getId(), 25.0)).isEqualTo(1);
        assertThat(references.fillTimedFps(with.getId(), 25.0)).isZero();

        assertThat(references.findById(without.getId()).orElseThrow().getTimedFps()).isEqualTo(25.0);
        assertThat(references.findById(with.getId()).orElseThrow().getTimedFps()).isEqualTo(23.976);
        assertThat(references.findById(other.getId()).orElseThrow().getTimedFps())
            .as("another rateless row is not touched").isNull();
    }

    @Test
    void fillTimedFpsChangesNothingElseOfTheRow() {
        Long filmId = film();
        OpenSubtitlesReference row = reference(filmId, "tt0000001", 5011, 2);
        row.setLastCueEndMs(7_000L);
        references.saveAndFlush(row);

        references.fillTimedFps(row.getId(), 25.0);

        OpenSubtitlesReference found = references.findById(row.getId()).orElseThrow();
        assertThat(found.getLastCueEndMs()).isEqualTo(7_000L);
        assertThat(found.getFailedDownloads()).isEqualTo(2);
    }

    @Test
    void aSaveOfAnEntityLoadedBeforeAFillWritesOnlyWhatItChangedSoTheFillIsKept() {
        Long filmId = film();
        OpenSubtitlesReference loaded = reference(filmId, "tt0000001", 5011, 0);
        // Another transaction's fill: it goes straight to the database, so the loaded entity does not see it.
        entityManager.createNativeQuery("update opensubtitles_reference set timed_fps = 25.0 where id = :id")
            .setParameter("id", loaded.getId()).executeUpdate();

        loaded.setLastCueEndMs(7_000L);
        references.saveAndFlush(loaded);

        Object[] row = (Object[]) entityManager.createNativeQuery(
            "select timed_fps, last_cue_end_ms from opensubtitles_reference where id = :id")
            .setParameter("id", loaded.getId()).getSingleResult();
        assertThat(((Number) row[0]).doubleValue()).isEqualTo(25.0);
        assertThat(((Number) row[1]).longValue()).isEqualTo(7_000L);
    }

    private OpenSubtitlesReference usable(Long filmId, long fileId, Double timedFps, Double retimedBy) {
        OpenSubtitlesReference reference = reference(filmId, "tt0000001", fileId, 0);
        reference.setCuesText("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n");
        reference.setCueCount(1);
        reference.setTimedFps(timedFps);
        reference.setRetimedBy(retimedBy);
        return references.saveAndFlush(reference);
    }

    @Test
    void theRetimeCandidatesAreTheUsableReferencesWithAStatedRateOrARetimeInIdOrder() {
        Long filmId = film();
        OpenSubtitlesReference rated = usable(filmId, 5011, 23.976, null);
        usable(filmId, 5012, null, null);
        OpenSubtitlesReference retimed = usable(filmId, 5013, null, 0.96);
        OpenSubtitlesReference unusable = reference(filmId, "tt0000001", 5014, 3);
        unusable.setTimedFps(25.0);
        references.saveAndFlush(unusable);

        assertThat(references.findRetimeCandidateIds()).containsExactly(rated.getId(), retimed.getId());
    }

    @Test
    void theCuesVersionStartsAtZeroIsRaisedByReplacingCuesAndReadAloneAndInTheFacts() {
        Long filmId = film();
        OpenSubtitlesReference reference = usable(filmId, 5011, 23.976, null);
        assertThat(references.findCuesVersionForUpdate(reference.getId())).contains(0);

        reference.replaceCues("1\n00:00:02,000 --> 00:00:03,000\nHi\n\n");
        references.saveAndFlush(reference);

        assertThat(references.findCuesVersionForUpdate(reference.getId())).contains(1);
        assertThat(references.findFactsByFilmIdIn(java.util.List.of(filmId))).extracting(
            OpenSubtitlesReferenceRepository.ReferenceFacts::cuesVersion).containsExactly(1);
        assertThat(references.findCuesVersionForUpdate(reference.getId() + 1000)).isEmpty();
    }
}
