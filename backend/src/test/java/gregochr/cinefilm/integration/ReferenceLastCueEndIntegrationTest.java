package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V30 on PostgreSQL: {@code opensubtitles_reference.last_cue_end_ms} is a nullable BIGINT that an older row (written
 * before the column) reads as null, and a reference stores and reads back the figure.
 */
@Transactional
class ReferenceLastCueEndIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @Autowired
    private JdbcTemplate jdbc;

    private OpenSubtitlesReference reference(Film film, long fileId, Long lastCueEndMs) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId("tt0000001");
        reference.setOsFileId(fileId);
        reference.setCuesText("1\n00:00:01,000 --> 00:00:02,000\nHello\n\n");
        reference.setCueCount(1);
        reference.setLastCueEndMs(lastCueEndMs);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    @Test
    void aReferenceStoresTheLastCueEndAndARowWithoutOneReadsAsNull() {
        Film film = films.saveAndFlush(film(null, "Taken", 2008, NOW));
        OpenSubtitlesReference kept = reference(film, 5012, 7_321_000L);
        OpenSubtitlesReference older = reference(film, 5013, null);

        assertThat(references.findById(kept.getId())).get().extracting(OpenSubtitlesReference::getLastCueEndMs)
            .isEqualTo(7_321_000L);
        assertThat(references.findById(older.getId())).get().extracting(OpenSubtitlesReference::getLastCueEndMs)
            .isNull();
        assertThat(jdbc.queryForObject("SELECT last_cue_end_ms FROM opensubtitles_reference WHERE id = ?",
            Long.class, kept.getId())).isEqualTo(7_321_000L);
    }
}
