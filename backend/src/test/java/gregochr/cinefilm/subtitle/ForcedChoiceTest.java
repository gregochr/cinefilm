package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.ForcedChoice.Decision;
import gregochr.cinefilm.subtitle.ForcedChoice.DownloadSide;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.ForcedChoice.Input;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedChoice.Step;
import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which forced subtitle an edition plays: every row of the order, its ties and the full-subtitles exception. */
class ForcedChoiceTest {

    private static final Assessment AGREES = new Assessment(Trust.PROVEN, Proof.AGREES);
    private static final Assessment ALIGNED = new Assessment(Trust.PROVEN, Proof.ALIGNED);
    private static final Assessment VERIFIED = new Assessment(Trust.PROVEN, Proof.VERIFIED);
    private static final Assessment UNCHECKED = new Assessment(Trust.UNCHECKED, Proof.NONE);
    private static final Assessment FAILED = new Assessment(Trust.FAILED, Proof.NONE);

    private static FileSide file(Assessment assessment) {
        return new FileSide(assessment, 20);
    }

    private static DownloadSide download(long id, Assessment assessment) {
        return new DownloadSide(id, assessment);
    }

    private static Decision decide(FileSide file, boolean embedded, DownloadSide... downloads) {
        return ForcedChoice.decide(new Input(file, List.of(downloads), embedded));
    }

    @Test
    void aProvenOwnerFileWinsOverEverythingElse() {
        Decision decision = decide(file(AGREES), true, download(1, AGREES), download(2, ALIGNED));

        assertThat(decision.step()).isEqualTo(Step.PROVEN_FILE);
        assertThat(decision.source()).isEqualTo(Source.FILE);
        assertThat(decision.downloadId()).isNull();
        assertThat(decision.fileReason()).isEqualTo("Plays: it agrees with OpenSubtitles");
        assertThat(decision.downloadReasons().get(1L)).isEqualTo("Not used: your file is proven and plays first");
        assertThat(decision.downloadReasons().get(2L)).isEqualTo("Not used: your file is proven and plays first");
    }

    @Test
    void theFileIsReplacedOnlyWhenADownloadPlaysInPlaceOfAFileThatFailed() {
        assertThat(decide(file(FAILED), false, download(1, UNCHECKED)).fileReplaced()).isTrue();
        assertThat(decide(file(FAILED), false, download(1, AGREES)).fileReplaced()).isTrue();
        assertThat(decide(file(FAILED), false).fileReplaced()).isFalse();
        assertThat(decide(file(FAILED), true, download(1, UNCHECKED)).fileReplaced()).isFalse();
        assertThat(decide(file(UNCHECKED), false, download(1, AGREES)).fileReplaced()).isFalse();
        assertThat(decide(file(AGREES), false, download(1, AGREES)).fileReplaced()).isFalse();
        assertThat(decide(null, false, download(1, UNCHECKED)).fileReplaced()).isFalse();
    }

    @Test
    void eachWayOfProvingAFileIsSaidInPlainWords() {
        assertThat(decide(file(VERIFIED), false).fileReason()).isEqualTo("Plays: you checked it");
        assertThat(decide(file(AGREES), false).fileReason()).isEqualTo("Plays: it agrees with OpenSubtitles");
        assertThat(decide(file(ALIGNED), false).fileReason())
            .isEqualTo("Plays: its timing lines up with the film's own English track");
    }

    @Test
    void theOwnersVerificationKeepsAFileThatFailedEveryCheckPlayingEvenWithFullSubtitlesInIt() {
        // SidecarTrust.assess turns a CHECK into PROVEN when the owner verified: the function sees only the result.
        Assessment verified = SidecarTrust.assess(true, "CHECK", false);

        Decision decision = ForcedChoice.decide(new Input(new FileSide(verified, 2_431), List.of(), true));

        assertThat(decision.step()).isEqualTo(Step.PROVEN_FILE);
        assertThat(decision.source()).isEqualTo(Source.FILE);
    }

    @Test
    void aProvenDownloadBeatsTheEmbeddedTrackAndAnUnprovenFile() {
        Decision unchecked = decide(file(UNCHECKED), true, download(7, AGREES));
        Decision failed = decide(file(FAILED), true, download(7, ALIGNED));

        assertThat(unchecked.step()).isEqualTo(Step.PROVEN_DOWNLOAD);
        assertThat(unchecked.source()).isEqualTo(Source.DOWNLOAD);
        assertThat(unchecked.downloadId()).isEqualTo(7L);
        assertThat(unchecked.fileReason()).isEqualTo("Not used: a proven OpenSubtitles download plays instead");
        assertThat(unchecked.downloadReasons().get(7L)).isEqualTo("Plays: it agrees with OpenSubtitles");
        assertThat(failed.step()).isEqualTo(Step.PROVEN_DOWNLOAD);
        assertThat(failed.fileReason()).isEqualTo(
            "Replaced by an OpenSubtitles download that is in time with the film's own English track");
        assertThat(failed.downloadReasons().get(7L))
            .isEqualTo("Plays: its timing lines up with the film's own English track");
    }

