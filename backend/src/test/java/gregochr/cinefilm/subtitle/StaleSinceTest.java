package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedChoice.Step;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code stale_since} on the three verdict tables, against H2: the entities read and write it, and a row that carries
 * it still decides what plays and what is proven exactly as it would without it.
 */
class StaleSinceTest extends ForcedDownloadTestBase {

    private static final Instant STALE = NOW.minusSeconds(60);

    @Autowired
    private ForcedSubtitleAlignmentRepository alignments;

    @Autowired
    private EntityManager entityManager;

    private Film film() {
        return searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.WEB"));
    }

    private OpenSubtitlesReference other(Film film) {
        return reference(film, 5011);
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void theThreeVerdictRowsWriteAndReadStaleSinceAndDefaultToNull() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar download = download(film, 5012);
        OpenSubtitlesReference reference = other(film);
        ForcedSubtitleCheck file = fileCheck(film, "AGREES", reference);
        ForcedDownloadCheck downloadRow = downloadCheck(film, download, "AGREES", reference);
        fitted(film, owner, Verdict.ALIGNED);
        ForcedSubtitleAlignment fit = alignments.findBySidecarId(owner.getId()).orElseThrow();
        assertThat(file.getStaleSince()).isNull();
        assertThat(downloadRow.getStaleSince()).isNull();
        assertThat(fit.getStaleSince()).isNull();

        file.setStaleSince(STALE);
        downloadRow.setStaleSince(STALE);
        fit.setStaleSince(STALE);
        checks.save(file);
        downloadChecks.save(downloadRow);
        alignments.save(fit);
        reload();

        assertThat(checks.findById(file.getId()).orElseThrow().getStaleSince()).isEqualTo(STALE);
        assertThat(downloadChecks.findById(downloadRow.getId()).orElseThrow().getStaleSince()).isEqualTo(STALE);
        assertThat(alignments.findById(fit.getId()).orElseThrow().getStaleSince()).isEqualTo(STALE);
        assertThat(checks.findByEditionIdInAndStaleSinceIsNotNull(List.of(file.getEditionId())))
            .extracting(ForcedSubtitleCheck::getId).containsExactly(file.getId());
        assertThat(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(download.getId())))
            .extracting(ForcedDownloadCheck::getId).containsExactly(downloadRow.getId());
    }

    @Test
    void theStaleOnlyQueriesLeaveOutRowsThatAreNotStale() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar download = download(film, 5012);
        OpenSubtitlesReference reference = other(film);
        ForcedSubtitleCheck file = fileCheck(film, "AGREES", reference);
        downloadCheck(film, download, "AGREES", reference);
        reload();

        assertThat(checks.findByEditionIdInAndStaleSinceIsNotNull(List.of(file.getEditionId()))).isEmpty();
        assertThat(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(download.getId())))
            .isEmpty();
    }

    @Test
    void aStaleAgreeingFileCheckStillProvesTheFile() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        ForcedSubtitleCheck row = fileCheck(film, "AGREES", other(film));
        row.setStaleSince(STALE);
        checks.saveAndFlush(row);

        Resolution resolution = resolve(film);

        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_FILE);
        assertThat(resolution.fileAssessment().proof()).isEqualTo(Proof.AGREES);
        assertThat(resolution.fileCheck().verdict()).isEqualTo("AGREES");
    }

    @Test
    void aStaleFailedDownloadCheckKeepsTheDownloadOffScreen() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);
        ForcedDownloadCheck row = downloadCheck(film, download, "CHECK", other(film));
        row.setStaleSince(STALE);
        downloadChecks.saveAndFlush(row);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads().get(0).assessment().trust()).isEqualTo(Trust.FAILED);
        assertThat(resolution.decision().source()).isNotEqualTo(Source.DOWNLOAD);
        assertThat(resolution.downloads().get(0).check().verdict()).isEqualTo("CHECK");
    }

    @Test
    void aStaleAgreeingDownloadCheckKeepsTheDownloadProven() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);
        ForcedDownloadCheck row = downloadCheck(film, download, "AGREES", other(film));
        row.setStaleSince(STALE);
        downloadChecks.saveAndFlush(row);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads().get(0).assessment().proof()).isEqualTo(Proof.AGREES);
        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_DOWNLOAD);
    }

    @Test
    void aStaleAlignedFitStillProvesTheDownload() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);
        download.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line"))
            .toList()));
        download.setCueCount(STARTS.size());
        sidecars.saveAndFlush(download);
        fitted(film, download, Verdict.ALIGNED);
        ForcedSubtitleAlignment fit = alignments.findBySidecarId(download.getId()).orElseThrow();
        fit.setStaleSince(STALE);
        alignments.saveAndFlush(fit);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads().get(0).assessment().proof()).isEqualTo(Proof.ALIGNED);
        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_DOWNLOAD);
    }
}
