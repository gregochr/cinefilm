package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.FilmDtos.ForcedSubtitleDto;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedChoice.Step;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which forced subtitle an edition plays, worked out from the real repositories on H2: the owner's files, downloads,
 * the checks and timing fits that still describe them, and the embedded track. A failed file's replacement does not
 * take its failure away (so the choice never flips back and forth).
 */
class ForcedResolverTest extends ForcedDownloadTestBase {

    private Film film() {
        return searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.WEB"));
    }

    @Test
    void anOwnerFileNothingHasCheckedIsUncheckedAndPlays() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        Resolution resolution = resolve(film);

        assertThat(resolution.decision().step()).isEqualTo(Step.UNCHECKED_FILE);
        assertThat(resolution.fileAssessment().trust()).isEqualTo(Trust.UNCHECKED);
        assertThat(forced.forEditions(film.getEditions()).get(edition(film).getId()).source()).isEqualTo("SIDECAR");
    }

    @Test
    void aCurrentAgreeingCheckProvesTheFile() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "AGREES", reference(film, 5011));

        Resolution resolution = resolve(film);

        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_FILE);
        assertThat(resolution.fileAssessment().proof()).isEqualTo(Proof.AGREES);
        assertThat(resolution.fileCheck().verdict()).isEqualTo("AGREES");
        assertThat(resolution.fileReferenceFileId()).isEqualTo(5011L);
    }

    @Test
    void aCheckForWhatTheFileWasBeforeANudgeProvesNothingAndFailsNothing() {
        Film film = film();
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        row.setOffsetMs(250);
        row.setUpdatedAt(LATER);

        Resolution resolution = resolve(film);

        assertThat(resolution.fileCheck()).isNull();
        assertThat(resolution.fileAssessment().trust()).isEqualTo(Trust.UNCHECKED);
        assertThat(resolution.decision().step()).isEqualTo(Step.UNCHECKED_FILE);
    }

    @Test
    void aCheckAgainstAReferenceFetchedForAnotherImdbIdCountsForNothing() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference old = reference(film, 5011);
        old.setImdbId("tt0000002");
        references.saveAndFlush(old);
        fileCheck(film, "CHECK", old);

        assertThat(resolve(film).fileAssessment().trust()).isEqualTo(Trust.UNCHECKED);
    }

    @Test
    void aFileThatFailedItsCheckKeepsPlayingWhenNothingBetterExists() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));

        Resolution resolution = resolve(film);

        assertThat(resolution.fileAssessment().trust()).isEqualTo(Trust.FAILED);
        assertThat(resolution.decision().step()).isEqualTo(Step.FAILED_FILE_KEPT);
        assertThat(forced.forEditions(film.getEditions())).containsKey(edition(film).getId());
    }

    @Test
    void aFailedFileIsReplacedByADownloadAndItsFailureIsKeptSoTheChoiceDoesNotFlip() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference other = reference(film, 5011);
        fileCheck(film, "CHECK", other);
        SubtitleSidecar download = download(film, 5012);

        Resolution replaced = resolve(film);
        ForcedSubtitleDto dto = forced.forEditions(film.getEditions()).get(edition(film).getId());

        assertThat(replaced.decision().step()).isEqualTo(Step.REPLACED_BY_DOWNLOAD);
        assertThat(dto.source()).isEqualTo("DOWNLOAD");
        assertThat(replaced.reasons().get(owner.getId())).startsWith("Replaced by an OpenSubtitles download");

        // The download's own second opinion arrives: it is proven, and the file's failure is still on record.
        downloadCheck(film, download, "AGREES", other);
        Resolution proven = resolve(film);

        assertThat(proven.decision().step()).isEqualTo(Step.PROVEN_DOWNLOAD);
        assertThat(proven.fileCheck().verdict()).isEqualTo("CHECK");
        assertThat(proven.fileAssessment().trust()).isEqualTo(Trust.FAILED);
        assertThat(proven.reasons().get(owner.getId())).isEqualTo("Replaced by an OpenSubtitles download that agrees");
        assertThat(proven.reasons().get(download.getId())).isEqualTo("Plays: it agrees with OpenSubtitles");
    }

    private long versionOf(Film film) {
        return forced.forEditions(film.getEditions()).get(edition(film).getId()).version();
    }

    @Test
    void switchingFromTheOwnersFileToADownloadChangesTheVersionEvenWithEqualTimestamps() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar download = download(film, 5012);
        owner.setUpdatedAt(NOW);
        download.setUpdatedAt(NOW);
        sidecars.saveAllAndFlush(List.of(owner, download));
        long file = versionOf(film);
        assertThat(file).isEqualTo(versionOf(film));

        fileCheck(film, "CHECK", reference(film, 5011));

        assertThat(resolve(film).decision().source()).isEqualTo(Source.DOWNLOAD);
        assertThat(versionOf(film)).isNotEqualTo(file);
    }

    @Test
    void anOffsetChangeChangesTheVersionAndTheSameChoiceKeepsIt() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);
        long before = versionOf(film);
        assertThat(versionOf(film)).isEqualTo(before);

        download.setOffsetMs(250);
        sidecars.saveAndFlush(download);

        assertThat(versionOf(film)).isNotEqualTo(before);
        assertThat(versionOf(film)).isBetween(0L, (1L << 48) - 1);
    }

    @Test
    void theOwnersVerificationOverridesAFailedCheck() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        download(film, 5012);
        owner.setOwnerVerifiedAt(NOW);
        owner.setVerifiedParts(String.valueOf(fileId(film, 1)));

        Resolution resolution = resolve(film);

        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_FILE);
        assertThat(resolution.fileAssessment().proof()).isEqualTo(Proof.VERIFIED);
        assertThat(resolution.decision().source()).isEqualTo(Source.FILE);
    }

    @Test
    void anAlignedTimingFitProvesAFileEvenWhenItsComparisonSaidCheck() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        owner.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList()));
        owner.setCueCount(STARTS.size());
        sidecars.saveAndFlush(owner);
        fileCheck(film, "CHECK", reference(film, 5011));
        fitted(film, owner, Verdict.ALIGNED);

        Resolution resolution = resolve(film);

        assertThat(resolution.fileAssessment().proof()).isEqualTo(Proof.ALIGNED);
        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_FILE);
        assertThat(resolution.alignments()).containsKey(owner.getId());
    }

    private SubtitleSidecar timedDownload(Film film, long osFileId) {
        SubtitleSidecar download = download(film, osFileId);
        download.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line"))
            .toList()));
        download.setCueCount(STARTS.size());
        return sidecars.saveAndFlush(download);
    }

    @Test
    void anAlignedDownloadIsProven() {
        Film film = film();
        SubtitleSidecar download = timedDownload(film, 5012);
        fitted(film, download, Verdict.ALIGNED);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads().get(0).assessment().proof()).isEqualTo(Proof.ALIGNED);
        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_DOWNLOAD);
    }

    @Test
    void aDownloadWhoseTimingCouldNotBeFittedIsStillUnchecked() {
        Film film = film();
        SubtitleSidecar download = timedDownload(film, 5012);
        fitted(film, download, Verdict.CHECK);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads().get(0).assessment().trust()).isEqualTo(Trust.UNCHECKED);
        assertThat(resolution.downloads().get(0).alignment().verdict()).isEqualTo("CHECK");
        assertThat(resolution.decision().step()).isEqualTo(Step.UNCHECKED_DOWNLOAD);
    }

    @Test
    void aFailedFileOfThreeHundredLinesWithNoReplacementPlaysNothing() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        owner.setCueCount(300);
        sidecars.saveAndFlush(owner);
        fileCheck(film, "CHECK", reference(film, 5011));

        Resolution resolution = resolve(film);

        assertThat(resolution.decision().step()).isEqualTo(Step.FAILED_FILE_DROPPED);
        assertThat(forced.forEditions(film.getEditions())).isEmpty();
        assertThat(forced.playableEditionIds(film.getEditions())).isEmpty();
        assertThat(forced.served(film.getEditions())).isEmpty();
    }

    @Test
    void theEmbeddedForcedTrackBeatsAnUncheckedFileAndIsNotServedFromHere() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        embeddedForced(film);

        Resolution resolution = resolve(film);

        assertThat(resolution.embedded()).isTrue();
        assertThat(resolution.decision().step()).isEqualTo(Step.EMBEDDED);
        assertThat(forced.forEditions(film.getEditions())).isEmpty();
        assertThat(forced.playableEditionIds(film.getEditions())).containsExactly(edition(film).getId());
    }

    @Test
    void aDownloadForAnotherImdbIdIsIgnoredAndSaysWhy() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);
        download.setImdbId("tt0000009");
        sidecars.saveAndFlush(download);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads()).isEmpty();
        assertThat(resolution.decision().step()).isEqualTo(Step.NONE);
        assertThat(resolution.reasons().get(download.getId()))
            .isEqualTo("Not used: it was fetched for another IMDb id");
    }

    @Test
    void aDownloadThatFollowedItsFileIntoAnotherEditionDoesNotCountThere() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);
        download.setPath("opensubtitles:" + (edition(film).getId() + 999) + ":5012");
        sidecars.saveAndFlush(download);

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads()).isEmpty();
        assertThat(resolution.reasons().get(download.getId())).isEqualTo("Not used: it was made for another edition");
    }

    @Test
    void aDownloadWithNoCuesOrThatIsMissingDoesNotCount() {
        Film film = film();
        SubtitleSidecar empty = download(film, 5012);
        empty.setCueCount(0);
        SubtitleSidecar gone = download(film, 5013);
        gone.setPresence(Presence.MISSING);
        sidecars.saveAllAndFlush(List.of(empty, gone));

        Resolution resolution = resolve(film);

        assertThat(resolution.downloads()).isEmpty();
        assertThat(resolution.decision().source()).isEqualTo(Source.NONE);
        assertThat(resolution.reasons().get(gone.getId())).isEqualTo("Not used: the download is missing");
    }

    @Test
    void twoOwnerFilesInConflictPlayNoFileButADownloadStillCan() {
        Film film = film();
        sidecar("Taken.en.forced.srt", fileId(film, 1), true);
        sidecar("Taken.forced.en.srt", fileId(film, 1), true);
        SubtitleSidecar download = download(film, 5012);

        Resolution resolution = resolve(film);

        assertThat(resolution.fileConflict()).isTrue();
        assertThat(resolution.fileAssessment()).isNull();
        assertThat(resolution.decision().step()).isEqualTo(Step.UNCHECKED_DOWNLOAD);
        assertThat(resolution.decision().downloadId()).isEqualTo(download.getId());
    }

    @Test
    void aSplitEditionsPartFilesAreJudgedAsOneStitchedPick() {
        Film film = searchedFilm("Towers", 2, result(7001, "Towers.2002"));
        sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        fileCheck(film, "AGREES", reference(film, 7001));

        Resolution resolution = resolve(film);

        assertThat(resolution.filePick().slots()).hasSize(2);
        assertThat(resolution.fileAssessment().proof()).isEqualTo(Proof.AGREES);
        assertThat(resolution.decision().step()).isEqualTo(Step.PROVEN_FILE);
    }

    @Test
    void aDownloadIsServedAsTheWholeEditionAndItsCuesAreWrittenAsWebVtt() {
        Film film = searchedFilm("Towers", 2, result(7001, "Towers.2002"));
        SubtitleSidecar download = download(film, 7001);
        download.setOffsetMs(500);
        sidecars.saveAndFlush(download);

        assertThat(forced.vtt(edition(film).getId()))
            .isEqualTo("WEBVTT\n\n00:00:10.500 --> 00:00:12.500\nHello\n\n00:00:20.500 --> 00:00:23.000\nWorld\n");
        assertThat(forced.served(film.getEditions()).get(edition(film).getId()).cues()).hasSize(2);
        assertThat(forced.forEditions(film.getEditions()).get(edition(film).getId()).source()).isEqualTo("DOWNLOAD");
    }

    @Test
    void editionsAreResolvedTogetherAndAnEditionWithNoFilesIsResolvedToNothing() {
        Film taken = film();
        Film towers = searchedFilm("Towers", 2, result(7001, "Towers.2002"));
        sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        download(towers, 7001);

        var both = resolver.resolve(List.of(edition(taken), edition(towers)));

        assertThat(both.get(edition(taken).getId()).reasons()).hasSize(1);
        assertThat(both.get(edition(towers).getId()).reasons()).hasSize(1);
        assertThat(both.get(edition(taken).getId()).decision().step()).isEqualTo(Step.UNCHECKED_FILE);
        assertThat(both.get(edition(towers).getId()).decision().step()).isEqualTo(Step.UNCHECKED_DOWNLOAD);
    }
}
