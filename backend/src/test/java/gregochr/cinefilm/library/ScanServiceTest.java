package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryIngest.Pending;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scan's decisions, with the database, ffprobe and TMDB faked. */
class ScanServiceTest {

    private static final Instant T1 = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2024-02-02T10:00:00Z");
    private static final MatchOutcome UNAVAILABLE = new MatchOutcome(Status.UNAVAILABLE, null, List.of());

    @TempDir
    Path library;

    private final LibraryScanner scanner = mock(LibraryScanner.class);
    private final FfprobeService ffprobe = mock(FfprobeService.class);
    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final TmdbMatcher matcher = mock(TmdbMatcher.class);
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private final List<Runnable> queued = new ArrayList<>();
    private ScanService service;
    private ScanService deferred;

    @BeforeEach
    void setUp() {
        LibraryRoot root = new LibraryRoot(library.toString());
        service = new ScanService(root, scanner, ffprobe, tmdb, matcher, ingest, Runnable::run, CLOCK);
        deferred = new ScanService(root, scanner, ffprobe, tmdb, matcher, ingest, queued::add, CLOCK);
        when(tmdb.enabled()).thenReturn(true);
        when(ingest.startRun("admin", CLOCK.instant())).thenReturn(5L);
        when(ingest.known()).thenReturn(Map.of());
    }

    private static FoundFile found(String path, long size, Instant modified) {
        return new FoundFile(path, size, modified, T1, false);
    }

    private static Known known(long id, String path, Long size, Instant modified, Presence presence,
                               Playability playability) {
        return new Known(id, path, size, modified, presence, playability);
    }

    private static ProbeResult probe(long durationMs, String embeddedTitle, Integer year, boolean drm) {
        return new ProbeResult(durationMs, "mp4", "H.264", 1920, 800, 9_000_000L, List.of(), List.of(),
            new ProbeResult.Tags(embeddedTitle, year, null, null, null), drm);
    }

    private void disk(FoundFile... files) throws IOException {
        for (FoundFile file : files) {
            Path path = library.resolve(file.path());
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
        when(scanner.scan()).thenReturn(new Found(List.of(files), 0));
    }

    private static MatchOutcome matched(long id) {
        return new MatchOutcome(Status.MATCHED, new MovieDetails(id, "Film", "Film", 2000, null, 100, List.of(), null,
            null, 1, List.of(), List.of(), null), List.of());
    }

    @Test
    void aNewPlayableFileIsProbedGuessedMatchedAndAdded() throws IOException {
        FoundFile file = found("Heat (1995).m4v", 10, T1);
        ProbeResult probe = probe(170 * 60_000L, null, null, false);
        MatchOutcome outcome = matched(949);
        disk(file);
        when(ffprobe.probe(library.resolve("Heat (1995).m4v").toRealPath())).thenReturn(probe);
        when(matcher.match(new TitleGuess("Heat", 1995), 170 * 60_000L)).thenReturn(outcome);

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Heat", 1995), outcome);
        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 1, 0, null);
        assertThat(service.isRunning()).isFalse();
        assertThat(service.progress()).isNull();
    }

    @Test
    void theEmbeddedTitleAndDateTagGuideTheLookup() throws IOException {
        FoundFile file = found("a.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, "10 Cloverfield Lane", 2016, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(matcher.match(new TitleGuess("10 Cloverfield Lane", 2016), 60_000L)).thenReturn(matched(1));

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("10 Cloverfield Lane", 2016),
            matched(1));
    }

    @Test
    void aFairPlayFileIsAddedAsProtectedWithoutAskingTmdb() throws IOException {
        FoundFile file = found("Drm.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, true);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);

        service.start("admin");

        verify(ingest).addNew(file, Playability.PROTECTED, probe, new TitleGuess("Drm", null), null);
        verify(matcher, never()).match(any(), anyLong());
    }

    @Test
    void anAviIsAddedAsNeedingConversionWithoutAskingTmdb() throws IOException {
        FoundFile file = new FoundFile("Old.avi", 10, T1, T1, true);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);

        service.start("admin");

        verify(ingest).addNew(file, Playability.NEEDS_CONVERSION, probe, new TitleGuess("Old", null), null);
        verify(matcher, never()).match(any(), anyLong());
    }

    @Test
    void anAviThatCannotBeProbedIsStillRecordedAsNeedingConversion() throws IOException {
        FoundFile file = new FoundFile("Old.avi", 10, T1, T1, true);
        disk(file);
        when(ffprobe.probe(any())).thenThrow(new ProbeException("boom", null));

        service.start("admin");

        verify(ingest).addNew(file, Playability.NEEDS_CONVERSION, null, new TitleGuess("Old", null), null);
    }

