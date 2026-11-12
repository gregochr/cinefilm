package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.CueDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CuesDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.library.LibraryLock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

/** The Admin subtitle service against the real repositories on H2. */
class SubtitleAdminServiceTest extends SubtitleTestBase {

    @Autowired
    private LibraryLock lock;

    private SidecarDto listed(Long id) {
        return admin.list().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void anUnmatchedFileIsListedWithNothingAboutAFilm() {
        SubtitleSidecar row = sidecar("Alien.en.forced.srt", null, false);

        assertThat(listed(row.getId())).isEqualTo(new SidecarDto(row.getId(), "Alien.en.forced.srt", "PRESENT",
            "unmatched", null, null, null, null, 0, null, false, false, 2, "No problems found", 0, false, false,
            NOW.toEpochMilli(), null));
    }

    @Test
    void aMatchedFileNamesItsFilmEditionAndPart() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setOffsetMs(-250);

        SidecarDto dto = listed(row.getId());

        assertThat(dto).isEqualTo(new SidecarDto(row.getId(), "Taken.en.forced.srt", "PRESENT", "matched",
            taken.getId(), "Taken", taken.getEditions().get(0).getId(), "Original", 1, 1, false, false, 2,
            "No problems found", -250, false, false, NOW.toEpochMilli(), null));
    }

    @Test
    void aWholeEditionFileOfASplitFilmHasNoPartAndThePartCount() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Towers.en.forced.srt", fileId(towers, 1), true);

        SidecarDto dto = listed(row.getId());

