package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsDetectionRepository.Stored;
import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsDetectionService.SaveOutcome;
import gregochr.cinefilm.credits.CreditsFrameReader.Job;
import gregochr.cinefilm.credits.CreditsFrameReader.ReadException;
import gregochr.cinefilm.credits.CreditsFrameReader.TimedFrame;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.subtitle.EmbeddedCueStarts;
import gregochr.cinefilm.subtitle.EmbeddedCueStartsRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The credits detection service's decisions with every collaborator faked and no Spring context: which files are due
 * and in what order, how a file is analysed (cue starts only from a current usable read, the candidate to dense to
 * refine flow, a failing reader), and the save (lock, revalidation, exact row, lock always released). The same rules
 * against H2 are in {@code CreditsDetectionServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class CreditsDetectionServiceUnitTest {

    private static final Path FILE = Path.of("/library/Taken.m4v");
    private static final long CREDITS_AT = 450_000;
    private static final long FILM_LENGTH = 10 * MIN;
    private static final String PRINT = "1001:" + NOW.toEpochMilli();
    private static final DueFile DUE = new DueFile(10L, "Taken.m4v", PRINT, FILM_LENGTH, "Taken");

    @Mock
    private EditionRepository editions;

    @Mock
    private MediaFileRepository files;

    @Mock
    private CreditsDetectionRepository detections;

    @Mock
    private EmbeddedCueStartsRepository cueStarts;

    @Mock
    private PlaybackStateRepository states;

    @Mock
    private CreditsFrameReader reader;

    @Mock
    private Job job;

    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private final Runnable checkpoint = () -> { };
    private CreditsDetectionService service;

    @BeforeEach
    void setUp() {
        service = new CreditsDetectionService(editions, files, detections, cueStarts, states, reader, lock,
            transactions);
    }

    // ---- Fixtures ------------------------------------------------------------------------------------------------

    /** An edition of {@code parts} one-hour files with ids {@code base + part}, a size and a time, added at a time. */
    private static Edition editionOf(long id, String title, int parts, Instant addedAt) {
        Edition built = edition(film(id, title, 2000, addedAt), id, "Original", parts * 60 * MIN, null);
        for (int i = 1; i <= parts; i++) {
            MediaFile file = file(built, parts == 1 ? title + ".m4v" : title + " - pt" + i + ".m4v");
            file.setId(id * 10 + i);
            file.setPartNumber(i);
            file.setDurationMs(60 * MIN);
            file.setSizeBytes(1_000L + i);
            file.setModifiedAt(NOW);
        }
        return built;
    }

    private static MediaFile part(Edition edition, int number) {
        return edition.originalParts().get(number - 1);
    }

    private List<Long> dueIds() {
        return service.dueFiles().stream().map(DueFile::mediaFileId).toList();
    }

    private void library(Edition... all) {
        when(editions.findAllWithFiles()).thenReturn(List.of(all));
    }

    private MediaFile savedFile() {
        MediaFile file = part(editionOf(1L, "Taken", 1, NOW), 1);
        file.setId(10L);
        file.setSizeBytes(1_001L);
        return file;
    }

    // ---- dueFiles ------------------------------------------------------------------------------------------------

    @Test
    void aFileWithNoStoredResultIsDueWithAllItsDetails() {
        Edition taken = editionOf(1L, "Taken", 1, NOW);
        library(taken);

        assertThat(service.dueFiles()).containsExactly(
            new DueFile(11L, "Taken.m4v", "1001:" + NOW.toEpochMilli(), 60 * MIN, "Taken"));
    }

    @Test
    void onlyTheLastPartInPlayOrderIsDue() {
        Edition split = editionOf(2L, "The Long Film", 3, NOW);
        library(split);

        assertThat(dueIds()).containsExactly(part(split, 3).getId());
    }

    @Test
    void aStoredResultWithTheSameFingerprintMakesTheFileDoneAndADifferentOneDueAgain() {
        Edition done = editionOf(1L, "Done", 1, NOW);
        Edition changed = editionOf(2L, "Changed", 1, NOW.minusSeconds(1));
        when(detections.findAllStored()).thenReturn(List.of(
            new Stored(part(done, 1).getId(), part(done, 1).fingerprint()),
            new Stored(part(changed, 1).getId(), "1001:1")));
        library(done, changed);

        assertThat(dueIds()).containsExactly(part(changed, 1).getId());
    }

    @Test
    void aStoredResultOfAnEarlierPartDoesNotCountForTheLastPart() {
        Edition split = editionOf(2L, "The Long Film", 2, NOW);
        when(detections.findAllStored()).thenReturn(List.of(
            new Stored(part(split, 1).getId(), part(split, 1).fingerprint())));
        library(split);

        assertThat(dueIds()).containsExactly(part(split, 2).getId());
    }

    @Test
    void filesTheAnalysisCannotReadAreNotDue() {
        Edition missing = editionOf(1L, "Missing", 1, NOW);
        part(missing, 1).setPresence(Presence.MISSING);
        Edition broken = editionOf(2L, "Broken", 1, NOW);
        part(broken, 1).setPlayability(Playability.NEEDS_CONVERSION);
        Edition noSize = editionOf(3L, "No size", 1, NOW);
        part(noSize, 1).setSizeBytes(null);
        Edition noTime = editionOf(4L, "No time", 1, NOW);
        part(noTime, 1).setModifiedAt(null);
        Edition noDuration = editionOf(5L, "No duration", 1, NOW);
        part(noDuration, 1).setDurationMs(null);
        Edition zeroDuration = editionOf(6L, "Zero duration", 1, NOW);
        part(zeroDuration, 1).setDurationMs(0L);
        Edition noOriginal = editionOf(7L, "Premade only", 1, NOW);
        part(noOriginal, 1).setKind(MediaKind.PREMADE_720);
        Edition fine = editionOf(8L, "Fine", 1, NOW);
        library(missing, broken, noSize, noTime, noDuration, zeroDuration, noOriginal, fine);

        assertThat(dueIds()).containsExactly(part(fine, 1).getId());
    }

    @Test
    void theVideoDurationIsTheLengthWhenKnownElseTheContainers() {
        Edition withVideo = editionOf(1L, "With video", 1, NOW);
        part(withVideo, 1).setVideoDurationMs(59 * MIN);
        library(withVideo);

        assertThat(service.dueFiles()).extracting(DueFile::durationMs).containsExactly(59 * MIN);
    }

    @Test
    void editionsInProgressComeFirstThenTheNewestAddedThenTheNewestId() {
        Edition oldest = editionOf(1L, "Oldest", 1, NOW.minus(Duration.ofDays(30)));
        Edition newest = editionOf(2L, "Newest", 1, NOW.minus(Duration.ofDays(1)));
        Edition middle = editionOf(3L, "Middle", 1, NOW.minus(Duration.ofDays(10)));
        Edition tieLow = editionOf(4L, "Tie low", 1, NOW.minus(Duration.ofDays(40)));
        Edition tieHigh = editionOf(5L, "Tie high", 1, NOW.minus(Duration.ofDays(40)));
        when(states.findInProgressEditionIds()).thenReturn(List.of(oldest.getId()));
        library(tieLow, oldest, newest, tieHigh, middle);

        assertThat(dueIds()).containsExactly(part(oldest, 1).getId(), part(newest, 1).getId(),
            part(middle, 1).getId(), part(tieHigh, 1).getId(), part(tieLow, 1).getId());
    }

    @Test
    void nothingInTheLibraryMeansNothingDue() {
        library();

        assertThat(service.dueFiles()).isEmpty();
    }

    // ---- detect --------------------------------------------------------------------------------------------------

    /** Five picture frames for the active area. */
    private static List<GreyFrame> activeFrames() {
        List<GreyFrame> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            frames.add(SyntheticFrames.picture());
        }
        return frames;
    }

    /** Coarse frames 360 to 595 s: picture until {@link #CREDITS_AT}, then a crawl. */
    private static List<TimedFrame> coarseFrames(boolean withCredits) {
        List<TimedFrame> frames = new ArrayList<>();
        for (long at = 360_000; at < FILM_LENGTH; at += 5_000) {
            frames.add(new TimedFrame(at, withCredits && at >= CREDITS_AT
                ? SyntheticFrames.crawl((int) ((at - CREDITS_AT) / 500), 3) : SyntheticFrames.picture()));
        }
        return frames;
    }

    /** Dense frames 360 to 540 s of the same film. */
    private static List<DenseFrame> denseFrames() {
        List<DenseFrame> frames = new ArrayList<>();
        for (long at = 360_000; at < 540_000; at += 500) {
            frames.add(new DenseFrame(at, at < CREDITS_AT ? SyntheticFrames.picture()
                : SyntheticFrames.crawl((int) ((at - CREDITS_AT) / 500), 3)));
        }
        return frames;
    }

    private void jobReads(boolean withCredits) {
        when(reader.open(FILE, FILM_LENGTH, checkpoint)).thenReturn(job);
        when(job.activeAreaFrames()).thenReturn(activeFrames());
        when(job.coarseFrames()).thenReturn(coarseFrames(withCredits));
    }

    private void cueRow(String fingerprint, EmbeddedCueStarts.Status status, List<Long> starts) {
        EmbeddedCueStarts row = new EmbeddedCueStarts();
        row.setMediaFileId(10L);
        row.setFingerprint(fingerprint);
        row.setStatus(status);
        row.setSubtitleStreamCount(2);
        row.setStartsMs(starts);
        row.setReadAt(NOW);
        when(cueStarts.findById(10L)).thenReturn(Optional.of(row));
    }

    @Test
    void aCandidateIsRefinedOnTheDenseFramesAroundItAndTheJobIsClosed() {
        jobReads(true);
        when(job.denseFrames(CREDITS_AT)).thenReturn(denseFrames());

        CreditsResult result = service.detect(DUE, FILE, checkpoint);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isBetween(CREDITS_AT, CREDITS_AT + 1_000);
        verify(job).denseFrames(CREDITS_AT);
        verify(job).close();
    }

    @Test
    void withNoCandidateTheResultIsNotFoundAndNoDenseDecodeIsAsked() {
        jobReads(false);

        CreditsResult result = service.detect(DUE, FILE, checkpoint);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(result.offsetMs()).isNull();
        assertThat(result.reasons()).containsExactly("The ending has no black screen and no text on a dark screen");
        verify(job, never()).denseFrames(CREDITS_AT);
        verify(job).close();
    }

    @Test
    void cueStartsFromACurrentUsableReadAreUsedSoLateDialogueMakesTheResultUnsure() {
        jobReads(true);
        when(job.denseFrames(CREDITS_AT)).thenReturn(denseFrames());
        cueRow(PRINT, EmbeddedCueStarts.Status.USABLE, List.of(300_000L, 460_000L, 485_000L));

        CreditsResult result = service.detect(DUE, FILE, checkpoint);

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.UNSURE);
        assertThat(result.reasons()).singleElement().asString().startsWith("Someone is still talking");
    }

    @Test
    void cueStartsFromAnOlderVersionOfTheFileAreIgnored() {
        jobReads(true);
        when(job.denseFrames(CREDITS_AT)).thenReturn(denseFrames());
        cueRow("1001:1", EmbeddedCueStarts.Status.USABLE, List.of(300_000L, 460_000L, 485_000L));

        assertThat(service.detect(DUE, FILE, checkpoint).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void aReadWithNoUsableTrackGivesNoCueStarts() {
        jobReads(true);
        when(job.denseFrames(CREDITS_AT)).thenReturn(denseFrames());
        cueRow(PRINT, EmbeddedCueStarts.Status.NO_REFERENCE, List.of());

        assertThat(service.detect(DUE, FILE, checkpoint).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void aFileWithNoReadAtAllIsAnalysedWithoutCueStarts() {
        jobReads(true);
        when(job.denseFrames(CREDITS_AT)).thenReturn(denseFrames());
        when(cueStarts.findById(10L)).thenReturn(Optional.empty());

        assertThat(service.detect(DUE, FILE, checkpoint).verdict()).isEqualTo(CreditsVerdict.FOUND);
    }

    @Test
    void aReaderThatFailsMidFileAbandonsTheFileClosesTheJobAndPassesTheFailureOn() {
        when(reader.open(FILE, FILM_LENGTH, checkpoint)).thenReturn(job);
        when(job.activeAreaFrames()).thenReturn(activeFrames());
        ReadException failure = new ReadException("ffmpeg timed out reading the frame at 370.000");
        when(job.coarseFrames()).thenThrow(failure);

        assertThatThrownBy(() -> service.detect(DUE, FILE, checkpoint)).isSameAs(failure);

        verify(job).close();
    }

    @Test
    void aFailingDenseDecodeAlsoClosesTheJob() {
        jobReads(true);
        ReadException failure = new ReadException("ffmpeg exited with status 1 reading the frames around 450.000");
        when(job.denseFrames(CREDITS_AT)).thenThrow(failure);

        assertThatThrownBy(() -> service.detect(DUE, FILE, checkpoint)).isSameAs(failure);

        verify(job).close();
    }

    @Test
    void aJobThatCannotBeOpenedFailsTheFileWithNothingToClose() {
        ReadException failure = new ReadException("The frame directory could not be made");
        when(reader.open(FILE, FILM_LENGTH, checkpoint)).thenThrow(failure);

        assertThatThrownBy(() -> service.detect(DUE, FILE, checkpoint)).isSameAs(failure);

        verifyNoInteractions(job);
    }

    // ---- save ----------------------------------------------------------------------------------------------------

    private static CreditsResult found() {
        return new CreditsResult(CreditsVerdict.FOUND, 3_723_500L, List.of("Text moves up", "Nobody is talking"));
    }

    @Test
    void successWritesExactlyTheRowFieldsAndReleasesTheLock() {
        MediaFile file = savedFile();
        when(files.findById(10L)).thenReturn(Optional.of(file));
        when(detections.findById(10L)).thenReturn(Optional.empty());

        SaveOutcome outcome = service.save(DUE, found(), NOW);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        ArgumentCaptor<CreditsDetection> saved = ArgumentCaptor.forClass(CreditsDetection.class);
        verify(detections).save(saved.capture());
        CreditsDetection row = saved.getValue();
        assertThat(row.getMediaFileId()).isEqualTo(10L);
        assertThat(row.getFingerprint()).isEqualTo(PRINT);
        assertThat(row.getVerdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(row.getOffsetMs()).isEqualTo(3_723_500L);
        assertThat(row.getReasons()).isEqualTo("Text moves up\nNobody is talking");
        assertThat(row.getCheckedAt()).isEqualTo(NOW);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anExistingRowIsUpdatedNotReplacedAndANotFoundResultClearsTheOffset() {
        MediaFile file = savedFile();
        when(files.findById(10L)).thenReturn(Optional.of(file));
        CreditsDetection existing = new CreditsDetection();
        existing.setMediaFileId(10L);
        existing.setFingerprint("old");
        existing.setVerdict(CreditsVerdict.FOUND);
        existing.setOffsetMs(99L);
        existing.setReasons("old");
        existing.setCheckedAt(NOW.minusSeconds(60));
        when(detections.findById(10L)).thenReturn(Optional.of(existing));

        service.save(DUE, new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("None")), NOW);

        verify(detections).save(existing);
        assertThat(existing.getVerdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(existing.getOffsetMs()).isNull();
        assertThat(existing.getFingerprint()).isEqualTo(PRINT);
        assertThat(existing.getReasons()).isEqualTo("None");
        assertThat(existing.getCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void reasonsAreCutAtTwoThousandCharactersAndNoShorter() {
        MediaFile file = savedFile();
        when(files.findById(10L)).thenReturn(Optional.of(file));
        when(detections.findById(10L)).thenReturn(Optional.empty());

        service.save(DUE, new CreditsResult(CreditsVerdict.UNSURE, null, List.of("x".repeat(2_001))), NOW);
        service.save(DUE, new CreditsResult(CreditsVerdict.UNSURE, null, List.of("y".repeat(2_000))), LATER);

        ArgumentCaptor<CreditsDetection> saved = ArgumentCaptor.forClass(CreditsDetection.class);
        verify(detections, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getReasons()).isEqualTo("x".repeat(2_000));
        assertThat(saved.getAllValues().get(1).getReasons()).isEqualTo("y".repeat(2_000));
    }

    private static final Instant LATER = NOW.plusSeconds(1);

    @Test
    void aRefusedLockIsBusyAndTouchesNothingAndLeavesTheHoldersLock() {
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        SaveOutcome outcome = service.save(DUE, found(), NOW);

        assertThat(outcome).isEqualTo(SaveOutcome.BUSY);
        verifyNoInteractions(files, detections);
        assertThat(lock.heldBy(LibraryLock.SCAN)).isTrue();
    }

    @Test
    void aFileThatIsGoneIsDroppedAndTheLockIsReleased() {
        when(files.findById(10L)).thenReturn(Optional.empty());

        assertThat(service.save(DUE, found(), NOW)).isEqualTo(SaveOutcome.DROPPED);

        verifyNoInteractions(detections);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFileThatIsNoLongerPresentIsDroppedAndTheLockIsReleased() {
        MediaFile file = savedFile();
        file.setPresence(Presence.MISSING);
        when(files.findById(10L)).thenReturn(Optional.of(file));

        assertThat(service.save(DUE, found(), NOW)).isEqualTo(SaveOutcome.DROPPED);

        verifyNoInteractions(detections);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFileWhoseFingerprintChangedIsDroppedAndTheLockIsReleased() {
        MediaFile file = savedFile();
        file.setSizeBytes(2_000L);
        when(files.findById(10L)).thenReturn(Optional.of(file));

        assertThat(service.save(DUE, found(), NOW)).isEqualTo(SaveOutcome.DROPPED);

        verifyNoInteractions(detections);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void theLockIsReleasedWhenTheWriteThrows() {
        MediaFile file = savedFile();
        when(files.findById(10L)).thenReturn(Optional.of(file));
        when(detections.findById(10L)).thenReturn(Optional.empty());
        IllegalStateException failure = new IllegalStateException("database is down");
        when(detections.save(any(CreditsDetection.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.save(DUE, found(), NOW)).isSameAs(failure);

        assertThat(lock.holder()).isNull();
    }

    @Test
    void theLockIsReleasedWhenTheFileLookupThrows() {
        IllegalStateException failure = new IllegalStateException("database is down");
        when(files.findById(10L)).thenThrow(failure);

        assertThatThrownBy(() -> service.save(DUE, found(), NOW)).isSameAs(failure);

        assertThat(lock.holder()).isNull();
    }

    @Test
    void theLockIsHeldByCreditsWhileTheWriteRuns() {
        MediaFile file = savedFile();
        when(files.findById(10L)).thenAnswer(call -> {
            assertThat(lock.heldBy(LibraryLock.CREDITS)).isTrue();
            return Optional.of(file);
        });
        when(detections.findById(10L)).thenReturn(Optional.empty());

        service.save(DUE, found(), NOW);

        assertThat(lock.holder()).isNull();
    }
}
