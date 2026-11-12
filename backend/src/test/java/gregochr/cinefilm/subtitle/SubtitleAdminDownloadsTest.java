package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Admin and downloads against the real services on H2: what the list says about a download and why each subtitle plays
 * or not, "Looks right" as "use my file", and removing a download (blocked for that edition for good, the next
 * candidate looked for).
 */
@RecordApplicationEvents
class SubtitleAdminDownloadsTest extends ForcedDownloadTestBase {

    @Autowired
    private ForcedDownloadService downloads;

    @Autowired
    private LibraryLock lock;

    @Autowired
    private ApplicationEvents events;

    private Film film() {
        return searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.WEB"),
            result(5013, "Taken.2008.DVD"));
    }

    private SidecarDto listed(SubtitleSidecar row) {
        return admin.list().stream().filter(d -> d.id().equals(row.getId())).findFirst().orElseThrow();
    }

    @Test
    void aDownloadIsListedWithItsOriginReleaseFileIdAndWhyItPlays() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);

        SidecarDto dto = listed(download);

        assertThat(dto.origin()).isEqualTo("OPENSUBTITLES");
        assertThat(dto.release()).isEqualTo("Release.5012");
        assertThat(dto.openSubtitlesFileId()).isEqualTo(5012L);
        assertThat(dto.path()).isEqualTo("opensubtitles:" + edition(film).getId() + ":5012");
        assertThat(dto.state()).isEqualTo("matched");
        assertThat(dto.whole()).isTrue();
        assertThat(dto.part()).isNull();
        assertThat(dto.parts()).isEqualTo(1);
        assertThat(dto.ownerAttached()).isFalse();
        assertThat(dto.served()).isTrue();
        assertThat(dto.servedReason()).isEqualTo("Plays: nothing else is available; not checked yet");
        assertThat(dto.filmTitle()).isEqualTo("Taken");
    }

    @Test
    void anOwnerFileHasNoReleaseOrFileIdAndAFailedOneIsReplacedAndSaysSoWhileItsCheckStaysVisible() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        SubtitleSidecar download = download(film, 5012);

        SidecarDto file = listed(owner);
        SidecarDto replacement = listed(download);

        assertThat(file.origin()).isEqualTo("FILE");
        assertThat(file.release()).isNull();
        assertThat(file.openSubtitlesFileId()).isNull();
        assertThat(file.served()).isFalse();
        assertThat(file.replacedByDownload()).isTrue();
        assertThat(replacement.replacedByDownload()).isFalse();
        assertThat(file.check().verdict()).isEqualTo("CHECK");
        assertThat(file.servedReason()).isEqualTo(
            "Replaced by an OpenSubtitles download that has not been checked yet; this file failed its checks");
        assertThat(replacement.served()).isTrue();
        assertThat(replacement.check()).isNull();
        assertThat(replacement.servedReason())
            .isEqualTo("Plays instead of the owner's file, which failed its checks; not checked yet");
    }

    @Test
    void aDownloadsOwnComparisonIsShownOnTheDownloadAndProvesIt() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        downloadCheck(film, download, "AGREES", reference(film, 5012));

        SidecarDto dto = listed(download);

        assertThat(dto.check().verdict()).isEqualTo("AGREES");
        assertThat(SidecarTrust.isProven(dto)).isTrue();
        assertThat(dto.servedReason()).isEqualTo("Plays: it agrees with OpenSubtitles");
    }

    @Test
    void anOwnerFileTheEditionDoesNotPlayBecauseAProvenDownloadDoesSaysSo() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar download = download(film, 5011);
        downloadCheck(film, download, "AGREES", reference(film, 5012));

        assertThat(listed(owner).served()).isFalse();
        assertThat(listed(owner).replacedByDownload()).isFalse();
        assertThat(listed(owner).servedReason()).isEqualTo("Not used: a proven OpenSubtitles download plays instead");
    }

    @Test
    void lookingRightOnTheOwnersFileIsUseMyFileAndItPlaysFirstAgain() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        SubtitleSidecar download = download(film, 5012);
        downloadCheck(film, download, "AGREES", reference(film, 5013));
        assertThat(listed(download).served()).isTrue();

        SidecarDto verified = admin.verify(owner.getId());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.served()).isTrue();
        assertThat(verified.servedReason()).isEqualTo("Plays: you checked it");
        assertThat(listed(download).served()).isFalse();
        assertThat(listed(download).servedReason()).isEqualTo("Not used: your file is proven and plays first");
        assertThat(forced.forEditions(film.getEditions()).get(edition(film).getId()).source()).isEqualTo("SIDECAR");
    }

    @Test
    void theOwnerCanVerifyANudgeAndUnverifyADownloadToo() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);

        assertThat(admin.verify(download.getId()).servedReason()).isEqualTo("Plays: you checked it");
        SidecarDto nudged = admin.offset(download.getId(), new OffsetRequest(500));
        assertThat(nudged.offsetMs()).isEqualTo(500);
        assertThat(nudged.offsetSource()).isEqualTo("OWNER");
        assertThat(nudged.verified()).isFalse();
        assertThat(admin.unverify(download.getId()).verified()).isFalse();
    }

    @Test
    void aDownloadCannotBeAttachedOrGivenBackToMatching() {
        Film film = film();
        SubtitleSidecar download = download(film, 5012);

        assertThatThrownBy(() -> admin.attach(download.getId(), new AttachRequest(edition(film).getId(), null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("A downloaded subtitle belongs to its edition and cannot be attached");
        assertThatThrownBy(() -> admin.detach(download.getId())).isInstanceOf(BadRequestException.class)
            .hasMessage("A downloaded subtitle belongs to its edition and cannot be attached");
        assertThat(sidecars.findById(download.getId()).orElseThrow().getMediaFileId()).isEqualTo(fileId(film, 1));
    }

    // --- removal ---

    @Test
    void removingADownloadDeletesItWithItsChecksBlocksThatFileForTheEditionAndAsksForTheNextCandidate() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        downloadCheck(film, download, "CHECK", reference(film, 5012));

        admin.remove(download.getId());
        // The delete reaches the database (and its foreign keys cascade) when the transaction flushes.
        sidecars.flush();

        assertThat(sidecars.findById(download.getId())).isEmpty();
        assertThat(downloadChecks.findAll()).isEmpty();
        assertThat(blocks.findAll()).extracting(ForcedDownloadBlock::getEditionId, ForcedDownloadBlock::getImdbId,
            ForcedDownloadBlock::getOsFileId).containsExactly(tuple(edition(film).getId(), IMDB, 5011L));
        assertThat(events.stream(SubtitleDownloadRemoved.class).toList())
            .containsExactly(new SubtitleDownloadRemoved(edition(film).getId()));
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aRemovedFileIsNeverOfferedAgainAndTheNextCandidateIsDue() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);

        admin.remove(download.getId());
        List<DueDownload> due = downloads.due();

        assertThat(due).hasSize(1);
        assertThat(due.get(0).candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L);
        assertThat(due.get(0).remainingFetches()).isEqualTo(2);
    }

    @Test
    void removingTheSameFileTwiceForAnEditionLeavesOneBlock() {
        Film film = film();
        SubtitleSidecar first = download(film, 5011);
        admin.remove(first.getId());
        sidecars.flush();
        SubtitleSidecar again = download(film, 5011);
        admin.remove(again.getId());

        assertThat(blocks.findAll()).hasSize(1);
    }

    @Test
    void anOwnersFileCannotBeRemovedAndAnUnknownRowIsNotFound() {
        Film film = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        assertThatThrownBy(() -> admin.remove(owner.getId())).isInstanceOf(BadRequestException.class)
            .hasMessage("Only a subtitle downloaded from OpenSubtitles can be removed");
        assertThatThrownBy(() -> admin.remove(owner.getId() + 999)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
        assertThat(sidecars.findById(owner.getId())).isPresent();
        assertThat(events.stream(SubtitleDownloadRemoved.class).count()).isZero();
    }

    @Test
    void aRemovalIsRefusedWhileAScanRunsAndLeavesEverythingAsItWas() {
        Film film = film();
        SubtitleSidecar download = download(film, 5011);
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            assertThatThrownBy(() -> admin.remove(download.getId())).isInstanceOf(ConflictException.class)
                .hasMessage("A scan is running");
        } finally {
            lock.release();
        }

        assertThat(sidecars.findById(download.getId())).isPresent();
        assertThat(blocks.findAll()).isEmpty();
    }

    @Test
    void removingADownloadWhoseReferenceIsStoredKeepsTheStoredReferenceSoItIsNeverFetchedAgain() {
        Film film = film();
        OpenSubtitlesReference stored = reference(film, 5011);
        SubtitleSidecar download = download(film, 5011);

        admin.remove(download.getId());

        assertThat(references.findById(stored.getId())).isPresent();
    }
}
