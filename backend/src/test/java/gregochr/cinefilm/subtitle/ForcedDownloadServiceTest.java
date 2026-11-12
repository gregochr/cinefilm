package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Which editions are due a download and which OpenSubtitles file each gets, and adding a download, against the real
 * repositories on H2: promote a stored reference first, skip files already used up, the cap, the priority order, and
 * the revalidation under the library lock.
 */
class ForcedDownloadServiceTest extends ForcedDownloadTestBase {

    @Autowired
    private ForcedDownloadService service;

    @Autowired
    private LibraryLock lock;

    private Film film(String title) {
        return searchedFilm(title, 1, result(5011, title + ".BluRay"), result(5012, title + ".WEB"),
            result(5013, title + ".DVD"), result(5014, title + ".Extended"));
    }

    private DueDownload onlyDue() {
        List<DueDownload> due = service.due();
        assertThat(due).hasSize(1);
        return due.get(0);
    }

    // --- which editions are due ---

    @Test
    void aNeededFilmWithNothingThatPlaysIsDueAMissingDownloadWithItsCandidatesInOrder() {
        Film film = film("Taken");

        DueDownload due = onlyDue();

        assertThat(due.kind()).isEqualTo(Kind.MISSING);
        assertThat(due.filmId()).isEqualTo(film.getId());
        assertThat(due.editionId()).isEqualTo(edition(film).getId());
        assertThat(due.imdbId()).isEqualTo(IMDB);
        assertThat(due.promotion()).isNull();
        assertThat(due.fileVersion()).isNull();
        assertThat(due.remainingFetches()).isEqualTo(3);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5012L, 5013L,
            5014L);
    }

    @Test
    void anEditionsCutOrdersTheCandidatesForIt() {
        Film film = film("Taken");
        edition(film).setName("Extended Edition");

        assertThat(onlyDue().candidates().get(0).fileId()).isEqualTo(5014L);
    }

    @Test
    void aFilmWhoseNeedIsNotShownIsNotDue() {
        Film film = film("Taken");
        film.setForcedNeedOwner(ForcedNeedChoice.NOT_NEEDED);
        films.saveAndFlush(film);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aFilmWithNoCandidatesOrOnlyMultiPartOnesOrLegacyOnesIsNotDue() {
        Film none = film("None");
        none.setOsCandidates("[]");
        Film multi = film("Multi");
        multi.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(
            new OpenSubtitlesResult(1, 5020, "r", 23.976, 1, true, false, null, null, 2))));
        Film legacy = film("Legacy");
        legacy.setOsCandidates(null);
        films.saveAllAndFlush(List.of(none, multi, legacy));

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aFilmNobodySearchedForItsCurrentImdbIdIsNotDue() {
        Film film = film("Taken");
        film.setOsSearchedImdbId("tt0000002");
        films.saveAndFlush(film);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void anEditionNothingIsWrongWithIsNotDue() {
        Film unchecked = film("Unchecked");
        sidecar("Unchecked.en.forced.srt", fileId(unchecked, 1), false);
        Film proven = film("Proven");
        sidecar("Proven.en.forced.srt", fileId(proven, 1), false);
        fileCheck(proven, "AGREES", reference(proven, 5011));
        Film embedded = film("Embedded");
        embeddedForced(embedded);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void anEditionWithAnEmbeddedTrackAndAFailedFileIsNotDueEither() {
        Film film = film("Taken");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        embeddedForced(film);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void twoOwnerFilesInConflictAreTheOwnersToSortOutSoNothingIsDownloaded() {
        Film film = film("Taken");
        sidecar("Taken.en.forced.srt", fileId(film, 1), true);
        sidecar("Taken.forced.en.srt", fileId(film, 1), true);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void anEditionWithADownloadThatHasNotFailedIsNotDueAndOneWhoseDownloadFailedIs() {
        Film film = film("Taken");
        SubtitleSidecar download = download(film, 5011);
        assertThat(service.due()).isEmpty();

        downloadCheck(film, download, "CHECK", reference(film, 5012));

        DueDownload due = onlyDue();
        assertThat(due.kind()).isEqualTo(Kind.MISSING);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L, 5014L);
        assertThat(due.remainingFetches()).isEqualTo(2);
    }

    // --- replacements and promotion ---

    @Test
    void anOwnerFileThatFailedItsChecksIsDueAReplacementWithTheFilesVersionToPinTheAddTo() {
        Film film = film("Taken");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));

        DueDownload due = onlyDue();

        assertThat(due.kind()).isEqualTo(Kind.REPLACEMENT);
        assertThat(due.fileVersion()).isEqualTo(fileVersion(film));
    }

    @Test
    void aStoredReferenceThatFitsTheOwnersFileIsPromotedBeforeAnythingIsFetched() {
        Film film = film("Taken");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference kept = reference(film, 5011);
        fileCheck(film, "CHECK", kept);
        ledger(film, 5011);

        DueDownload due = onlyDue();

        assertThat(due.promotion()).isNotNull();
        assertThat(due.promotion().getOsFileId()).isEqualTo(5011L);
        // The file the edition was compared with is not offered again for fetching.
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L, 5014L);
    }

    @Test
    void aStoredReferenceIsNotPromotedForAMissingSubtitle() {
        Film film = film("Taken");
        reference(film, 5011);
        ledger(film, 5011);

        assertThat(onlyDue().promotion()).isNull();
    }

    @Test
    void aStoredReferenceThatIsFullSubtitlesIsNotPromotedAndNotFetchedAgain() {
        Film film = film("Taken");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference full = reference(film, 5011, SubtitleTestBase.CUES, 300);
        fileCheck(film, "CHECK", full);
        ledger(film, 5011);

        DueDownload due = onlyDue();

        assertThat(due.promotion()).isNull();
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L, 5014L);
    }

    // --- files already used up ---

    @Test
    void filesBlockedAlreadyDownloadedComparedOrKnownToBeUnusableAreSkipped() {
        Film film = film("Taken");
        SubtitleSidecar failed = download(film, 5011);
        downloadCheck(film, failed, "CHECK", reference(film, 5012));
        ledger(film, 5012);
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(edition(film).getId());
        block.setImdbId(IMDB);
        block.setOsFileId(5013);
        block.setRemovedAt(NOW);
        blocks.saveAndFlush(block);

        DueDownload due = onlyDue();

        // 5011 is a download, 5012 was compared with, 5013 was removed: only 5014 is left, and one fetch (3 - 2).
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5014L);
        assertThat(due.remainingFetches()).isEqualTo(1);
    }

    @Test
    void aStoredUnusableReferenceIsNeverFetchedAgain() {
        Film film = film("Taken");
        OpenSubtitlesReference unusable = reference(film, 5011, "", 0);
        unusable.setFailedDownloads(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS);
        references.saveAndFlush(unusable);

        assertThat(onlyDue().candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L,
            5014L);
    }

    @Test
    void downloadsMadeForAnotherImdbIdDoNotCountAgainstTheCap() {
        Film film = film("Taken");
        for (long fileId = 5011; fileId <= 5013; fileId++) {
            SubtitleSidecar old = download(film, fileId);
            old.setImdbId("tt0000009");
            sidecars.saveAndFlush(old);
        }

        assertThat(onlyDue().remainingFetches()).isEqualTo(3);
    }

    @Test
    void anEditionThatHasHadThreeFilesIsNeverGivenAFourth() {
        Film film = film("Taken");
        SubtitleSidecar first = download(film, 5011);
        downloadCheck(film, first, "CHECK", reference(film, 5014));
        for (long fileId = 5012; fileId <= 5013; fileId++) {
            ForcedDownloadBlock block = new ForcedDownloadBlock();
            block.setEditionId(edition(film).getId());
            block.setImdbId(IMDB);
            block.setOsFileId(fileId);
            block.setRemovedAt(NOW);
            blocks.saveAndFlush(block);
        }

        assertThat(service.due()).isEmpty();
    }

    @Test
    void missingOnesComeBeforeReplacementsWhateverTheFilmOrder() {
        Film replacement = film("Alpha");
        sidecar("Alpha.en.forced.srt", fileId(replacement, 1), false);
        fileCheck(replacement, "CHECK", reference(replacement, 5011));
        Film missing = film("Zulu");

        List<DueDownload> due = service.due();

        assertThat(due).extracting(DueDownload::kind).containsExactly(Kind.MISSING, Kind.REPLACEMENT);
        assertThat(due).extracting(DueDownload::filmId).containsExactly(missing.getId(), replacement.getId());
    }

    // --- adding ---

    @Test
    void addingStoresTheReferenceAsAWholeEditionDownloadOnPartOneAndPutsItOnTheLedger() {
        Film film = searchedFilm("Towers", 2, result(7001, "Towers.2002"));
        OpenSubtitlesReference reference = reference(film, 7001);
        DueDownload due = onlyDue();

        AddOutcome outcome = service.add(due, reference, LATER);

        assertThat(outcome).isEqualTo(AddOutcome.ADDED);
        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getOrigin()).isEqualTo(SubtitleSidecar.Origin.OPENSUBTITLES);
        assertThat(row.getPath()).isEqualTo("opensubtitles:" + edition(film).getId() + ":7001");
        assertThat(row.getMediaFileId()).isEqualTo(fileId(film, 1));
        assertThat(row.isWholeEdition()).isTrue();
        assertThat(row.isOwnerAttached()).isFalse();
        assertThat(row.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(row.getCuesText()).isEqualTo(CUES);
        assertThat(row.getCueCount()).isEqualTo(2);
        assertThat(row.getLastEndMs()).isEqualTo(22_500);
        assertThat(row.getOpenSubtitlesFileId()).isEqualTo(7001L);
        assertThat(row.getRelease()).isEqualTo("Release.7001");
        assertThat(row.getImdbId()).isEqualTo(IMDB);
        assertThat(row.getDownloadedAt()).isEqualTo(LATER);
        assertThat(row.getUpdatedAt()).isEqualTo(LATER);
        assertThat(row.getOffsetMs()).isZero();
        assertThat(ledger.existsByEditionIdAndImdbIdAndOsFileId(edition(film).getId(), IMDB, 7001)).isTrue();
        // It is now waiting to be proven: nothing more is due.
        assertThat(service.due()).isEmpty();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void addingAPromotionLeavesTheFileOnTheLedgerOnceAndDownloadsNothing() {
        Film film = film("Taken");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference kept = reference(film, 5011);
        fileCheck(film, "CHECK", kept);
        ledger(film, 5011);
        DueDownload due = onlyDue();

        assertThat(service.add(due, due.promotion(), LATER)).isEqualTo(AddOutcome.ADDED);

        assertThat(ledger.findByEditionIdIn(List.of(edition(film).getId()))).hasSize(1);
        assertThat(sidecars.findAll()).filteredOn(SubtitleSidecar::isDownload).hasSize(1);
    }

    @Test
    void anAddIsDroppedWhenTheFilmsImdbIdChangedWhileTheFileWasFetched() {
        Film film = film("Taken");
        OpenSubtitlesReference reference = reference(film, 5011);
        DueDownload due = onlyDue();
        film.setImdbId("tt0000002");
        films.saveAndFlush(film);

        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).isEmpty();
    }

    @Test
    void anAddIsDroppedForAFileThatIsNoLongerOneOfTheFilmsSingleFileCandidates() {
        Film film = film("Taken");
        OpenSubtitlesReference reference = reference(film, 5011);
        DueDownload due = onlyDue();
        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(result(5099, "Other"))));
        films.saveAndFlush(film);

        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddIsDroppedForAReferenceFetchedForAnotherImdbIdOrThatIsNotPromotable() {
        Film film = film("Taken");
        DueDownload due = onlyDue();
        OpenSubtitlesReference other = reference(film, 5011);
        other.setImdbId("tt0000002");
        OpenSubtitlesReference full = reference(film, 5012, SubtitleTestBase.CUES, 300);
        OpenSubtitlesReference unusable = reference(film, 5013, "", 0);

        assertThat(service.add(due, other, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(service.add(due, full, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(service.add(due, unusable, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).isEmpty();
    }

    @Test
    void anAddIsDroppedForAFileTheOwnerRemovedOrThatIsAlreadyADownload() {
        Film film = film("Taken");
        OpenSubtitlesReference removed = reference(film, 5011);
        OpenSubtitlesReference made = reference(film, 5012);
        DueDownload due = onlyDue();
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(edition(film).getId());
        block.setImdbId(IMDB);
        block.setOsFileId(5011);
        block.setRemovedAt(NOW);
        blocks.saveAndFlush(block);
        SubtitleSidecar existing = download(film, 5012);
        downloadCheck(film, existing, "CHECK", reference(film, 5013));

        assertThat(service.add(due, removed, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(service.add(due, made, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddIsDroppedWhenTheEditionStoppedBeingDueForTheSameReason() {
        Film film = film("Taken");
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        OpenSubtitlesReference candidate = reference(film, 5012);
        DueDownload due = onlyDue();

        // The owner says "Looks right" while the file is being fetched: the file is proven and nothing replaces it.
        owner.setOwnerVerifiedAt(NOW);
        owner.setVerifiedParts(String.valueOf(fileId(film, 1)));
        sidecars.saveAndFlush(owner);

        assertThat(service.add(due, candidate, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).filteredOn(SubtitleSidecar::isDownload).isEmpty();
    }

    @Test
    void aReplacementIsDroppedWhenTheOwnersFileChangedMeanwhile() {
        Film film = film("Taken");
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        fileCheck(film, "CHECK", reference(film, 5011));
        OpenSubtitlesReference candidate = reference(film, 5012);
        DueDownload due = onlyDue();

        // A nudge makes the failed check stale: the file is no longer known to have failed.
        owner.setOffsetMs(250);
        sidecars.saveAndFlush(owner);

        assertThat(service.add(due, candidate, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddIsDroppedForAnEditionThatIsGone() {
        Film film = film("Taken");
        OpenSubtitlesReference reference = reference(film, 5011);
        DueDownload due = onlyDue();
        DueDownload ghost = new DueDownload(due.kind(), due.filmId(), due.editionId() + 999, due.imdbId(),
            due.editionName(), null, due.candidates(), 3, null);
        DueDownload noFilm = new DueDownload(due.kind(), due.filmId() + 999, due.editionId(), due.imdbId(),
            due.editionName(), null, due.candidates(), 3, null);

        assertThat(service.add(ghost, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(service.add(noFilm, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddIsRefusedWhileAScanOrAnEditHoldsTheLibraryLockAndTheLockIsGivenBack() {
        Film film = film("Taken");
        OpenSubtitlesReference reference = reference(film, 5011);
        DueDownload due = onlyDue();

        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.BUSY);
            assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        } finally {
            lock.release();
        }

        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.ADDED);
        assertThat(lock.holder()).isNull();
    }

    private void ledger(Film film, long fileId) {
        ForcedCheckCandidate row = new ForcedCheckCandidate();
        row.setEditionId(edition(film).getId());
        row.setImdbId(IMDB);
        row.setOsFileId(fileId);
        row.setTriedAt(NOW);
        ledger.saveAndFlush(row);
    }
}