    @Test
    void aFailedFileIsReplacedByAProvenDownloadThatAgreesInTheWordsOfTheBrief() {
        Decision decision = decide(file(FAILED), false, download(7, AGREES));

        assertThat(decision.fileReason()).isEqualTo("Replaced by an OpenSubtitles download that agrees");
    }

    @Test
    void aFailedFileReplacedByADownloadTheOwnerVerifiedSaysSo() {
        Decision decision = decide(file(FAILED), false, download(7, VERIFIED));

        assertThat(decision.fileReason()).isEqualTo("Replaced by an OpenSubtitles download that you checked");
    }

    @Test
    void aDownloadTheOwnerVerifiedIsProvenToo() {
        Decision decision = decide(null, false, download(7, VERIFIED));

        assertThat(decision.step()).isEqualTo(Step.PROVEN_DOWNLOAD);
        assertThat(decision.fileReason()).isNull();
        assertThat(decision.downloadReasons().get(7L)).isEqualTo("Plays: you checked it");
    }

    @Test
    void theEmbeddedForcedTrackBeatsAnUnprovenFileAndAnUncheckedDownloadAndCountsAsProven() {
        for (Assessment unproven : List.of(UNCHECKED, FAILED)) {
            Decision decision = decide(file(unproven), true, download(3, UNCHECKED));

            assertThat(decision.step()).isEqualTo(Step.EMBEDDED);
            assertThat(decision.source()).isEqualTo(Source.EMBEDDED);
            assertThat(decision.downloadId()).isNull();
            assertThat(decision.fileReason()).isEqualTo("Not used: the film's own forced subtitle track plays instead");
            assertThat(decision.downloadReasons().get(3L))
                .isEqualTo("Not used: the film's own forced subtitle track plays instead");
        }
        assertThat(decide(null, true).step()).isEqualTo(Step.EMBEDDED);
    }

    @Test
    void anUncheckedOwnerFileBeatsAnUncheckedDownload() {
        Decision decision = decide(file(UNCHECKED), false, download(3, UNCHECKED));

        assertThat(decision.step()).isEqualTo(Step.UNCHECKED_FILE);
        assertThat(decision.source()).isEqualTo(Source.FILE);
        assertThat(decision.fileReason()).isEqualTo("Plays: nothing says it is wrong, but it is not proven yet");
        assertThat(decision.downloadReasons().get(3L))
            .isEqualTo("Not used: your file plays first: nothing says it is wrong");
        assertThat(decision.hasLiveDownload()).isTrue();
    }

    @Test
    void aFailedFileIsReplacedByADownloadThatHasNotFailed() {
        Decision decision = decide(file(FAILED), false, download(3, UNCHECKED));

        assertThat(decision.step()).isEqualTo(Step.REPLACED_BY_DOWNLOAD);
        assertThat(decision.source()).isEqualTo(Source.DOWNLOAD);
        assertThat(decision.downloadId()).isEqualTo(3L);
        assertThat(decision.fileReason()).isEqualTo(
            "Replaced by an OpenSubtitles download that has not been checked yet; this file failed its checks");
        assertThat(decision.downloadReasons().get(3L))
            .isEqualTo("Plays instead of the owner's file, which failed its checks; not checked yet");
    }

    @Test
    void aFailedFileWithNoDownloadKeepsPlayingBecauseNothingBetterExists() {
        Decision decision = decide(file(FAILED), false);

        assertThat(decision.step()).isEqualTo(Step.FAILED_FILE_KEPT);
        assertThat(decision.source()).isEqualTo(Source.FILE);
        assertThat(decision.fileReason())
            .isEqualTo("Plays only because nothing better exists: it failed its checks (needs checking)");
        assertThat(decision.hasLiveDownload()).isFalse();
    }

    @Test
    void aFailedFileWithOnlyAFailedDownloadStillKeepsPlaying() {
        Decision decision = decide(file(FAILED), false, download(3, FAILED));

        assertThat(decision.step()).isEqualTo(Step.FAILED_FILE_KEPT);
        assertThat(decision.downloadReasons().get(3L)).isEqualTo("Not used: it failed its checks");
        assertThat(decision.hasLiveDownload()).isFalse();
    }

