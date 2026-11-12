package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
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
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private SubtitleAdminService service;
    private SubtitleSidecar row;
    private Edition split;

    @BeforeEach
    void setUp() {
        service = new SubtitleAdminService(sidecars, files, editions, sync, CLOCK, lock, transactions);
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
        when(sidecars.findAllByOrderByPathAscIdAsc()).thenReturn(List.of(row));
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
        when(files.findAllById(java.util.Set.of(10L))).thenReturn(List.of(split.originalParts().get(0)));

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
        when(files.findAllById(java.util.Set.of(20L))).thenReturn(List.of(split.originalParts().get(1)));

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
        when(files.findAllById(java.util.Set.of(10L))).thenReturn(List.of(split.originalParts().get(0)));
        service.verify(5L);

        assertThat(row.getOwnerVerifiedAt()).isEqualTo(NOW);
        assertThat(row.getVerifiedParts()).isEqualTo("10,20");

        service.unverify(5L);
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
    }

    @Test
    void detachClearsTheAttachmentAndMatchesByNameAgain() {
        listAndFind();
        row.setOwnerAttached(true);
        row.setMediaFileId(10L);
        row.setWholeEdition(true);

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
}
