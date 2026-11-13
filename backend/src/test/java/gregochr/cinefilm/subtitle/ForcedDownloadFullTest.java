package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Which editions are due a full English subtitle ({@link Kind#FULL}) and adding one, against the real repositories on
 * H2: the full need and the full candidates, the owner's file and an existing download, the per-kind cap, the order
 * against promotions, replacements and missing forced downloads, and the revalidation under the library lock.
 */
class ForcedDownloadFullTest extends ForcedDownloadTestBase {

    private static final String FULL_CUES = IntStream.rangeClosed(1, 300).mapToObj(i -> i + "\n00:"
        + String.format("%02d", i / 60) + ":" + String.format("%02d", i % 60) + ",000 --> 00:"
        + String.format("%02d", i / 60) + ":" + String.format("%02d", i % 60) + ",500\nLine " + i + "\n")
        .collect(Collectors.joining("\n")) + "\n";

    @Autowired
    private ForcedDownloadService service;

    @Autowired
    private LibraryLock lock;

    private static OpenSubtitlesResult hearingImpaired(long fileId, String release) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, release, 23.976, 10, true, true, null, null, 1);
    }

    /** A film whose owner said it needs full subtitles, with a full search that found these candidates. */
    private Film fullFilm(String title, OpenSubtitlesResult... results) {
        Film film = filmWithParts(title, 1, 60);
        film.setImdbId(IMDB);
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullSearchedAt(NOW);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(results)));
        return films.saveAndFlush(film);
    }

    private Film fullFilm(String title) {
        return fullFilm(title, result(5011, title + ".BluRay"), result(5012, title + ".WEB"),
            result(5013, title + ".DVD"), result(5014, title + ".Extended"));
    }

    private Film forcedFilm(String title) {
        return searchedFilm(title, 1, result(7011, title + ".BluRay"), result(7012, title + ".WEB"),
            result(7013, title + ".DVD"));
    }

    private SubtitleSidecar fullDownload(Film film, long osFileId) {
        SubtitleSidecar row = download(film, osFileId);
        row.setKind(SubtitleKind.FULL);
        return sidecars.saveAndFlush(row);
    }

    private void block(Film film, long osFileId, SubtitleKind kind) {
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setKind(kind);
        block.setEditionId(edition(film).getId());
        block.setImdbId(IMDB);
        block.setOsFileId(osFileId);
        block.setRemovedAt(NOW);
        blocks.saveAndFlush(block);
    }

    private void tried(Film film, long osFileId, SubtitleKind kind) {
        ForcedCheckCandidate row = new ForcedCheckCandidate();
        row.setKind(kind);
        row.setEditionId(edition(film).getId());
        row.setImdbId(IMDB);
        row.setOsFileId(osFileId);
        row.setTriedAt(NOW);
        ledger.saveAndFlush(row);
    }

    private DueDownload onlyDue() {
        List<DueDownload> due = service.due();
        assertThat(due).hasSize(1);
        return due.get(0);
    }

    private OpenSubtitlesReference fullReference(Film film, long fileId) {
        return reference(film, fileId, FULL_CUES, 300);
    }

    // --- which editions are due ---

    @Test
    void aFilmThatNeedsFullSubtitlesAndHasCandidatesIsDueAFullDownloadWithTheCandidatesInOrder() {
        Film film = fullFilm("Oldboy");

        DueDownload due = onlyDue();

        assertThat(due.kind()).isEqualTo(Kind.FULL);
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
    void anEditionsCutAndHearingImpairedFilesOrderTheFullCandidates() {
        Film film = fullFilm("Oldboy", hearingImpaired(5011, "Oldboy.Extended.SDH"), result(5012, "Oldboy.WEB"),
            result(5013, "Oldboy.Extended.BluRay"));

        assertThat(onlyDue().candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L,
            5011L);
        edition(film).setName("Extended Edition");
        assertThat(onlyDue().candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5013L, 5011L,
            5012L);
    }

    @Test
    void aFilmWhoseFullNeedIsNotNeededIsNotDue() {
        Film film = fullFilm("Oldboy");
        film.setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        films.saveAndFlush(film);
        Film unknown = fullFilm("Untouched");
        unknown.setFullNeedOwner(null);
        films.saveAndFlush(unknown);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aFilmWithoutUsableFullCandidatesIsNotDue() {
        Film none = fullFilm("None");
        none.setOsFullCandidates("[]");
        Film unsearched = fullFilm("Unsearched");
        unsearched.setOsFullCandidates(null);
        Film otherId = fullFilm("OtherId");
        otherId.setOsFullSearchedImdbId("tt0000002");
        Film multi = fullFilm("Multi", new OpenSubtitlesResult(1, 5011, "a", 23.976, 1, true, false, null, null, 2));
        Film legacy = fullFilm("Legacy");
        legacy.setOsFullSearchedImdbId(null);
        films.saveAllAndFlush(List.of(none, unsearched, otherId, multi, legacy));

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aForcedOnlySearchGivesNoFullDownloadEvenForAFilmThatNeedsFullSubtitles() {
        Film film = forcedFilm("Taken");
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        films.saveAndFlush(film);

        assertThat(service.due()).extracting(DueDownload::kind).containsExactly(Kind.MISSING);
    }

    @Test
    void aPresentOwnersFullFileMeansNothingIsDueButAMissingOneDoesNot() {
        Film film = fullFilm("Oldboy");
        SubtitleSidecar owner = fullSidecar("Oldboy.en.srt", fileId(film, 1), false);

        assertThat(service.due()).isEmpty();

        owner.setPresence(Presence.MISSING);
        sidecars.saveAndFlush(owner);
        assertThat(onlyDue().kind()).isEqualTo(Kind.FULL);
    }

    @Test
    void aFullFileForOnePartOfASplitEditionMeansNothingIsDue() {
        Film film = filmWithParts("Oldboy", 2, 60);
        film.setImdbId(IMDB);
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullSearchedAt(NOW);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "a"))));
        films.saveAndFlush(film);
        fullSidecar("Oldboy - pt2.en.srt", fileId(film, 2), false);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void anExistingFullDownloadMeansNothingIsDue() {
        Film film = fullFilm("Oldboy");
        fullDownload(film, 5011);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aForcedFileOrAForcedDownloadNeverSatisfiesTheFullDue() {
        Film film = fullFilm("Oldboy");
        sidecar("Oldboy.en.forced.srt", fileId(film, 1), false);
        download(film, 5011);

        DueDownload due = onlyDue();

        assertThat(due.kind()).isEqualTo(Kind.FULL);
        assertThat(due.remainingFetches()).isEqualTo(3);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5012L, 5013L,
            5014L);
    }

    @Test
    void aRemovedFullDownloadIsNeverOfferedAgainAndTheNextCandidateIsDueWithOneFetchLess() {
        Film film = fullFilm("Oldboy");
        block(film, 5011, SubtitleKind.FULL);

        DueDownload due = onlyDue();

        assertThat(due.remainingFetches()).isEqualTo(2);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L, 5014L);
    }

    @Test
    void theFullCapIsThreeAndOnlyFullBlocksCountAgainstIt() {
        Film film = fullFilm("Oldboy");
        block(film, 5011, SubtitleKind.FORCED);
        block(film, 5012, SubtitleKind.FORCED);
        block(film, 5013, SubtitleKind.FORCED);

        assertThat(onlyDue().remainingFetches()).isEqualTo(3);

        block(film, 5011, SubtitleKind.FULL);
        block(film, 5012, SubtitleKind.FULL);
        assertThat(onlyDue().remainingFetches()).isEqualTo(1);

        block(film, 5013, SubtitleKind.FULL);
        assertThat(service.due()).isEmpty();
    }

    @Test
    void forcedDownloadsAndBlocksDoNotCountAgainstTheFullCapAndFullOnesDoNotCountAgainstTheForcedCap() {
        Film both = forcedFilm("Taken");
        both.setImdbId(IMDB);
        both.setFullNeedOwner(FullNeedChoice.NEEDED);
        both.setOsFullSearchedImdbId(IMDB);
        both.setOsFullSearchedAt(NOW);
        both.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "a"), result(5012, "b"),
            result(5013, "c"))));
        films.saveAndFlush(both);
        block(both, 7011, SubtitleKind.FORCED);
        block(both, 5011, SubtitleKind.FULL);
        block(both, 5012, SubtitleKind.FULL);

        List<DueDownload> due = service.due();

        assertThat(due).extracting(DueDownload::kind).containsExactly(Kind.FULL, Kind.MISSING);
        assertThat(due.get(0).remainingFetches()).isEqualTo(1);
        assertThat(due.get(1).remainingFetches()).isEqualTo(2);
    }

    @Test
    void aFullFileThatWasComparedOrIsStoredAsNotFullIsLeftOut() {
        Film film = fullFilm("Oldboy");
        tried(film, 5011, SubtitleKind.FULL);
        tried(film, 5012, SubtitleKind.FORCED);
        // Two lines is not full subtitles: stored for good, never fetched again. 300 lines is a usable full file.
        reference(film, 5013);
        fullReference(film, 5014);

        assertThat(onlyDue().candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5014L);
    }

    @Test
    void fullDownloadsComeAfterPromotionsAndBeforeReplacementsAndMissingOnesWhateverTheFilmOrder() {
        Film missing = forcedFilm("Alpha");
        Film replacement = forcedFilm("Bravo");
        sidecar("Bravo.en.forced.srt", fileId(replacement, 1), false);
        fileCheck(replacement, "CHECK", reference(replacement, 7011));
        Film promoted = forcedFilm("Zulu");
        sidecar("Zulu.en.forced.srt", fileId(promoted, 1), false);
        fileCheck(promoted, "CHECK", reference(promoted, 7011));
        tried(promoted, 7011, SubtitleKind.FORCED);
        Film full = fullFilm("Yankee");

        List<DueDownload> due = service.due();

        assertThat(due).extracting(DueDownload::filmId).containsExactly(promoted.getId(), full.getId(),
            replacement.getId(), missing.getId());
        assertThat(due).extracting(DueDownload::kind).containsExactly(Kind.REPLACEMENT, Kind.FULL,
            Kind.REPLACEMENT, Kind.MISSING);
        assertThat(due.get(0).promotion()).isNotNull();
        assertThat(due.get(1).promotion()).isNull();
    }

    // --- adding ---

    @Test
    void addingAFullReferenceStoresAFullWholeEditionDownloadAndPutsItOnTheFullLedgerOnly() {
        Film film = fullFilm("Oldboy");
        OpenSubtitlesReference reference = fullReference(film, 5011);

        AddOutcome outcome = service.add(onlyDue(), reference, LATER);

        assertThat(outcome).isEqualTo(AddOutcome.ADDED);
        SubtitleSidecar row = sidecars.findAll().get(0);
        assertThat(row.getKind()).isEqualTo(SubtitleKind.FULL);
        assertThat(row.getOrigin()).isEqualTo(SubtitleSidecar.Origin.OPENSUBTITLES);
        assertThat(row.getPath()).isEqualTo("opensubtitles:" + edition(film).getId() + ":5011");
        assertThat(row.getMediaFileId()).isEqualTo(fileId(film, 1));
        assertThat(row.isWholeEdition()).isTrue();
        assertThat(row.getCueCount()).isEqualTo(300);
        assertThat(row.getImdbId()).isEqualTo(IMDB);
        assertThat(row.getDownloadedAt()).isEqualTo(LATER);
        assertThat(ledger.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition(film).getId(), IMDB, 5011,
            SubtitleKind.FULL)).isTrue();
        assertThat(ledger.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition(film).getId(), IMDB, 5011,
            SubtitleKind.FORCED)).isFalse();
        assertThat(sidecars.findByMediaFileIdInAndKind(List.of(fileId(film, 1)), SubtitleKind.FORCED)).isEmpty();
        assertThat(service.due()).isEmpty();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aReferenceWithFewerThanThreeHundredLinesIsNotFullSubtitlesAndIsDropped() {
        Film film = fullFilm("Oldboy");
        DueDownload due = onlyDue();
        OpenSubtitlesReference short299 = reference(film, 5011, FULL_CUES, 299);
        OpenSubtitlesReference exactly300 = fullReference(film, 5012);
        OpenSubtitlesReference unusable = reference(film, 5013, "", 0);

        assertThat(service.add(due, short299, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(service.add(due, unusable, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).isEmpty();
        assertThat(service.add(due, exactly300, LATER)).isEqualTo(AddOutcome.ADDED);
    }

    @Test
    void anAddIsDroppedWhenTheOwnerSaidNotNeededTheImdbIdChangedOrTheFileLeftTheCandidates() {
        Film film = fullFilm("Oldboy");
        OpenSubtitlesReference reference = fullReference(film, 5011);
        DueDownload due = onlyDue();

        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5099, "Other"))));
        films.saveAndFlush(film);
        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);

        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "Oldboy.BluRay"))));
        film.setOsFullSearchedImdbId("tt0000002");
        films.saveAndFlush(film);
        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);

        film.setOsFullSearchedImdbId(IMDB);
        film.setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        films.saveAndFlush(film);
        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);

        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setImdbId("tt0000002");
        films.saveAndFlush(film);
        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).isEmpty();
    }

    @Test
    void anAddIsDroppedWhenAnOwnersFileOrADownloadAppearedOrTheFileWasRemoved() {
        Film film = fullFilm("Oldboy");
        OpenSubtitlesReference first = fullReference(film, 5011);
        OpenSubtitlesReference second = fullReference(film, 5012);
        DueDownload due = onlyDue();

        block(film, 5011, SubtitleKind.FULL);
        assertThat(service.add(due, first, LATER)).isEqualTo(AddOutcome.DROPPED);

        SubtitleSidecar owner = fullSidecar("Oldboy.en.srt", fileId(film, 1), false);
        assertThat(service.add(due, second, LATER)).isEqualTo(AddOutcome.DROPPED);

        sidecars.delete(owner);
        sidecars.flush();
        fullDownload(film, 5013);
        assertThat(service.add(due, second, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).hasSize(1);
    }

    @Test
    void anAddIsDroppedOnceTheFullCapIsUsedUpEvenForAFileNotBlocked() {
        Film film = fullFilm("Oldboy");
        OpenSubtitlesReference reference = fullReference(film, 5014);
        DueDownload due = onlyDue();
        block(film, 5011, SubtitleKind.FULL);
        block(film, 5012, SubtitleKind.FULL);
        block(film, 5013, SubtitleKind.FULL);

        assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddWaitsWhileAScanHoldsTheLibraryLock() {
        Film film = fullFilm("Oldboy");
        OpenSubtitlesReference reference = fullReference(film, 5011);
        DueDownload due = onlyDue();
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            assertThat(service.add(due, reference, LATER)).isEqualTo(AddOutcome.BUSY);
        } finally {
            lock.release();
        }

        assertThat(sidecars.findAll()).isEmpty();
    }

    @Test
    void aFullDownloadThatIsNotPresentDoesNotSuppressARetryButAPresentOneDoes() {
        Film film = fullFilm("Oldboy");
        SubtitleSidecar download = fullDownload(film, 5011);
        assertThat(service.due()).isEmpty();

        download.setPresence(Presence.MISSING);
        sidecars.saveAndFlush(download);

        DueDownload due = onlyDue();
        assertThat(due.remainingFetches()).isEqualTo(2);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L, 5014L);
    }

    @Test
    void removalsNeverMakeAnEditionDueForeverOnceTheCapIsReached() {
        Film film = fullFilm("Oldboy");
        block(film, 5011, SubtitleKind.FULL);
        block(film, 5012, SubtitleKind.FULL);
        assertThat(onlyDue().candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5013L, 5014L);

        block(film, 5013, SubtitleKind.FULL);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void anEditionWithNoFilmFileIsNeverDueAndAnAddForItIsDropped() {
        Film film = filmWithParts("Oldboy", 0, 60);
        film.setImdbId(IMDB);
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullSearchedAt(NOW);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "a"))));
        films.saveAndFlush(film);

        assertThat(service.due()).isEmpty();
        DueDownload made = new DueDownload(Kind.FULL, film.getId(), edition(film).getId(), IMDB, "Original", null,
            List.of(result(5011, "a")), 3, null);
        assertThat(service.add(made, fullReference(film, 5011), LATER)).isEqualTo(AddOutcome.DROPPED);
    }

    @Test
    void anAddForAnEditionThatIsGoneIsDropped() {
        Film film = fullFilm("Oldboy");
        OpenSubtitlesReference reference = fullReference(film, 5011);
        DueDownload gone = new DueDownload(Kind.FULL, film.getId(), 987_654L, IMDB, "Original", null,
            List.of(result(5011, "Oldboy.BluRay")), 3, null);

        assertThat(service.add(gone, reference, LATER)).isEqualTo(AddOutcome.DROPPED);
        assertThat(sidecars.findAll()).isEmpty();
    }

    @Test
    void anOwnersFileWithNoLinesOrOnAnotherFilmDoesNotMakeTheEditionUndue() {
        Film film = fullFilm("Oldboy");
        SubtitleSidecar empty = fullSidecar("Oldboy.en.srt", fileId(film, 1), false);
        empty.setCueCount(0);
        sidecars.saveAndFlush(empty);
        Film other = fullFilm("Martyrs");
        fullSidecar("Martyrs.en.srt", fileId(other, 1), false);

        assertThat(service.due()).extracting(DueDownload::filmId).containsExactly(film.getId());
    }

    @Test
    void aDownloadOfAnotherFilmOrMadeForAnotherEditionDoesNotCountAsThisEditionsDownload() {
        Film film = fullFilm("Oldboy");
        Film other = fullFilm("Martyrs");
        fullDownload(other, 5011);
        SubtitleSidecar moved = fullDownload(film, 5012);
        // An edit moved the file into this edition: it was made for another one, so it does not count here.
        moved.setPath("opensubtitles:987654:5012");
        sidecars.saveAndFlush(moved);

        assertThat(service.due()).extracting(DueDownload::filmId).containsExactly(film.getId());
    }

    @Test
    void everyCandidateAlreadyComparedLeavesNothingDueAndOtherEditionsOrImdbIdsOnTheLedgerCountForNothing() {
        Film film = fullFilm("Oldboy", result(5011, "a"), result(5012, "b"));
        Film other = fullFilm("Martyrs", result(5011, "a"));
        tried(other, 5011, SubtitleKind.FULL);
        ForcedCheckCandidate elsewhere = new ForcedCheckCandidate();
        elsewhere.setKind(SubtitleKind.FULL);
        elsewhere.setEditionId(edition(film).getId());
        elsewhere.setImdbId("tt0000002");
        elsewhere.setOsFileId(5011);
        elsewhere.setTriedAt(NOW);
        ledger.saveAndFlush(elsewhere);

        assertThat(service.due()).extracting(DueDownload::filmId).containsExactly(film.getId());
        assertThat(service.due().get(0).candidates()).extracting(OpenSubtitlesResult::fileId)
            .containsExactly(5011L, 5012L);

        tried(film, 5011, SubtitleKind.FULL);
        tried(film, 5012, SubtitleKind.FULL);
        assertThat(service.due()).isEmpty();
    }
}
