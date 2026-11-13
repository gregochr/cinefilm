package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.FullFitService.Job;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Which full downloads the measurement looks at and what it logs about them, against the real repositories on H2. */
class FullFitServiceTest extends ForcedDownloadTestBase {

    @Autowired
    private FullFitService service;

    @Autowired
    private CreditsDetectionRepository credits;

    private Film fullFilm(String title, int parts) {
        Film film = filmWithParts(title, parts, 60);
        film.setImdbId(IMDB);
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullSearchedAt(NOW);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "a"), result(5012, "b"),
            result(5013, "c"))));
        return films.saveAndFlush(film);
    }

    private SubtitleSidecar fullDownload(Film film, long osFileId) {
        SubtitleSidecar row = download(film, osFileId);
        row.setKind(SubtitleKind.FULL);
        return sidecars.saveAndFlush(row);
    }

    private void creditsAt(MediaFile file, CreditsVerdict verdict, Long offsetMs) {
        CreditsDetection row = new CreditsDetection();
        row.setMediaFileId(file.getId());
        row.setFingerprint("fp");
        row.setVerdict(verdict);
        row.setOffsetMs(offsetMs);
        row.setReasons("");
        row.setCheckedAt(NOW);
        credits.saveAndFlush(row);
    }

    @Test
    void aLiveFullDownloadIsAJobWithItsOtherCandidatesTheStoredReferencesTheRuntimeAndNoCredits() {
        Film film = fullFilm("Oldboy", 1);
        SubtitleSidecar download = fullDownload(film, 5012);
        reference(film, 5013);

        List<Job> jobs = service.due(Set.of());

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.filmId()).isEqualTo(film.getId());
            assertThat(job.editionId()).isEqualTo(edition(film).getId());
            assertThat(job.imdbId()).isEqualTo(IMDB);
            assertThat(job.sidecarId()).isEqualTo(download.getId());
            assertThat(job.downloadFileId()).isEqualTo(5012L);
            assertThat(job.download()).hasSize(2);
            assertThat(job.runtimeMs()).isEqualTo(60 * MIN);
            assertThat(job.creditsStartMs()).isNull();
            assertThat(job.others()).extracting(c -> c.fileId()).containsExactly(5011L, 5013L);
            assertThat(job.stored()).containsOnlyKeys(5013L);
        });
    }

    @Test
    void aDownloadAlreadyMeasuredByThisProcessIsLeftOut() {
        Film film = fullFilm("Oldboy", 1);
        SubtitleSidecar download = fullDownload(film, 5012);

        assertThat(service.due(Set.of(download.getId()))).isEmpty();
        assertThat(service.due(Set.of(download.getId() + 1))).hasSize(1);
    }

    @Test
    void forcedDownloadsOwnersFilesAndDownloadsOfFilmsWithoutFullCandidatesAreNotJobs() {
        Film film = fullFilm("Oldboy", 1);
        download(film, 5012);
        fullSidecar("Oldboy.en.srt", fileId(film, 1), false);
        Film noCandidates = fullFilm("Untouched", 1);
        noCandidates.setOsFullCandidates("[]");
        films.saveAndFlush(noCandidates);
        fullDownload(noCandidates, 5012);

        assertThat(service.due(Set.of())).isEmpty();
    }

    @Test
    void aDownloadForAFilmTheOwnerMarkedNotNeededIsNotMeasured() {
        Film film = fullFilm("Oldboy", 1);
        fullDownload(film, 5012);
        film.setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        films.saveAndFlush(film);

        assertThat(service.due(Set.of())).isEmpty();
    }

    @Test
    void creditsDetectionsCreditsStartIsPlacedOnTheEditionsTimeline() {
        Film film = fullFilm("Towers", 2);
        fullDownload(film, 5012);
        // The last part starts at 60 minutes: credits 3 minutes into it are 63 minutes into the edition.
        creditsAt(edition(film).originalParts().get(1), CreditsVerdict.FOUND, 3 * MIN);

        assertThat(service.due(Set.of())).singleElement().satisfies(job -> {
            assertThat(job.creditsStartMs()).isEqualTo(63 * MIN);
            assertThat(job.runtimeMs()).isEqualTo(120 * MIN);
        });
    }

    @Test
    void onlyAFoundCreditsStartWithAnOffsetCounts() {
        Film film = fullFilm("Oldboy", 1);
        fullDownload(film, 5012);
        MediaFile file = edition(film).originalParts().get(0);

        creditsAt(file, CreditsVerdict.UNSURE, 50 * MIN);
        assertThat(service.due(Set.of()).get(0).creditsStartMs()).isNull();

        creditsAt(file, CreditsVerdict.NOT_FOUND, null);
        assertThat(service.due(Set.of()).get(0).creditsStartMs()).isNull();

        creditsAt(file, CreditsVerdict.FOUND, 55 * MIN);
        assertThat(service.due(Set.of()).get(0).creditsStartMs()).isEqualTo(55 * MIN);
    }

    @Test
    void withNoFullDownloadAnywhereThereIsNothingToMeasure() {
        fullFilm("Oldboy", 1);

        assertThat(service.due(Set.of())).isEmpty();
    }

    @Test
    void aMultiDiscCandidateIsNeverTheSecondFile() {
        Film film = fullFilm("Oldboy", 1);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "a"),
            new OpenSubtitlesResult(1, 5014, "split", 23.976, 1, true, false, null, null, 2), result(5012, "b"))));
        films.saveAndFlush(film);
        fullDownload(film, 5012);

        assertThat(service.due(Set.of()).get(0).others()).extracting(c -> c.fileId()).containsExactly(5011L);
    }

    @Test
    void aFoundVerdictWithNoOffsetGivesNoCreditsStart() {
        Film film = fullFilm("Oldboy", 1);
        fullDownload(film, 5012);
        creditsAt(edition(film).originalParts().get(0), CreditsVerdict.FOUND, null);

        assertThat(service.due(Set.of()).get(0).creditsStartMs()).isNull();
    }

    @Test
    void creditsOfASplitEditionWhosePartLengthIsUnknownCannotBePlacedOnTheTimeline() {
        Film film = fullFilm("Towers", 2);
        fullDownload(film, 5012);
        MediaFile first = edition(film).originalParts().get(0);
        first.setDurationMs(null);
        mediaFiles.saveAndFlush(first);
        creditsAt(edition(film).originalParts().get(1), CreditsVerdict.FOUND, 3 * MIN);

        assertThat(service.due(Set.of()).get(0).creditsStartMs()).isNull();
    }
}