        assertThat(dto.whole()).isTrue();
        assertThat(dto.part()).isNull();
        assertThat(dto.parts()).isEqualTo(2);
    }

    @Test
    void aPerPartFileOfASplitFilmNamesItsPart() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Towers - pt2.en.forced.srt", fileId(towers, 2), false);

        assertThat(listed(row.getId()).part()).isEqualTo(2);
    }

    @Test
    void twoFilesCompetingForOnePlaceAreBothListedAsConflicts() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar first = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        SubtitleSidecar second = sidecar("Taken.forced.en.srt", fileId(taken, 1), false);
        Film heat = filmWithParts("Heat", 1, 90);
        SubtitleSidecar fine = sidecar("Heat.en.forced.srt", fileId(heat, 1), false);

        assertThat(listed(first.getId()).state()).isEqualTo("conflict");
        assertThat(listed(second.getId()).state()).isEqualTo("conflict");
        assertThat(listed(fine.getId()).state()).isEqualTo("matched");
    }

    @Test
    void aMissingFileIsListedAsMissingButStillMatched() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setPresence(Presence.MISSING);

        SidecarDto dto = listed(row.getId());

        assertThat(dto.presence()).isEqualTo("MISSING");
        assertThat(dto.state()).isEqualTo("matched");
    }

    @Test
    void theListIsInPathOrder() {
        sidecar("B.en.forced.srt", null, false);
        sidecar("A.en.forced.srt", null, false);

        assertThat(admin.list()).extracting(SidecarDto::path).containsExactly("A.en.forced.srt", "B.en.forced.srt");
    }

    @Test
    void aFileEndingAfterTheEditionsRuntimeIsFlaggedOnlyPastTheLastMillisecond() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);

        row.setLastEndMs(90 * MIN);
        assertThat(listed(row.getId()).endsAfterRuntime()).isFalse();
        row.setLastEndMs(90 * MIN + 1);
        assertThat(listed(row.getId()).endsAfterRuntime()).isTrue();
    }

    @Test
    void theOffsetCountsTowardsEndingAfterTheFilm() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setLastEndMs(90 * MIN - 1000);

        row.setOffsetMs(1000);
        assertThat(listed(row.getId()).endsAfterRuntime()).isFalse();
        row.setOffsetMs(1001);
        assertThat(listed(row.getId()).endsAfterRuntime()).isTrue();
    }

    @Test
    void aSecondPartFileCountsFromWhereThatPartStarts() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Towers - pt2.en.forced.srt", fileId(towers, 2), false);

        row.setLastEndMs(60 * MIN);
        assertThat(listed(row.getId()).endsAfterRuntime()).isFalse();
        row.setLastEndMs(60 * MIN + 1);
        assertThat(listed(row.getId()).endsAfterRuntime()).isTrue();
    }

    @Test
    void anEditionWithNoRuntimeOrAFileWithNoCuesIsNeverFlagged() {
        Film taken = filmWithParts("Taken", 1, 90);
        taken.getEditions().get(0).setRuntimeMs(0);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setLastEndMs(500 * MIN);
        assertThat(listed(row.getId()).endsAfterRuntime()).isFalse();

        taken.getEditions().get(0).setRuntimeMs(90 * MIN);
        row.setLastEndMs(0);
        row.setOffsetMs(600_000);
        assertThat(listed(row.getId()).endsAfterRuntime()).isFalse();
    }

    @Test
    void cuesCarryTheOffsetAppliedAndTheEditionTimeline() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setOffsetMs(1500);

        CuesDto cues = admin.cues(row.getId());

        assertThat(cues.offsetMs()).isEqualTo(1500);
        assertThat(cues.cues()).containsExactly(new CueDto(11_500, 13_500, "Hello"),
            new CueDto(21_500, 24_000, "World"));
    }

    @Test
    void aCueNeverStartsBeforeZeroWhateverTheOffset() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setOffsetMs(-15_000);

        assertThat(admin.cues(row.getId()).cues()).containsExactly(new CueDto(0, 0, "Hello"),
            new CueDto(5000, 7500, "World"));
    }

    @Test
    void aSecondPartFilesCuesAreOnTheEditionTimelineAndAWholeEditionFilesAreAsTheyAre() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar part = sidecar("Towers - pt2.en.forced.srt", fileId(towers, 2), false);
        SubtitleSidecar whole = sidecar("Towers.en.forced.srt", fileId(towers, 2), true);

        assertThat(admin.cues(part.getId()).cues().get(0)).isEqualTo(new CueDto(60 * MIN + 10_000, 60 * MIN + 12_000,
            "Hello"));
        assertThat(admin.cues(whole.getId()).cues().get(0)).isEqualTo(new CueDto(10_000, 12_000, "Hello"));
    }

    @Test
    void aFirstPartFilesCuesAreNotMoved() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar part = sidecar("Towers - pt1.en.forced.srt", fileId(towers, 1), false);

        assertThat(admin.cues(part.getId()).cues().get(0).startMs()).isEqualTo(10_000);
    }

    @Test
    void aSecondPartFileOfAnEditionWithNoTimelineIsNotMoved() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar part = sidecar("Towers - pt2.en.forced.srt", fileId(towers, 2), false);
        towers.getEditions().get(0).originalParts().get(0).setDurationMs(null);

        assertThat(admin.cues(part.getId()).cues().get(0).startMs()).isEqualTo(10_000);
        assertThat(listed(part.getId()).endsAfterRuntime()).isFalse();
    }

    @Test
    void anUnmatchedFilesCuesStartAtZero() {
        SubtitleSidecar row = sidecar("Alien.en.forced.srt", null, false);

        assertThat(admin.cues(row.getId()).cues().get(1)).isEqualTo(new CueDto(20_000, 22_500, "World"));
    }

    @Test
    void cuesOfAnUnknownFileIs404() {
        assertThatThrownBy(() -> admin.cues(9999L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
    }

    @Test
    void attachingToAWholeEditionStoresItAgainstPartOneAndMarksItAttached() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Other name.en.forced.srt", null, false);

        SidecarDto dto = admin.attach(row.getId(), new AttachRequest(towers.getEditions().get(0).getId(), null));

        assertThat(row.getMediaFileId()).isEqualTo(fileId(towers, 1));
        assertThat(row.isWholeEdition()).isTrue();
        assertThat(row.isOwnerAttached()).isTrue();
        assertThat(dto.state()).isEqualTo("matched");
        assertThat(dto.ownerAttached()).isTrue();
        assertThat(dto.filmTitle()).isEqualTo("Towers");
        assertThat(dto.whole()).isTrue();
    }

    @Test
    void attachingToAPartStoresItAgainstThatPartsFile() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Other name.en.forced.srt", null, true);

        SidecarDto dto = admin.attach(row.getId(), new AttachRequest(towers.getEditions().get(0).getId(), 2));

        assertThat(row.getMediaFileId()).isEqualTo(fileId(towers, 2));
        assertThat(row.isWholeEdition()).isFalse();
        assertThat(dto.part()).isEqualTo(2);
    }

    @Test
    void attachingToPartOneOfASingleFileEditionIsPerPart() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Other.en.forced.srt", null, false);

        admin.attach(row.getId(), new AttachRequest(taken.getEditions().get(0).getId(), 1));

        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        assertThat(row.isWholeEdition()).isFalse();
    }

    @Test
    void aWholeFilmAttachToASingleFileIsFileRelativeSoALaterJoinPlaysItAfterTheFirstFile() {
        Film other = filmWithParts("Other", 1, 60);
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Other name.en.forced.srt", null, false);
        Long edition = taken.getEditions().get(0).getId();

        SidecarDto dto = admin.attach(row.getId(), new AttachRequest(edition, null));

        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        assertThat(row.isWholeEdition()).isFalse();
        assertThat(dto.whole()).isFalse();
        assertThat(dto.part()).isEqualTo(1);

        // Another file is joined before it (lower id, same part number): Taken's file now starts 60 minutes in.
        mediaFiles.moveToEdition(List.of(fileId(other, 1)), editionRepository.findById(edition).orElseThrow());

        assertThat(forced.vtt(edition)).startsWith("WEBVTT\n\n01:00:10.000 --> 01:00:12.000\nHello");
        assertThat(admin.cues(row.getId()).cues().get(0).startMs()).isEqualTo(60 * 60_000L + 10_000);
    }

    @Test
    void aWholeFilmAttachToASplitEditionIsStillWholeEdition() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Other name.en.forced.srt", null, false);

        admin.attach(row.getId(), new AttachRequest(towers.getEditions().get(0).getId(), null));

        assertThat(row.isWholeEdition()).isTrue();
    }

    @Test
    void attachingTakesTheOwnersCheckAwayAndTouchesTheVersion() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setOwnerVerifiedAt(NOW);
        row.setVerifiedParts("1");
        row.setUpdatedAt(NOW.minusSeconds(100));

        admin.attach(row.getId(), new AttachRequest(taken.getEditions().get(0).getId(), null));

        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void attachNeedsAnEditionThatExistsWithFilesAndThePartItNames() {
        Film towers = filmWithParts("Towers", 2, 60);
        Film bare = filmWithParts("Bare", 1, 60);
        bare.getEditions().get(0).getMediaFiles().clear();
        editionRepository.saveAndFlush(bare.getEditions().get(0));
        SubtitleSidecar row = sidecar("X.en.forced.srt", null, false);
        Long editionId = towers.getEditions().get(0).getId();

        assertThatThrownBy(() -> admin.attach(row.getId(), null)).isInstanceOf(BadRequestException.class)
            .hasMessage("Pick an edition");
        assertThatThrownBy(() -> admin.attach(row.getId(), new AttachRequest(null, 1)))
            .isInstanceOf(BadRequestException.class).hasMessage("Pick an edition");
        assertThatThrownBy(() -> admin.attach(row.getId(), new AttachRequest(9999L, null)))
            .isInstanceOf(NotFoundException.class).hasMessage("No such edition");
        assertThatThrownBy(() -> admin.attach(row.getId(), new AttachRequest(bare.getEditions().get(0).getId(), null)))
            .isInstanceOf(BadRequestException.class).hasMessage("That edition has no film file");
        assertThatThrownBy(() -> admin.attach(row.getId(), new AttachRequest(editionId, 3)))
            .isInstanceOf(BadRequestException.class).hasMessage("That edition has no part 3");
        assertThatThrownBy(() -> admin.attach(row.getId(), new AttachRequest(editionId, 0)))
            .isInstanceOf(BadRequestException.class).hasMessage("That edition has no part 0");
        assertThat(row.getMediaFileId()).isNull();
        assertThat(row.isOwnerAttached()).isFalse();
    }

    @Test
    void attachToAnUnknownFileIs404BeforeAnythingElseIsChecked() {
        assertThatThrownBy(() -> admin.attach(9999L, null)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
    }

    @Test
    void detachGivesTheFileBackToMatchingByNameAndTakesTheCheckAway() {
        Film taken = filmWithParts("Taken", 1, 90);
        Film heat = filmWithParts("Heat", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(heat, 1), true);
        row.setOwnerAttached(true);
        row.setOwnerVerifiedAt(NOW);
        row.setVerifiedParts("1");

        SidecarDto dto = admin.detach(row.getId());

        assertThat(row.isOwnerAttached()).isFalse();
        assertThat(row.getMediaFileId()).isEqualTo(fileId(taken, 1));
        assertThat(row.isWholeEdition()).isFalse();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(dto.ownerAttached()).isFalse();
        assertThat(dto.filmTitle()).isEqualTo("Taken");
    }

    @Test
    void detachingAFileWithNoFilmByThatNameLeavesItUnmatched() {
        Film heat = filmWithParts("Heat", 1, 90);
        SubtitleSidecar row = sidecar("Alien.en.forced.srt", fileId(heat, 1), false);
        row.setOwnerAttached(true);

        assertThat(admin.detach(row.getId()).state()).isEqualTo("unmatched");
    }

    @Test
    void detachOfAnUnknownFileIs404() {
        assertThatThrownBy(() -> admin.detach(9999L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
    }

    @Test
    void anOffsetIsStoredAndTakesTheCheckAwayAndTouchesTheVersion() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        row.setOwnerVerifiedAt(NOW);
        row.setVerifiedParts("1");
        row.setUpdatedAt(NOW.minusSeconds(100));

        SidecarDto dto = admin.offset(row.getId(), new OffsetRequest(-1250));

        assertThat(dto.offsetMs()).isEqualTo(-1250);
        assertThat(dto.verified()).isFalse();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void theOffsetMayBeTenMinutesEitherWayAndNotOneMillisecondMore() {
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", null, false);

        assertThat(admin.offset(row.getId(), new OffsetRequest(600_000)).offsetMs()).isEqualTo(600_000);
        assertThat(admin.offset(row.getId(), new OffsetRequest(-600_000)).offsetMs()).isEqualTo(-600_000);
        assertThat(admin.offset(row.getId(), new OffsetRequest(0)).offsetMs()).isZero();
        assertThatThrownBy(() -> admin.offset(row.getId(), new OffsetRequest(600_001)))
            .isInstanceOf(BadRequestException.class).hasMessage("The offset must be within 10 minutes either way");
        assertThatThrownBy(() -> admin.offset(row.getId(), new OffsetRequest(-600_001)))
            .isInstanceOf(BadRequestException.class).hasMessage("The offset must be within 10 minutes either way");
        assertThat(row.getOffsetMs()).isZero();
    }

    @Test
    void theExtremeIntegersAreRefusedNotOverflowedPastTheCheck() {
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", null, false);

        for (int extreme : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> admin.offset(row.getId(), new OffsetRequest(extreme)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("The offset must be within 10 minutes either way");
        }
        assertThat(row.getOffsetMs()).isZero();
    }

    @Test
    void anOffsetNeedsAValueAndAKnownFile() {
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", null, false);

        assertThatThrownBy(() -> admin.offset(row.getId(), null)).isInstanceOf(BadRequestException.class)
            .hasMessage("An offset is required");
        assertThatThrownBy(() -> admin.offset(row.getId(), new OffsetRequest(null)))
            .isInstanceOf(BadRequestException.class).hasMessage("An offset is required");
        assertThatThrownBy(() -> admin.offset(9999L, new OffsetRequest(1)))
            .isInstanceOf(NotFoundException.class).hasMessage("No such subtitle");
    }

    @Test
    void verifyRecordsTheEditionsPartsAndUnverifyTakesItAway() {
        Film towers = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Towers.en.forced.srt", fileId(towers, 1), true);
        row.setUpdatedAt(NOW.minusSeconds(100));

        SidecarDto verified = admin.verify(row.getId());

        assertThat(verified.verified()).isTrue();
        assertThat(row.getOwnerVerifiedAt()).isEqualTo(NOW);
        assertThat(row.getVerifiedParts()).isEqualTo(fileId(towers, 1) + "," + fileId(towers, 2));
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);

        row.setUpdatedAt(NOW.minusSeconds(100));
        SidecarDto undone = admin.unverify(row.getId());

        assertThat(undone.verified()).isFalse();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void theCheckStopsCountingWhenTheEditionsPartsChange() {
        Film towers = filmWithParts("Towers", 2, 60);
        Film other = filmWithParts("Other", 1, 60);
        SubtitleSidecar row = sidecar("Towers.en.forced.srt", fileId(towers, 1), true);
        admin.verify(row.getId());
        assertThat(listed(row.getId()).verified()).isTrue();

        mediaFiles.moveToEdition(List.of(fileId(other, 1)), editionRepository.findById(
            towers.getEditions().get(0).getId()).orElseThrow());

        assertThat(listed(row.getId()).verified()).isFalse();
    }

    @Test
    void verifyNeedsAFilmAndAKnownFile() {
        SubtitleSidecar row = sidecar("Alien.en.forced.srt", null, false);

        assertThatThrownBy(() -> admin.verify(row.getId())).isInstanceOf(BadRequestException.class)
            .hasMessage("Attach the subtitle to a film first");
        assertThatThrownBy(() -> admin.verify(9999L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
        assertThatThrownBy(() -> admin.unverify(9999L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle");
    }

    @Test
    void everyChangeIsRefusedWhileAScanHoldsTheLibraryLockAndNothingIsChanged() {
        Film taken = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        Long edition = taken.getEditions().get(0).getId();
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            for (Runnable change : List.<Runnable>of(() -> admin.attach(row.getId(), new AttachRequest(edition, null)),
                () -> admin.detach(row.getId()), () -> admin.offset(row.getId(), new OffsetRequest(500)),
                () -> admin.verify(row.getId()), () -> admin.unverify(row.getId()))) {
                assertThatThrownBy(change::run).isInstanceOf(ConflictException.class)
                    .hasMessage("A scan is running");
            }
            assertThat(row.getOffsetMs()).isZero();
            assertThat(row.isOwnerAttached()).isFalse();
            assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        } finally {
            lock.release();
        }
    }

    @Test
    void aChangeTakesTheLockWhileItRunsAndGivesItBack() {
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", null, false);

        admin.offset(row.getId(), new OffsetRequest(500));

        assertThat(lock.holder()).isNull();
        assertThatThrownBy(() -> admin.offset(row.getId(), new OffsetRequest(600_001)))
            .isInstanceOf(BadRequestException.class);
        assertThat(lock.holder()).isNull();
    }
}
