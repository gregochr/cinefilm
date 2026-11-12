package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.ReferenceRead;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The timing check's database decisions with the repositories faked and no Spring context: what is due and what must
 * be read, what a save stores and when it sets the offset, and what Admin is told. (The same rules against H2 are in
 * {@code SidecarTimingServiceTest}.)
 */
@ExtendWith(MockitoExtension.class)
class SidecarTimingServiceUnitTest {

    private static final Instant LATER = NOW.plusSeconds(3_600);
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();
    private static final String CUES = SrtParser.format(
        STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList());

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private EditionRepository editions;

    @Mock
    private MediaFileRepository files;

    @Mock
    private EmbeddedCueStartsRepository cache;

    @Mock
    private ForcedSubtitleAlignmentRepository alignments;

    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private SidecarTimingService service;
    private Edition edition;
    private MediaFile film1;
    private SubtitleSidecar sidecar;

    @BeforeEach
    void setUp() {
        service = new SidecarTimingService(sidecars, editions, files, cache, alignments, lock, transactions);
        edition = edition(film(1L, "Taken", 2008, NOW), 40L, "Original", 90 * MIN, null);
        film1 = file(edition, "Taken.m4v");
        film1.setId(10L);
        film1.setSizeBytes(1_000L);
        film1.setModifiedAt(NOW);
        sidecar = new SubtitleSidecar();
        sidecar.setId(5L);
        sidecar.setPath("Taken.en.forced.srt");
        sidecar.setMediaFileId(10L);
        sidecar.setCuesText(CUES);
        sidecar.setCueCount(STARTS.size());
        sidecar.setUpdatedAt(NOW);
    }

