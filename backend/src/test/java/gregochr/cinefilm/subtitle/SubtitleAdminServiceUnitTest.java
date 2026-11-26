package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.RetimeRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/** The Admin subtitle service's rules, with the repositories faked. */
@ExtendWith(MockitoExtension.class)
class SubtitleAdminServiceUnitTest {

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private MediaFileRepository files;

    @Mock
    private EditionRepository editions;

    @Mock
    private SidecarSync sync;

    @Mock
    private ForcedResolver resolver;

    @Mock
    private ForcedDownloadBlockRepository blocks;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private FilmRates filmRates;

    @Mock
    private SubtitleRetimeService retimer;

    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private SubtitleAdminService service;
    private SubtitleSidecar row;
    private Edition split;

    @BeforeEach
    void setUp() {
        service = new SubtitleAdminService(sidecars, files, editions, sync, resolver, blocks, CLOCK, lock,
            transactions, events, filmRates, retimer, "off");
        row = new SubtitleSidecar();
        row.setId(5L);
        row.setPath("A/Other.en.forced.srt");
        row.setCuesText("1\n00:00:10,000 --> 00:00:12,000\nHello\n\n");
        row.setCueCount(1);
        row.setUpdatedAt(NOW.minusSeconds(60));
        split = edition(film(1L, "Towers", 2002, NOW), 40L, "Original", 120 * MIN, null);
        for (int number = 1; number <= 2; number++) {
            MediaFile part = file(split, "Towers - pt" + number + ".m4v");
            part.setId(10L * number);
            part.setPartNumber(number);
        }
    }