    @Test
    void aPlayableFileThatCannotBeProbedIsRecordedAsUnreadableForALaterRetry() throws IOException {
        FoundFile file = found("Corrupt.m4v", 10, T1);
        disk(file);
        when(ffprobe.probe(any())).thenThrow(new ProbeException("ffprobe exited with status 1", null));

        service.start("admin");

        verify(ingest).addNew(file, Playability.UNREADABLE, null, new TitleGuess("Corrupt", null), null);
        verify(matcher, never()).match(any(), anyLong());
        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 1, 0, null);
    }

    @Test
    void aFileThatVanishesBetweenTheWalkAndTheProbeIsUnreadableNotACrash() {
        FoundFile file = found("Gone.m4v", 10, T1);
        when(scanner.scan()).thenReturn(new Found(List.of(file), 0));

        service.start("admin");

        verify(ingest).addNew(file, Playability.UNREADABLE, null, new TitleGuess("Gone", null), null);
    }

    @Test
    void anUnchangedKnownFileIsLeftAloneWithoutProbing() throws IOException {
        FoundFile file = found("Heat.m4v", 10, T1);
        disk(file);
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ffprobe, never()).probe(any());
        verify(ingest, never()).addNew(any(), any(), any(), any(), any());
        verify(ingest, never()).refresh(anyLong(), any(), any(), any());
        verify(ingest, never()).markPresent(anyLong());
        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 0, null);
    }

    @Test
    void aMissingFileThatIsBackAndUnchangedIsMarkedPresentAgain() throws IOException {
        disk(found("Heat.m4v", 10, T1));
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.MISSING, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).markPresent(1L);
        verify(ffprobe, never()).probe(any());
    }

    @Test
    void aTimeDifferenceOfOneMillisecondCountsAsChanged() throws IOException {
        FoundFile file = found("Heat.m4v", 10, T1.plusMillis(1));
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).refresh(1L, file, Playability.PLAYABLE, probe);
    }

    @Test
    void aSubMillisecondTimeDifferenceDoesNotCountAsChanged() throws IOException {
        disk(found("Heat.m4v", 10, T1.plusNanos(999_999)));
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ffprobe, never()).probe(any());
    }

    @Test
    void aFileWhoseSizeChangedIsReprobedAndRefreshed() throws IOException {
        FoundFile file = found("Heat.m4v", 11, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).refresh(1L, file, Playability.PLAYABLE, probe);
        verify(ingest, never()).addNew(any(), any(), any(), any(), any());
    }

    @Test
    void aStoredFileWithNoRecordedSizeOrTimeIsTreatedAsChanged() throws IOException {
        FoundFile file = found("Heat.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", null, T1, Presence.PRESENT, Playability.PLAYABLE)));
        service.start("admin");
        verify(ingest).refresh(1L, file, Playability.PLAYABLE, probe);

        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, null, Presence.PRESENT, Playability.PLAYABLE)));
        service.start("admin");
        verify(ingest, times(2)).refresh(1L, file, Playability.PLAYABLE, probe);
    }

    @Test
    void anUnreadableFileIsProbedAgainEvenWhenUnchanged() throws IOException {
        FoundFile file = found("Heat.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.UNREADABLE)));

        service.start("admin");

        verify(ingest).refresh(1L, file, Playability.PLAYABLE, probe);
    }

    @Test
    void aRenamedFileKeepsItsEditionAndIsNotMarkedMissing() throws IOException {
        FoundFile renamed = found("New Name.m4v", 10, T1);
        disk(renamed);
        when(ingest.known()).thenReturn(Map.of("Old Name.m4v",
            known(1, "Old Name.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).repoint(1L, renamed);
        verify(ingest).markMissing(List.of());
        verify(ingest, never()).addNew(any(), any(), any(), any(), any());
        verify(ffprobe, never()).probe(any());
        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 0, null);
    }

    @Test
    void aStoredFileStillOnDiskIsNotStolenAsTheSourceOfARename() throws IOException {
        FoundFile existing = found("Heat.m4v", 10, T1);
        FoundFile copy = found("Heat copy.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(existing, copy);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(tmdb.enabled()).thenReturn(false);
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest, never()).repoint(anyLong(), any());
        verify(ingest).addNew(copy, Playability.PLAYABLE, probe, new TitleGuess("Heat copy", null), null);
    }

    @Test
    void oneVanishedFileIsClaimedByOnlyOneNewFile() throws IOException {
        FoundFile first = found("A.m4v", 10, T1);
        FoundFile second = found("B.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(first, second);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(tmdb.enabled()).thenReturn(false);
        when(ingest.known()).thenReturn(Map.of("Old.m4v",
            known(1, "Old.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).repoint(1L, first);
        verify(ingest).addNew(second, Playability.PLAYABLE, probe, new TitleGuess("B", null), null);
    }

    @Test
    void aVanishedFileWithADifferentSizeOrTimeIsNotARename() throws IOException {
        FoundFile file = found("New.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(tmdb.enabled()).thenReturn(false);
        when(ingest.known()).thenReturn(Map.of(
            "SizeDiffers.m4v", known(1, "SizeDiffers.m4v", 11L, T1, Presence.PRESENT, Playability.PLAYABLE),
            "TimeDiffers.m4v", known(2, "TimeDiffers.m4v", 10L, T2, Presence.PRESENT, Playability.PLAYABLE),
            "NoSize.m4v", known(3, "NoSize.m4v", null, T1, Presence.PRESENT, Playability.PLAYABLE),
            "NoTime.m4v", known(4, "NoTime.m4v", 10L, null, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest, never()).repoint(anyLong(), any());
        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("New", null), null);
    }

    @Test
    void filesThatHaveVanishedAreMarkedMissingNeverDeleted() throws IOException {
        disk(found("Stays.m4v", 10, T1));
        when(ingest.known()).thenReturn(Map.of(
            "Stays.m4v", known(1, "Stays.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE),
            "Gone.m4v", known(2, "Gone.m4v", 20L, T2, Presence.PRESENT, Playability.PLAYABLE),
            "AlreadyMissing.m4v", known(3, "AlreadyMissing.m4v", 30L, T2, Presence.MISSING,
                Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).markMissing(List.of(2L));
        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 1, null);
    }

    @Test
    void anEmptyFolderWhenTheLibraryHoldsFilesIsAnUnmountedShareNotAMassDeletion() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest, never()).markMissing(any());
        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0,
            "The library folder is empty or unavailable; nothing was marked missing");
    }

    @Test
    void anEmptyFolderAndAnEmptyLibraryIsAnOrdinaryEmptyScan() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, null);
    }

    @Test
    void anEmptyFolderWhenOnlyMissingFilesAreKnownIsAnOrdinaryEmptyScan() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.MISSING, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, null);
    }

    @Test
    void anUnreadableLibraryFolderIsRecordedAsAnErrorNotThrown() {
        when(scanner.scan()).thenThrow(new UncheckedIOException("x", new IOException("no such folder")));

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, "Cannot read the library folder");
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void anUnexpectedFailureIsRecordedWithoutItsMessage() {
        when(scanner.scan()).thenThrow(new IllegalStateException("secret detail"));

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, "Scan failed: IllegalStateException");
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void oneFileThatFailsIsCountedAndTheRestOfTheScanContinues() throws IOException {
        FoundFile bad = found("Bad.m4v", 10, T1);
        FoundFile good = found("Good.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(bad, good);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(tmdb.enabled()).thenReturn(false);
        doThrow(new IllegalStateException("db")).when(ingest)
            .addNew(bad, Playability.PLAYABLE, probe, new TitleGuess("Bad", null), null);

        service.start("admin");

        verify(ingest).addNew(good, Playability.PLAYABLE, probe, new TitleGuess("Good", null), null);
        verify(ingest).finishRun(5L, CLOCK.instant(), 2, 1, 0, "1 files could not be processed");
    }

    @Test
    void filesRefusedForEscapingTheRootAreReported() throws IOException {
        disk(found("Heat.m4v", 10, T1));
        when(scanner.scan()).thenReturn(new Found(List.of(found("Heat.m4v", 10, T1)), 2));
        when(ingest.known()).thenReturn(Map.of("Heat.m4v",
            known(1, "Heat.m4v", 10L, T1, Presence.PRESENT, Playability.PLAYABLE)));

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 0, "2 files outside the library folder were refused");
    }

    @Test
    void refusedAndFailedFilesAreBothReported() throws IOException {
        FoundFile bad = found("Bad.m4v", 10, T1);
        when(scanner.scan()).thenReturn(new Found(List.of(bad), 1));
        when(ingest.known()).thenReturn(Map.of("Bad.m4v",
            known(1, "Bad.m4v", 99L, T1, Presence.PRESENT, Playability.PLAYABLE)));
        doThrow(new IllegalStateException("db")).when(ingest).refresh(anyLong(), any(), any(), any());

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 0,
            "1 files could not be processed; 1 files outside the library folder were refused");
    }

    @Test
    void aTmdbOutageStopsFurtherLookupsInThisScanButEveryFileIsStillAdded() throws IOException {
        FoundFile first = found("A.m4v", 10, T1);
        FoundFile second = found("B.m4v", 11, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(first, second);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(matcher.match(any(), anyLong())).thenReturn(UNAVAILABLE);

        service.start("admin");

        verify(matcher, times(1)).match(any(), anyLong());
        verify(ingest).addNew(first, Playability.PLAYABLE, probe, new TitleGuess("A", null), null);
        verify(ingest).addNew(second, Playability.PLAYABLE, probe, new TitleGuess("B", null), null);
        verify(ingest, never()).pending();
        verify(ingest).finishRun(5L, CLOCK.instant(), 2, 2, 0, null);
    }

    @Test
    void withoutATokenNoLookupIsAttemptedAndFilmsAreLeftPending() throws IOException {
        FoundFile file = found("A.m4v", 10, T1);
        ProbeResult probe = probe(60_000L, null, null, false);
        disk(file);
        when(ffprobe.probe(any())).thenReturn(probe);
        when(tmdb.enabled()).thenReturn(false);

        service.start("admin");

        verify(matcher, never()).match(any(), anyLong());
        verify(ingest, never()).pending();
        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("A", null), null);
    }

    @Test
    void filmsPendingFromAnEarlierOutageAreRetriedAndAppliedAtTheEndOfTheScan() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        when(ingest.pending()).thenReturn(List.of(new Pending(10L, "Heat", 1995, 170 * 60_000L),
            new Pending(11L, "Alien", null, 117 * 60_000L)));
        MatchOutcome heat = matched(949);
        MatchOutcome alien = new MatchOutcome(Status.NEEDS_MATCH, null, List.of());
        when(matcher.match(new TitleGuess("Heat", 1995), 170 * 60_000L)).thenReturn(heat);
        when(matcher.match(new TitleGuess("Alien", null), 117 * 60_000L)).thenReturn(alien);

        service.start("admin");

        verify(ingest).applyOutcome(10L, heat);
        verify(ingest).applyOutcome(11L, alien);
    }

    @Test
    void aTmdbOutageDuringTheRetryStopsTheRetries() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        when(ingest.pending()).thenReturn(List.of(new Pending(10L, "Heat", 1995, 1L),
            new Pending(11L, "Alien", null, 2L), new Pending(12L, "Dune", null, 3L)));
        when(matcher.match(new TitleGuess("Heat", 1995), 1L)).thenReturn(matched(949));
        when(matcher.match(new TitleGuess("Alien", null), 2L)).thenReturn(UNAVAILABLE);

        service.start("admin");

        verify(ingest).applyOutcome(eq(10L), any());
        verify(ingest, never()).applyOutcome(eq(11L), any());
        verify(matcher, never()).match(eq(new TitleGuess("Dune", null)), anyLong());
    }

    @Test
    void onlyOneScanRunsAtATimeAndTheNextIsRefused() {
        deferred.start("admin");

        assertThat(deferred.isRunning()).isTrue();
        assertThat(deferred.progress()).isEqualTo(new ScanService.Progress(0, 0, null));
        assertThatThrownBy(() -> deferred.start("nightly")).isInstanceOf(ScanAlreadyRunningException.class)
            .hasMessage("A scan is already running");
        assertThat(queued).hasSize(1);
    }

    @Test
    void aFinishedScanAllowsTheNextOne() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        deferred.start("admin");
        queued.get(0).run();

        assertThat(deferred.isRunning()).isFalse();
        assertThat(deferred.progress()).isNull();
        deferred.start("admin");
        assertThat(queued).hasSize(2);
    }

    @Test
    void progressShowsTheFileBeingHandledAndHowManyAreDone() throws IOException {
        FoundFile first = found("A.m4v", 10, T1);
        FoundFile second = found("B.m4v", 11, T1);
        disk(first, second);
        List<ScanService.Progress> seen = new ArrayList<>();
        when(ffprobe.probe(any())).thenAnswer(invocation -> {
            seen.add(deferred.progress());
            return probe(60_000L, null, null, false);
        });
        when(tmdb.enabled()).thenReturn(false);

        deferred.start("admin");
        queued.get(0).run();

        assertThat(seen).containsExactly(new ScanService.Progress(2, 0, "A.m4v"),
            new ScanService.Progress(2, 1, "B.m4v"));
    }

    @Test
    void anExecutorThatRefusesTheScanDoesNotLeaveTheServiceStuckRunning() {
        Executor refusing = command -> {
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        };
        ScanService stopped = new ScanService(new LibraryRoot(library.toString()), scanner, ffprobe, tmdb, matcher,
            ingest, refusing, CLOCK);

        assertThatThrownBy(() -> stopped.start("admin")).isInstanceOf(
            java.util.concurrent.RejectedExecutionException.class);
        assertThat(stopped.isRunning()).isFalse();
    }
}
