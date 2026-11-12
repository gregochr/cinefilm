package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SubtitleSidecar;
import gregochr.cinefilm.subtitle.SubtitleSidecarRepository;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The only automatic removal of a downloaded subtitle: when TMDB gives the film another IMDb id the download was for
 * another film. The owner's files, other films' downloads and a film whose id did not change are left alone.
 */
class LibraryIngestDownloadsTest extends ApiTestBase {

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private SubtitleSidecarRepository sidecars;

    @Autowired
    private EntityManager em;

    private static MovieDetails movie(String imdbId) {
        return new MovieDetails(949, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0, List.of(),
            List.of(), null, imdbId);
    }

    private Film filmWithFile(String title, String imdbId) {
        Film film = film(null, title, 1995, NOW);
        film.setImdbId(imdbId);
        Edition edition = edition(film, null, "Original", 170 * MIN, null);
        file(edition, title + ".m4v");
        return films.saveAndFlush(film);
    }

    private SubtitleSidecar row(Film film, String path, boolean download) {
        MediaFile media = film.getEditions().get(0).originalParts().get(0);
        SubtitleSidecar row = new SubtitleSidecar();
        row.setPath(path);
        row.setSizeBytes(10);
        row.setModifiedAt(NOW);
        row.setMediaFileId(media.getId());
        row.setCuesText("1\n00:00:10,000 --> 00:00:12,000\nHello\n\n");
        row.setCueCount(1);
        row.setParseReport("ok");
        row.setUpdatedAt(NOW);
        if (download) {
            row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
            row.setOpenSubtitlesFileId(5011L);
            row.setImdbId(film.getImdbId());
        }
        return sidecars.saveAndFlush(row);
    }

    @Test
    void aDifferentImdbIdRemovesTheFilmsDownloadsAndNothingElse() {
        Film heat = filmWithFile("Heat", "tt0000001");
        Film other = filmWithFile("Alien", "tt0078748");
        SubtitleSidecar download = row(heat, "opensubtitles:1:5011", true);
        SubtitleSidecar owners = row(heat, "Heat.en.forced.srt", false);
        SubtitleSidecar othersDownload = row(other, "opensubtitles:2:5011", true);
        em.clear();
        Film loaded = films.findById(heat.getId()).orElseThrow();

        ingest.applyMovie(loaded, movie("tt0113277"));
        em.flush();
        em.clear();

        assertThat(sidecars.findById(download.getId())).isEmpty();
        assertThat(sidecars.findById(owners.getId())).isPresent();
        assertThat(sidecars.findById(othersDownload.getId())).isPresent();
    }

    @Test
    void theSameImdbIdAndABlankOneKeepTheDownloads() {
        Film heat = filmWithFile("Heat", "tt0113277");
        SubtitleSidecar download = row(heat, "opensubtitles:1:5011", true);
        em.clear();

        for (String unchanged : new String[] {"tt0113277", null, "", "   "}) {
            Film loaded = films.findById(heat.getId()).orElseThrow();
            ingest.applyMovie(loaded, movie(unchanged));
            em.flush();
            em.clear();
            assertThat(sidecars.findById(download.getId())).as("imdb %s", unchanged).isPresent();
        }
    }

    @Test
    void aFilmNotSavedYetHasNoDownloadsToRemove() {
        Film fresh = film(null, "Heat", 1995, NOW);

        ingest.applyMovie(fresh, movie("tt0113277"));

        assertThat(fresh.getImdbId()).isEqualTo("tt0113277");
        assertThat(sidecars.count()).isZero();
    }
}