    private void listAndFind() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).thenReturn(List.of(row));
    }

    @Test
    void anUnknownFileIsNotFoundForEveryAction() {
        when(sidecars.findById(9L)).thenReturn(Optional.empty());

        for (Runnable action : List.<Runnable>of(() -> service.cues(9L), () -> service.detach(9L),
            () -> service.verify(9L), () -> service.unverify(9L), () -> service.offset(9L, new OffsetRequest(1)),
            () -> service.attach(9L, new AttachRequest(40L, null)))) {
            assertThatThrownBy(action::run).isInstanceOf(NotFoundException.class).hasMessage("No such subtitle");
        }
    }

    @Test
    void attachingWholeToASplitEditionStoresItAgainstPartOneAsWholeEdition() {
        listAndFind();
        when(editions.findById(40L)).thenReturn(Optional.of(split));
        when(files.findAllById(Set.of(10L))).thenReturn(List.of(split.originalParts().get(0)));

        SidecarDto dto = service.attach(5L, new AttachRequest(40L, null));

        assertThat(row.getMediaFileId()).isEqualTo(10L);
        assertThat(row.isWholeEdition()).isTrue();
        assertThat(row.isOwnerAttached()).isTrue();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(dto.whole()).isTrue();
    }

    @Test
    void attachingToAPartStoresThatPartsFileAndRefusesAnotherNumber() {
        listAndFind();
        when(editions.findById(40L)).thenReturn(Optional.of(split));
        when(files.findAllById(Set.of(20L))).thenReturn(List.of(split.originalParts().get(1)));

        service.attach(5L, new AttachRequest(40L, 2));

        assertThat(row.getMediaFileId()).isEqualTo(20L);
        assertThat(row.isWholeEdition()).isFalse();
        assertThatThrownBy(() -> service.attach(5L, new AttachRequest(40L, 3)))
            .isInstanceOf(BadRequestException.class).hasMessage("That edition has no part 3");
    }

    @Test
    void attachNeedsAnEditionThatExistsAndHasFiles() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        when(editions.findById(41L)).thenReturn(Optional.empty());
        Edition bare = edition(film(2L, "Bare", 2000, NOW), 42L, "Original", MIN, null);
        when(editions.findById(42L)).thenReturn(Optional.of(bare));

        assertThatThrownBy(() -> service.attach(5L, null)).isInstanceOf(BadRequestException.class)
            .hasMessage("Pick an edition");
        assertThatThrownBy(() -> service.attach(5L, new AttachRequest(null, null)))
            .isInstanceOf(BadRequestException.class).hasMessage("Pick an edition");
        assertThatThrownBy(() -> service.attach(5L, new AttachRequest(41L, null)))
            .isInstanceOf(NotFoundException.class).hasMessage("No such edition");
        assertThatThrownBy(() -> service.attach(5L, new AttachRequest(42L, null)))
            .isInstanceOf(BadRequestException.class).hasMessage("That edition has no film file");
    }

    @Test
    void theOffsetIsBoundedByTenMinutesAndNeedsAValue() {
        listAndFind();

        assertThat(service.offset(5L, new OffsetRequest(600_000)).offsetMs()).isEqualTo(600_000);
        assertThat(service.offset(5L, new OffsetRequest(-600_000)).offsetMs()).isEqualTo(-600_000);
        for (Integer bad : new Integer[] {600_001, -600_001, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> service.offset(5L, new OffsetRequest(bad)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("The offset must be within 10 minutes either way");
        }
        assertThatThrownBy(() -> service.offset(5L, null)).isInstanceOf(BadRequestException.class)
            .hasMessage("An offset is required");
        assertThatThrownBy(() -> service.offset(5L, new OffsetRequest(null)))
            .isInstanceOf(BadRequestException.class).hasMessage("An offset is required");
        assertThat(row.getOffsetMs()).isEqualTo(-600_000);
    }

    @Test
    void verifyNeedsAFilmAndRecordsThePartsUnverifyClearsThem() {
        listAndFind();
        assertThatThrownBy(() -> service.verify(5L)).isInstanceOf(BadRequestException.class)
            .hasMessage("Attach the subtitle to a film first");

        row.setMediaFileId(10L);
        when(files.findById(10L)).thenReturn(Optional.of(split.originalParts().get(0)));
        when(files.findAllById(Set.of(10L))).thenReturn(List.of(split.originalParts().get(0)));
        service.verify(5L);

        assertThat(row.getOwnerVerifiedAt()).isEqualTo(NOW);
        assertThat(row.getVerifiedParts()).isEqualTo("10,20");

        service.unverify(5L);
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
    }

    @Test
    void aNudgeKeepsTheOwnersCheckAndAProvenFileStaysProvenAndServed() {
        listAndFind();
        row.setMediaFileId(10L);
        row.setOwnerVerifiedAt(NOW.minusSeconds(500));
        row.setVerifiedParts("10,20");
        List<Long> parts = List.of(10L, 20L);
        ForcedChoice.Decision before = decideFor(parts);

        service.offset(5L, new OffsetRequest(-1250));
        service.offset(5L, new OffsetRequest(0));

        assertThat(row.getOwnerVerifiedAt()).isEqualTo(NOW.minusSeconds(500));
        assertThat(row.isVerifiedFor(parts)).isTrue();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(row.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.OWNER);
        ForcedChoice.Decision after = decideFor(parts);
        assertThat(before.step()).isEqualTo(ForcedChoice.Step.PROVEN_FILE);
        assertThat(after.step()).isEqualTo(ForcedChoice.Step.PROVEN_FILE);
        assertThat(after.source()).isEqualTo(ForcedChoice.Source.FILE);
        assertThat(after.downloadId()).isNull();
    }

    /** What the edition plays for this file, with a proven download and the embedded track also on offer. */
    private ForcedChoice.Decision decideFor(List<Long> parts) {
        SidecarTrust.Assessment assessment = SidecarTrust.assess(row.isVerifiedFor(parts), null, false);
        return ForcedChoice.decide(new ForcedChoice.Input(new ForcedChoice.FileSide(assessment, 20),
            List.of(new ForcedChoice.DownloadSide(1L, new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN,
                SidecarTrust.Proof.AGREES))), true));
    }

    @Test
    void anAttachStillTakesTheOwnersCheckAway() {
        listAndFind();
        when(editions.findById(40L)).thenReturn(Optional.of(split));
        when(files.findAllById(Set.of(20L))).thenReturn(List.of(split.originalParts().get(1)));
        row.setOwnerVerifiedAt(NOW);
        row.setVerifiedParts("10,20");

        service.attach(5L, new AttachRequest(40L, 2));

        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
    }

    @Test
    void detachClearsTheAttachmentAndMatchesByNameAgain() {
        listAndFind();
        row.setOwnerAttached(true);
        row.setMediaFileId(10L);
        row.setWholeEdition(true);
        // The real rematch gives the file back to where its name says: here, nowhere.
        org.mockito.Mockito.doAnswer(call -> {
            row.retarget(null, false);
            return null;
        }).when(sync).rematch(row);

        service.detach(5L);

        assertThat(row.isOwnerAttached()).isFalse();
        assertThat(row.getMediaFileId()).isNull();
        assertThat(row.isWholeEdition()).isFalse();
        verify(sync).rematch(row);
    }

    @Test
    void cuesOfAnUnmatchedFileStartAtZeroPlusTheOffsetNeverBelowZero() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        row.setOffsetMs(-15_000);

        assertThat(service.cues(5L).cues()).extracting(SubtitleDtos.CueDto::startMs).containsExactly(0L);
    }

    private SubtitleSidecar downloadRow() {
        SubtitleSidecar download = new SubtitleSidecar();
        download.setId(8L);
        download.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        download.setMediaFileId(10L);
        download.setWholeEdition(true);
        download.setImdbId("tt0167261");
        download.setOpenSubtitlesFileId(5011L);
        download.setPath("opensubtitles:40:5011");
        download.setUpdatedAt(NOW);
        return download;
    }

    @Test
    void removingADownloadDeletesItBlocksItsFileForTheEditionAndPublishesTheEventAfterTheCommit() {
        SubtitleSidecar download = downloadRow();
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(editions.existsById(40L)).thenReturn(true);
        when(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(40L, "tt0167261", 5011L, SubtitleKind.FORCED))
            .thenReturn(false);

        service.remove(8L);

        verify(sidecars).delete(download);
        verify(blocks).save(argThat(block -> block.getEditionId().equals(40L) && block.getImdbId().equals("tt0167261")
            && block.getOsFileId() == 5011L && block.getRemovedAt().equals(NOW)));
        verify(events).publishEvent(new SubtitleDownloadRemoved(40L));
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aListenerThatFailsAfterTheRemovalCommittedDoesNotMakeItAnError() {
        SubtitleSidecar download = downloadRow();
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(editions.existsById(40L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("executor refused")).when(events)
            .publishEvent(new SubtitleDownloadRemoved(40L));

        service.remove(8L);

        verify(sidecars).delete(download);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFileAlreadyBlockedForTheEditionIsNotBlockedAgain() {
        SubtitleSidecar download = downloadRow();
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(editions.existsById(40L)).thenReturn(true);
        when(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(40L, "tt0167261", 5011L, SubtitleKind.FORCED))
            .thenReturn(true);

        service.remove(8L);

        verify(blocks, never()).save(argThat(block -> true));
        verify(sidecars).delete(download);
    }

    @Test
    void aDownloadWhoseFileMovedToAnotherEditionBlocksTheEditionItWasMadeFor() {
        SubtitleSidecar download = downloadRow();
        download.setPath("opensubtitles:77:5011");
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(editions.existsById(77L)).thenReturn(true);
        when(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(77L, "tt0167261", 5011L, SubtitleKind.FORCED))
            .thenReturn(false);

        service.remove(8L);

        verify(blocks).save(argThat(block -> block.getEditionId().equals(77L) && block.getOsFileId() == 5011L));
        verify(sidecars).delete(download);
        verify(events).publishEvent(new SubtitleDownloadRemoved(77L));
    }

    @Test
    void aDownloadWhoseEditionWasDeletedIsRemovedWithNoBlock() {
        SubtitleSidecar download = downloadRow();
        download.setPath("opensubtitles:99:5011");
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(editions.existsById(99L)).thenReturn(false);

        service.remove(8L);

        verify(sidecars).delete(download);
        verify(blocks, never()).save(argThat(block -> true));
    }

    @Test
    void aFileBesideTheFilmCannotBeRemovedAndNothingIsDeletedOrPublished() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.remove(5L)).isInstanceOf(BadRequestException.class)
            .hasMessage("Only a subtitle downloaded from OpenSubtitles can be removed");

        verify(sidecars, never()).delete(row);
        verify(events, never()).publishEvent(new SubtitleDownloadRemoved(null));
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aDownloadCannotBeAttachedOrDetachedAndKeepsItsPlace() {
        SubtitleSidecar download = downloadRow();
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));

        assertThatThrownBy(() -> service.attach(8L, new AttachRequest(40L, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("A downloaded subtitle belongs to its edition and cannot be attached");
        assertThatThrownBy(() -> service.detach(8L)).isInstanceOf(BadRequestException.class)
            .hasMessage("A downloaded subtitle belongs to its edition and cannot be attached");

        assertThat(download.getMediaFileId()).isEqualTo(10L);
        assertThat(download.isWholeEdition()).isTrue();
        verify(sync, never()).rematch(download);
    }

    @Test
    void removalIsRefusedWithAConflictWhileTheLibraryLockIsHeld() {
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> service.remove(8L)).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
    }

    @Test
    void aChangeWhileATimingOrCheckPassHoldsTheLockIsNotCalledAScan() {
        lock.tryAcquire(LibraryLock.SUBTITLE);

        assertThatThrownBy(() -> service.remove(8L)).isInstanceOf(ConflictException.class)
            .hasMessage("Subtitles are being changed or checked; try again in a moment");
    }

    @Test
    void everyChangeIsRefusedWithAConflictWhileTheLibraryLockIsHeld() {
        lock.tryAcquire(LibraryLock.SCAN);

        for (Runnable action : List.<Runnable>of(() -> service.detach(5L), () -> service.verify(5L),
            () -> service.unverify(5L), () -> service.offset(5L, new OffsetRequest(1)),
            () -> service.attach(5L, new AttachRequest(40L, null)))) {
            assertThatThrownBy(action::run).isInstanceOf(ConflictException.class)
                .hasMessage("A scan is running");
        }
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
    }

    /** The forced side of the list is empty: the file lookup for no files is asked all the same. */
    private void noForcedRows() {
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).thenReturn(List.of());
        when(files.findAllById(Set.of())).thenReturn(List.of());
    }

    private SubtitleSidecar fullRow(long id, String path, Long fileId) {
        SubtitleSidecar full = new SubtitleSidecar();
        full.setId(id);
        full.setKind(SubtitleKind.FULL);
        full.setPath(path);
        full.setMediaFileId(fileId);
        full.setCuesText(row.getCuesText());
        full.setCueCount(1);
        full.setUpdatedAt(NOW);
        return full;
    }

    @Test
    void theListHasTheForcedRowsThenTheFullOnesInPathOrderEachWithItsKindAndNoChecks() {
        row.setPath("B.en.forced.srt");
        SubtitleSidecar full = fullRow(6L, "A.en.srt", 10L);
        SubtitleSidecar loose = fullRow(7L, "C.en.srt", null);
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).thenReturn(List.of(row));
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).thenReturn(List.of(full, loose));
        when(files.findAllById(Set.of())).thenReturn(List.of());
        when(files.findAllById(Set.of(10L))).thenReturn(List.of(split.originalParts().get(0)));

        List<SidecarDto> listed = service.list();

        assertThat(listed).extracting(SidecarDto::path).containsExactly("A.en.srt", "B.en.forced.srt", "C.en.srt");
        assertThat(listed).extracting(SidecarDto::kind).containsExactly("FULL", "FORCED", "FULL");
        SidecarDto matched = listed.get(0);
        assertThat(matched.state()).isEqualTo("matched");
        assertThat(matched.served()).isTrue();
        assertThat(matched.servedReason()).isEqualTo("Ready to play as English (full)");
        assertThat(matched.check()).isNull();
        assertThat(matched.alignment()).isNull();
        assertThat(matched.replacedByDownload()).isFalse();
        assertThat(listed.get(2).state()).isEqualTo("unmatched");
        assertThat(listed.get(2).servedReason()).isEqualTo("Not used: it is not attached to a film");
    }

    @Test
    void aFullFileOnALaterPartWhoseStartIsUnknownIsNotOfferedAndSaysSo() {
        SubtitleSidecar onPartTwo = fullRow(6L, "A.en.srt", 20L);
        split.originalParts().get(0).setDurationMs(null);
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).thenReturn(List.of(onPartTwo));
        noForcedRows();
        when(files.findAllById(Set.of(20L))).thenReturn(List.of(split.originalParts().get(1)));

        SidecarDto dto = service.list().get(0);

        assertThat(dto.served()).isFalse();
        assertThat(dto.state()).isEqualTo("matched");
        assertThat(dto.servedReason()).isEqualTo("Not used: the lengths of the film's parts are not known yet");
    }

    @Test
    void aFullFileTheSubtitlesMenuDoesNotOfferSaysWhy() {
        SubtitleSidecar missing = fullRow(6L, "A.en.srt", 10L);
        missing.setPresence(gregochr.cinefilm.film.Presence.MISSING);
        SubtitleSidecar empty = fullRow(7L, "B.en.srt", 20L);
        empty.setCueCount(0);
        SubtitleSidecar one = fullRow(8L, "C.en.srt", 10L);
        SubtitleSidecar two = fullRow(9L, "D.en.srt", 10L);
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL))
            .thenReturn(List.of(missing, empty, one, two));
        noForcedRows();
        when(files.findAllById(Set.of(10L, 20L))).thenReturn(split.originalParts());

        List<SidecarDto> listed = service.list();

        assertThat(listed).extracting(SidecarDto::servedReason).containsExactly("Not used: the file is missing",
            "Not used: it has no lines", "Not used: two full subtitle files compete for the same place",
            "Not used: two full subtitle files compete for the same place");
        assertThat(listed).extracting(SidecarDto::state).containsExactly("matched", "matched", "conflict", "conflict");
        assertThat(listed).extracting(SidecarDto::served).containsOnly(false);
    }

    @Test
    void aFullFileCannotBeCheckedOrUncheckedAsForced() {
        SubtitleSidecar full = fullRow(6L, "A.en.srt", 10L);
        when(sidecars.findById(6L)).thenReturn(Optional.of(full));

        assertThatThrownBy(() -> service.verify(6L)).isInstanceOf(BadRequestException.class)
            .hasMessage("Only a forced subtitle can be checked");
        assertThatThrownBy(() -> service.unverify(6L)).isInstanceOf(BadRequestException.class)
            .hasMessage("Only a forced subtitle can be checked");
        assertThat(full.getOwnerVerifiedAt()).isNull();
    }

    @Test
    void aFullFileCanBeAttachedAndNudgedLikeAForcedOne() {
        SubtitleSidecar full = fullRow(6L, "A.en.srt", null);
        when(sidecars.findById(6L)).thenReturn(Optional.of(full));
        noForcedRows();
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).thenReturn(List.of(full));
        when(editions.findById(40L)).thenReturn(Optional.of(split));
        when(files.findAllById(Set.of(20L))).thenReturn(List.of(split.originalParts().get(1)));

        service.attach(6L, new AttachRequest(40L, 2));
        service.offset(6L, new OffsetRequest(-250));

        assertThat(full.getMediaFileId()).isEqualTo(20L);
        assertThat(full.isOwnerAttached()).isTrue();
        assertThat(full.getOffsetMs()).isEqualTo(-250);
        assertThat(full.getKind()).isEqualTo(SubtitleKind.FULL);
    }

    @Test
    void removingAFullDownloadBlocksItsFileAsAFullOne() {
        SubtitleSidecar download = downloadRow();
        download.setKind(SubtitleKind.FULL);
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(editions.existsById(40L)).thenReturn(true);
        when(blocks.existsByEditionIdAndImdbIdAndOsFileIdAndKind(40L, "tt0167261", 5011L, SubtitleKind.FULL))
            .thenReturn(false);

        service.remove(8L);

        verify(blocks).save(argThat(block -> block.getKind() == SubtitleKind.FULL && block.getOsFileId() == 5011L));
        verify(sidecars).delete(download);
    }

    private SubtitleSidecar statedDownload() {
        SubtitleSidecar download = downloadRow();
        download.setTimedFps(23.976);
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).thenReturn(List.of(download));
        when(filmRates.forEditions(Set.of(40L))).thenReturn(Map.of(40L, 25.0));
        return download;
    }

    @Test
    void retimeOnAppliesTheChoiceUnderTheLockAndAnswersTheRowWithItsRateLine() {
        SubtitleSidecar download = statedDownload();
        List<String> heldWhenApplied = new ArrayList<>();
        when(retimer.applyChoice(download, true)).thenAnswer(call -> {
            heldWhenApplied.add(lock.holder());
            download.setRetime(RetimeChoice.AUTO);
            download.setRetimedBy(23.976 / 25);
            return SubtitleRetimeService.Outcome.REWRITTEN;
        });

        SidecarDto dto = service.retime(8L, new RetimeRequest(true));

        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(8L)));

        assertThat(heldWhenApplied).containsExactly(LibraryLock.SUBTITLE);
        assertThat(lock.holder()).isNull();
        assertThat(dto.timedFps()).isEqualTo(23.976);
        assertThat(dto.filmFps()).isEqualTo(25.0);
        assertThat(dto.retime()).isEqualTo("AUTO");
        assertThat(dto.retimedBy()).isEqualTo(23.976 / 25);
        assertThat(dto.rateLine()).isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
    }

    @Test
    void retimeOffAppliesTheChoiceAndAnswersTheRowAsNotRetimed() {
        SubtitleSidecar download = statedDownload();
        when(retimer.applyChoice(download, false)).thenAnswer(call -> {
            download.setRetime(RetimeChoice.OFF);
            return SubtitleRetimeService.Outcome.RESTORED;
        });

        SidecarDto dto = service.retime(8L, new RetimeRequest(false));

        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(8L)));
        assertThat(dto.retime()).isEqualTo("OFF");
        assertThat(dto.retimedBy()).isNull();
        assertThat(dto.rateLine()).isEqualTo("Not retimed; it may drift");
    }

    @Test
    void retimeOfAnOwnersFileIsRefusedAndNothingIsChanged() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.retime(5L, new RetimeRequest(true))).isInstanceOf(BadRequestException.class)
            .hasMessage("Only a subtitle downloaded from OpenSubtitles can be retimed");
        assertThat(row.getRetime()).isNull();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void retimeOfAnUnknownSubtitleIsNotFound() {
        when(sidecars.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retime(9L, new RetimeRequest(true))).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
    }

    @Test
    void retimeWithNoChoiceInTheBodyIsRefused() {
        SubtitleSidecar download = downloadRow();
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));

        assertThatThrownBy(() -> service.retime(8L, new RetimeRequest(null))).isInstanceOf(BadRequestException.class)
            .hasMessage("Say whether to retime it");
        assertThatThrownBy(() -> service.retime(8L, null)).isInstanceOf(BadRequestException.class)
            .hasMessage("Say whether to retime it");
        assertThat(download.getRetime()).isNull();
    }

    @Test
    void retimeIsRefusedWithTheLockHoldersTextWhileTheLibraryLockIsHeld() {
        lock.tryAcquire(LibraryLock.SUBTITLE);

        assertThatThrownBy(() -> service.retime(8L, new RetimeRequest(false))).isInstanceOf(ConflictException.class)
            .hasMessage("Subtitles are being changed or checked; try again in a moment");
        assertThat(lock.holder()).isEqualTo(LibraryLock.SUBTITLE);
    }

    @Test
    void theListReadsTheFilmRatesOncePerEditionForEveryDownloadAndNeverForAnOwnersFile() {
        SubtitleSidecar first = downloadRow();
        first.setTimedFps(25.0);
        SubtitleSidecar second = downloadRow();
        second.setId(9L);
        second.setPath("opensubtitles:41:5012");
        SubtitleSidecar sameEdition = downloadRow();
        sameEdition.setId(10L);
        sameEdition.setPath("opensubtitles:40:5013");
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED))
            .thenReturn(List.of(first, second, sameEdition, row));
        when(filmRates.forEditions(Set.of(40L, 41L))).thenReturn(Map.of(40L, 25.0));

        List<SidecarDto> listed = service.list();

        assertThat(listed).extracting(SidecarDto::filmFps).containsExactly(25.0, null, 25.0, null);
        assertThat(listed).extracting(SidecarDto::rateLine).containsExactly("Timed for this film's frame rate",
            "Frame rate unknown", "Frame rate unknown", null);
    }

    @Test
    void aListWithNoDownloadsReadsNoRates() {
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).thenReturn(List.of(row));

        assertThat(service.list()).extracting(SidecarDto::rateLine).containsExactly((String) null);

        verifyNoInteractions(filmRates);
    }

    @Test
    void aFullDownloadListsItsRateFactsFromTheSameSingleRatesRead() {
        noForcedRows();
        SubtitleSidecar full = fullRow(11L, "opensubtitles:40:5099", null);
        full.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        full.setTimedFps(23.976);
        full.setRetimedBy(23.976 / 25);
        full.setRetime(RetimeChoice.AUTO);
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).thenReturn(List.of(full));
        when(filmRates.forEditions(Set.of(40L))).thenReturn(Map.of(40L, 25.0));

        SidecarDto dto = service.list().get(0);

        assertThat(dto.kind()).isEqualTo("FULL");
        assertThat(dto.filmFps()).isEqualTo(25.0);
        assertThat(dto.rateLine()).isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
        verify(filmRates).forEditions(Set.of(40L));
    }

    @Test
    void aForcedAndAFullDownloadOfOneListShareOneRatesRead() {
        SubtitleSidecar forcedDownload = downloadRow();
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FORCED)).thenReturn(List.of(forcedDownload));
        SubtitleSidecar full = fullRow(11L, "opensubtitles:41:5099", null);
        full.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        when(sidecars.findAllByKindOrderByPathAscIdAsc(SubtitleKind.FULL)).thenReturn(List.of(full));
        when(filmRates.forEditions(Set.of(40L, 41L))).thenReturn(Map.of());

        assertThat(service.list()).hasSize(2);

        verify(filmRates).forEditions(Set.of(40L, 41L));
    }

    @Test
    void anOnForAFitsAsItIsRowIsRefusedAndNothingIsWrittenOrPublished() {
        SubtitleSidecar download = downloadRow();
        download.setRetime(RetimeChoice.OFF_FITS);
        when(sidecars.findById(8L)).thenReturn(Optional.of(download));

        assertThatThrownBy(() -> service.retime(8L, new RetimeRequest(true))).isInstanceOf(ConflictException.class)
            .hasMessage("This file already fits this copy as it is, so it is not retimed");

        assertThat(download.getRetime()).isEqualTo(RetimeChoice.OFF_FITS);
        assertThat(lock.holder()).isNull();
        verifyNoInteractions(retimer, events);
    }

    @Test
    void anUnchangedRetimePublishesNothing() {
        SubtitleSidecar download = statedDownload();
        when(retimer.applyChoice(download, true)).thenReturn(SubtitleRetimeService.Outcome.UNCHANGED);

        service.retime(8L, new RetimeRequest(true));

        verifyNoInteractions(events);
    }

    @Test
    void aListenerThatFailsAfterTheRetimeCommittedDoesNotMakeItAnError() {
        SubtitleSidecar download = statedDownload();
        when(retimer.applyChoice(download, true)).thenReturn(SubtitleRetimeService.Outcome.REWRITTEN);
        org.mockito.Mockito.doThrow(new IllegalStateException("executor refused")).when(events)
            .publishEvent(new SubtitleCuesRewritten(Set.of(8L)));

        SidecarDto dto = service.retime(8L, new RetimeRequest(true));

        assertThat(dto.id()).isEqualTo(8L);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void whileTheSettingIsRestoreAnOnIsRefusedAndAnOffIsStillApplied() {
        SubtitleSidecar download = statedDownload();
        SubtitleAdminService restoring = new SubtitleAdminService(sidecars, files, editions, sync, resolver, blocks,
            CLOCK, lock, transactions, events, filmRates, retimer, "restore");
        when(retimer.applyChoice(download, false)).thenReturn(SubtitleRetimeService.Outcome.RESTORED);

        assertThatThrownBy(() -> restoring.retime(8L, new RetimeRequest(true))).isInstanceOf(ConflictException.class)
            .hasMessage("Retiming is being undone on this server; try again once that is finished");
        assertThat(lock.holder()).isNull();
        assertThat(restoring.retime(8L, new RetimeRequest(false)).id()).isEqualTo(8L);
        verify(retimer).applyChoice(download, false);
    }

    @Test
    void anOnIsAllowedWhateverTheOtherSettingsAre() {
        SubtitleSidecar download = statedDownload();
        for (String setting : List.of("off", "on", "nonsense")) {
            SubtitleAdminService other = new SubtitleAdminService(sidecars, files, editions, sync, resolver, blocks,
                CLOCK, lock, transactions, events, filmRates, retimer, setting);
            when(retimer.applyChoice(download, true)).thenReturn(SubtitleRetimeService.Outcome.UNCHANGED);

            assertThat(other.retime(8L, new RetimeRequest(true)).id()).isEqualTo(8L);
        }
    }
}