    @Test
    void aFailedFileOfThreeHundredLinesOrMoreIsDroppedEvenWithNoReplacement() {
        Decision dropped = ForcedChoice.decide(new Input(new FileSide(FAILED, 300), List.of(), false));
        Decision kept = ForcedChoice.decide(new Input(new FileSide(FAILED, 299), List.of(), false));

        assertThat(ForcedChoice.FULL_LINES).isEqualTo(300);
        assertThat(dropped.step()).isEqualTo(Step.FAILED_FILE_DROPPED);
        assertThat(dropped.source()).isEqualTo(Source.NONE);
        assertThat(dropped.fileReason()).isEqualTo(
            "Not used: it failed its checks and looks like full subtitles (300 lines), not forced ones");
        assertThat(kept.step()).isEqualTo(Step.FAILED_FILE_KEPT);
    }

    @Test
    void theFullSubtitlesExceptionDoesNotApplyToAFileNothingHasFailed() {
        Decision unchecked = ForcedChoice.decide(new Input(new FileSide(UNCHECKED, 2_431), List.of(), false));
        Decision proven = ForcedChoice.decide(new Input(new FileSide(AGREES, 2_431), List.of(), false));

        assertThat(unchecked.step()).isEqualTo(Step.UNCHECKED_FILE);
        assertThat(proven.step()).isEqualTo(Step.PROVEN_FILE);
    }

    @Test
    void aFullFailedFileIsStillReplacedByADownload() {
        Decision decision = ForcedChoice.decide(new Input(new FileSide(FAILED, 2_431),
            List.of(download(3, UNCHECKED)), false));

        assertThat(decision.step()).isEqualTo(Step.REPLACED_BY_DOWNLOAD);
    }

    @Test
    void withNoOwnerFileAnUncheckedDownloadPlays() {
        Decision decision = decide(null, false, download(3, UNCHECKED));

        assertThat(decision.step()).isEqualTo(Step.UNCHECKED_DOWNLOAD);
        assertThat(decision.source()).isEqualTo(Source.DOWNLOAD);
        assertThat(decision.downloadId()).isEqualTo(3L);
        assertThat(decision.fileReason()).isNull();
        assertThat(decision.downloadReasons().get(3L)).isEqualTo("Plays: nothing else is available; not checked yet");
    }

    @Test
    void aFailedDownloadNeverPlaysAndNothingPlaysWhenThereIsNothingElse() {
        Decision failed = decide(null, false, download(3, FAILED));
        Decision nothing = decide(null, false);

        assertThat(failed.step()).isEqualTo(Step.NONE);
        assertThat(failed.source()).isEqualTo(Source.NONE);
        assertThat(failed.hasLiveDownload()).isFalse();
        assertThat(nothing.step()).isEqualTo(Step.NONE);
        assertThat(nothing.downloadReasons()).isEmpty();
        assertThat(nothing.fileReason()).isNull();
    }

    @Test
    void aProvenDownloadBeatsAnUncheckedOneWhateverTheirIds() {
        Decision decision = decide(null, false, download(1, UNCHECKED), download(9, AGREES));

        assertThat(decision.downloadId()).isEqualTo(9L);
        assertThat(decision.downloadReasons().get(1L)).isEqualTo("Not used: another OpenSubtitles download plays");
    }

    @Test
    void tiesBetweenDownloadsOfOneTrustLevelGoToTheLowestId() {
        Decision proven = decide(null, false, download(8, AGREES), download(4, ALIGNED), download(6, VERIFIED));
        Decision unchecked = decide(null, false, download(8, UNCHECKED), download(5, UNCHECKED));

        assertThat(proven.downloadId()).isEqualTo(4L);
        assertThat(unchecked.downloadId()).isEqualTo(5L);
        assertThat(unchecked.downloadReasons().get(8L)).isEqualTo("Not used: another OpenSubtitles download plays");
    }

    @Test
    void aLiveDownloadIsOneThatHasNotFailedWhateverPlays() {
        assertThat(decide(file(AGREES), false, download(1, UNCHECKED)).hasLiveDownload()).isTrue();
        assertThat(decide(file(AGREES), false, download(1, FAILED)).hasLiveDownload()).isFalse();
        assertThat(decide(file(AGREES), false).hasLiveDownload()).isFalse();
    }

    @Test
    void aFailedDownloadIsNeverReplacedByTheNextStepUntilItIsGone() {
        // The unchecked file plays; a failed download is only listed.
        Decision decision = decide(file(UNCHECKED), false, download(2, FAILED));

        assertThat(decision.step()).isEqualTo(Step.UNCHECKED_FILE);
        assertThat(decision.downloadReasons().get(2L)).isEqualTo("Not used: it failed its checks");
    }
}