    private void served() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdIn(List.of(10L))).thenReturn(List.of(sidecar));
    }

    private EmbeddedCueStarts read(List<Long> starts) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(10L);
        read.setFingerprint(TimingKeys.fingerprint(film1));
        read.setStatus(EmbeddedCueStarts.Status.USABLE);
        read.setStartsMs(starts);
        return read;
    }

    private static Result aligned(int offset) {
        return new Result(Verdict.ALIGNED, offset, 1.0, 12, 12, 10, List.of());
    }

    private FitJob job() {
        return new FitJob(1L, 5L, TimingKeys.identity(sidecar), "10:1000:" + NOW.toEpochMilli(), STARTS, STARTS, null);
    }

    /** What a save needs to find: the film's served sidecar and the file's read as it is now. */
    private void readyToSave() {
        served();
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of(read(STARTS)));
        when(alignments.findBySidecarId(5L)).thenReturn(Optional.empty());
    }

    private ForcedSubtitleAlignment saved() {
        ArgumentCaptor<ForcedSubtitleAlignment> captor = ArgumentCaptor.forClass(ForcedSubtitleAlignment.class);
        verify(alignments).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aFilmWhoseServedFileHasNoReadIsDueAndNeedsIt() {
        when(sidecars.findFilmIdsWithPresentSubtitle()).thenReturn(List.of(1L));
        served();
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of());
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of());

        assertThat(service.dueFilmIds()).containsExactly(1L);
        assertThat(service.readsFor(1L)).containsExactly(new ReferenceRead(10L, "Taken.m4v",
            "1000:" + NOW.toEpochMilli()));
    }

    @Test
    void aFilmWithACurrentFitIsNotDue() {
        when(sidecars.findFilmIdsWithPresentSubtitle()).thenReturn(List.of(1L));
        served();
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of(read(STARTS)));
        ForcedSubtitleAlignment fit = new ForcedSubtitleAlignment();
        fit.setSidecarId(5L);
        fit.setSidecarIdentity(TimingKeys.identity(sidecar));
        fit.setReferenceKey("10:1000:" + NOW.toEpochMilli());
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of(fit));

        assertThat(service.dueFilmIds()).isEmpty();
    }

    @Test
    void aFilmWithNoServedFileHasNothingDueOrToRead() {
        when(sidecars.findFilmIdsWithPresentSubtitle()).thenReturn(List.of());

        assertThat(service.dueFilmIds()).isEmpty();
        assertThat(service.readsFor(1L)).isEmpty();
        verify(alignments, never()).findBySidecarIdIn(List.of());
    }

    @Test
    void aReadIsStoredForTheFileAsItIsNow() {
        when(files.findById(10L)).thenReturn(Optional.of(film1));
        when(cache.findById(10L)).thenReturn(Optional.empty());
        ReferenceRead ask = new ReferenceRead(10L, "Taken.m4v", TimingKeys.fingerprint(film1));

        EmbeddedCueReader.Reading reading = new EmbeddedCueReader.Reading(EmbeddedCueStarts.Status.USABLE, 3, 2,
            "subrip", List.of(1_000L, 2_500L), null);

        SaveOutcome stored = service.saveReference(ask, reading, LATER);

        assertThat(stored).isEqualTo(SaveOutcome.SAVED);
        ArgumentCaptor<EmbeddedCueStarts> captor = ArgumentCaptor.forClass(EmbeddedCueStarts.class);
        verify(cache).save(captor.capture());
        EmbeddedCueStarts row = captor.getValue();
        assertThat(row.getMediaFileId()).isEqualTo(10L);
        assertThat(row.getFingerprint()).isEqualTo(ask.fingerprint());
        assertThat(row.getStartsMs()).containsExactly(1_000L, 2_500L);
        assertThat(row.getCueCount()).isEqualTo(2);
        assertThat(row.getCodec()).isEqualTo("subrip");
        assertThat(row.getReadAt()).isEqualTo(LATER);
    }

    @Test
    void aReadIsNotStoredWhileAScanOrAnEditHoldsTheLibraryLock() {
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        SaveOutcome stored = service.saveReference(new ReferenceRead(10L, "Taken.m4v", TimingKeys.fingerprint(film1)),
            new EmbeddedCueReader.Reading(EmbeddedCueStarts.Status.USABLE, 1, 2, "ass", List.of(1L), null), LATER);

        assertThat(stored).isEqualTo(SaveOutcome.BUSY);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        verify(files, never()).findById(10L);
    }

    @Test
    void aReadOfAFileThatChangedIsNotStored() {
        when(files.findById(10L)).thenReturn(Optional.of(film1));

        SaveOutcome stored = service.saveReference(new ReferenceRead(10L, "Taken.m4v", "1:1"),
            new EmbeddedCueReader.Reading(EmbeddedCueStarts.Status.USABLE, 1, 2, "ass", List.of(1L), null), LATER);

        assertThat(stored).isEqualTo(SaveOutcome.DROPPED);
        verify(cache, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anAlignedFitSetsTheOffsetAsAutomaticAndStoresTheRow() {
        readyToSave();

        SaveOutcome outcome = service.save(job(), aligned(900), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.APPLIED);
        assertThat(sidecar.getOffsetMs()).isEqualTo(900);
        assertThat(sidecar.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.AUTO);
        assertThat(sidecar.getUpdatedAt()).isEqualTo(LATER);
        ForcedSubtitleAlignment row = saved();
        assertThat(row.getVerdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(row.getFittedOffsetMs()).isEqualTo(900);
        assertThat(row.isApplied()).isTrue();
        assertThat(row.getSidecarId()).isEqualTo(5L);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anOwnersOffsetIsNotChangedButTheFitIsStored() {
        readyToSave();
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);

        assertThat(service.save(job(), aligned(900), LATER)).isEqualTo(SaveOutcome.SAVED);

        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(saved().isApplied()).isFalse();
    }

    @Test
    void aFitForAFileThatChangedIsDropped() {
        served();
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of(read(STARTS)));
        FitJob fitted = job();
        sidecar.setCuesText("1\n00:00:01,000 --> 00:00:02,000\nOther\n\n");

        assertThat(service.save(fitted, aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);

        verify(alignments, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(sidecar.getOffsetMs()).isZero();
    }

    @Test
    void aFitForAFileThatIsNoLongerServedIsDropped() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdIn(List.of(10L))).thenReturn(List.of());

        assertThat(service.save(job(), aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);
    }

    @Test
    void aTakenLibraryLockMeansBusyAndNothingIsRead() {
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        assertThat(service.save(job(), aligned(900), LATER)).isEqualTo(SaveOutcome.BUSY);

        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        verify(editions, never()).findWithFilesByFilmIdIn(Set.of(1L));
    }

    @Test
    void theRulesForSettingAnOffset() {
        SubtitleSidecar fresh = new SubtitleSidecar();
        assertThat(SidecarTimingService.shouldApply(aligned(100), fresh, List.of(10L))).isTrue();
        assertThat(SidecarTimingService.shouldApply(aligned(99), fresh, List.of(10L))).isFalse();
        assertThat(SidecarTimingService.shouldApply(new Result(Verdict.CHECK, 900, 1, 12, 12, 10, List.of()), fresh,
            List.of(10L))).isFalse();
        fresh.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        assertThat(SidecarTimingService.shouldApply(aligned(900), fresh, List.of(10L))).isTrue();
        fresh.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        assertThat(SidecarTimingService.shouldApply(aligned(900), fresh, List.of(10L))).isFalse();
        fresh.setOffsetSource(null);
        fresh.setOwnerVerifiedAt(NOW);
        fresh.setVerifiedParts("10");
        assertThat(SidecarTimingService.shouldApply(aligned(900), fresh, List.of(10L))).isFalse();
    }

    @Test
    void adminIsToldItWasAppliedOnlyWhileTheOffsetIsStillTheAutomaticFit() {
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of(read(STARTS)));
        ForcedSubtitleAlignment row = new ForcedSubtitleAlignment();
        row.setSidecarId(5L);
        row.setSidecarIdentity(TimingKeys.identity(sidecar));
        row.setReferenceKey("10:1000:" + NOW.toEpochMilli());
        row.setVerdict(Verdict.ALIGNED);
        row.setFittedOffsetMs(900);
        row.setApplied(true);
        row.setCheckedAt(LATER);
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of(row));
        sidecar.setOffsetMs(900);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);

        Map<Long, AlignmentDto> automatic = service.current(Map.of(5L, edition), List.of(sidecar));
        sidecar.setOffsetMs(0);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        Map<Long, AlignmentDto> reset = service.current(Map.of(5L, edition), List.of(sidecar));

        assertThat(automatic.get(5L).applied()).isTrue();
        assertThat(reset.get(5L).applied()).isFalse();
        assertThat(reset.get(5L).fittedOffsetMs()).isEqualTo(900);

        sidecar.setOffsetMs(500);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        assertThat(service.current(Map.of(5L, edition), List.of(sidecar)).get(5L).applied()).isFalse();
        sidecar.setOffsetMs(900);
        row.setVerdict(Verdict.CHECK);
        assertThat(service.current(Map.of(5L, edition), List.of(sidecar)).get(5L).applied()).isFalse();
    }
}
