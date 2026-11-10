package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

/** How the scan hands a new file to grouping: what it searches for, when it does not search, and the passes it runs. */
class ScanServiceGroupingTest {

    private static final Instant T1 = Instant.parse("2024-01-01T10:00:00Z");
    private static final long MIN = 60_000L;
    private static final MatchOutcome UNAVAILABLE = new MatchOutcome(Status.UNAVAILABLE, null, List.of());

    @TempDir
    Path library;

    private final LibraryScanner scanner = mock(LibraryScanner.class);
    private final FfprobeService ffprobe = mock(FfprobeService.class);
    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final TmdbMatcher matcher = mock(TmdbMatcher.class);
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private final LibraryGrouping grouping = mock(LibraryGrouping.class);
    private ScanService service;
    private ProbeResult probe;

    @BeforeEach
    void setUp() {
        service = new ScanService(new LibraryRoot(library.toString()), scanner, ffprobe, tmdb, matcher, ingest,
            Runnable::run, CLOCK, new LibraryLock(), event -> { }, grouping);
        when(tmdb.enabled()).thenReturn(true);
        when(ingest.startRun("admin", CLOCK.instant())).thenReturn(5L);
        when(ingest.known()).thenReturn(Map.of());
    }

    private FoundFile newFile(String path, long durationMin) throws IOException {
        FoundFile file = new FoundFile(path, 10, T1, T1, false);
        Path on = library.resolve(path);
        Files.createDirectories(on.getParent());
        Files.writeString(on, "x");
        when(scanner.scan()).thenReturn(new Found(List.of(file), 0));
        probe = new ProbeResult(durationMin * MIN, "mp4", "H.264", 1920, 800, 9_000_000L, List.of(), List.of(),
            new ProbeResult.Tags(null, null, null, null, null), false);
        when(ffprobe.probe(on.toRealPath())).thenReturn(probe);
        return file;
    }

    private static MatchOutcome matched(long id) {
        return new MatchOutcome(Status.MATCHED, new MovieDetails(id, "Film", "Film", 2000, null, 100, List.of(),
            null, null, 1, List.of(), List.of(), null), List.of());
    }

    @Test
    void repairAndGroupingRunAfterTheMissingSweepAndBeforeTheRetriesAndDefaultsAfterThem() throws IOException {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));

        service.start("admin");

        InOrder order = inOrder(ingest, grouping);
        order.verify(ingest).markMissing(List.of());
        order.verify(grouping).repair();
        order.verify(grouping).group();
        order.verify(ingest).pending();
        order.verify(ingest).completeCollections();
        order.verify(grouping).refreshDefaults();
        order.verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, null);
    }

    @Test
    void groupingNeedsNoTmdb() {
        when(tmdb.enabled()).thenReturn(false);
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));

        service.start("admin");

        verify(grouping).repair();
        verify(grouping).group();
        verify(grouping).refreshDefaults();
        verify(ingest, never()).pending();
    }

    @Test
    void aFailureInRepairIsCountedAndTheRestOfTheScanStillRuns() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        doThrow(new IllegalStateException("boom")).when(grouping).repair();

        service.start("admin");

        verify(grouping, never()).group();
        verify(ingest).pending();
        verify(grouping).refreshDefaults();
        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, "1 files could not be processed");
    }

    @Test
    void aFailureInTheDefaultsIsCountedToo() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        doThrow(new IllegalStateException("boom")).when(grouping).refreshDefaults();

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, "1 files could not be processed");
    }

    @Test
    void aPartOfAnEditionWeHaveIsAddedWithoutAnyTmdbLookup() throws IOException {
        FoundFile file = newFile("Towers - pt2.m4v", 110);
        when(ingest.isPartOfKnownEdition("Towers - pt2.m4v")).thenReturn(true);

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Towers", null), null);
        verify(matcher, never()).match(org.mockito.ArgumentMatchers.any(), anyLong());
        verify(matcher, never()).matchTagged(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aPartNumberedFileIsSearchedByItsTitleWithADurationOfZero() throws IOException {
        FoundFile file = newFile("Towers (2002) - pt1.m4v", 90);
        MatchOutcome outcome = matched(121);
        when(matcher.match(new TitleGuess("Towers", 2002), 0)).thenReturn(outcome);

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Towers", 2002), outcome);
    }

    @Test
    void aWholeFilmIsSearchedWithItsDuration() throws IOException {
        FoundFile file = newFile("Heat (1995).m4v", 170);
        MatchOutcome outcome = matched(949);
        when(matcher.match(new TitleGuess("Heat", 1995), 170 * MIN)).thenReturn(outcome);

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Heat", 1995), outcome);
    }

    @Test
    void aTmdbTagFetchesThatFilmDirectlyAndNothingIsSearched() throws IOException {
        FoundFile file = newFile("Heat {tmdb-949}.m4v", 170);
        MatchOutcome outcome = matched(949);
        when(matcher.matchTagged(949L, null)).thenReturn(Optional.of(outcome));

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Heat", null), outcome);
        verify(matcher, never()).match(org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void anImdbTagIsLookedUpByIdToo() throws IOException {
        FoundFile file = newFile("Heat {imdb-tt0113277}.m4v", 170);
        MatchOutcome outcome = matched(949);
        when(matcher.matchTagged(null, "tt0113277")).thenReturn(Optional.of(outcome));

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Heat", null), outcome);
    }

    @Test
    void aTagTmdbDoesNotKnowFallsBackToTheTitleSearch() throws IOException {
        FoundFile file = newFile("Heat {tmdb-1}.m4v", 170);
        MatchOutcome outcome = matched(949);
        when(matcher.matchTagged(1L, null)).thenReturn(Optional.empty());
        when(matcher.match(new TitleGuess("Heat", null), 170 * MIN)).thenReturn(outcome);

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Heat", null), outcome);
    }

    @Test
    void aPendingFilmWhoseFileCarriesAnIdIsRetriedByThatId() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        when(ingest.pending()).thenReturn(List.of(new LibraryIngest.Pending(10L, "Heat", null, 170 * MIN, 949L, null)));
        MatchOutcome outcome = matched(949);
        when(matcher.matchTagged(949L, null)).thenReturn(Optional.of(outcome));

        service.start("admin");

        verify(ingest).applyOutcome(10L, outcome);
        verify(matcher, never()).match(org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void aPendingTagTmdbDoesNotKnowFallsBackToTheTitleSearchAndAnUntaggedOneSearchesDirectly() {
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
        when(ingest.pending()).thenReturn(List.of(new LibraryIngest.Pending(10L, "Heat", 1995, 1L, null, "tt1"),
            new LibraryIngest.Pending(11L, "Alien", null, 2L)));
        MatchOutcome heat = matched(949);
        MatchOutcome alien = matched(348);
        when(matcher.matchTagged(null, "tt1")).thenReturn(Optional.empty());
        when(matcher.match(new TitleGuess("Heat", 1995), 1L)).thenReturn(heat);
        when(matcher.match(new TitleGuess("Alien", null), 2L)).thenReturn(alien);

        service.start("admin");

        verify(ingest).applyOutcome(10L, heat);
        verify(ingest).applyOutcome(11L, alien);
        verify(matcher, never()).matchTagged(null, null);
    }

    @Test
    void aTmdbOutageOnATaggedLookupLeavesTheFileForALaterScan() throws IOException {
        FoundFile file = newFile("Heat {tmdb-949}.m4v", 170);
        when(matcher.matchTagged(949L, null)).thenReturn(Optional.of(UNAVAILABLE));

        service.start("admin");

        verify(ingest).addNew(file, Playability.PLAYABLE, probe, new TitleGuess("Heat", null), null);
        verify(matcher, never()).match(org.mockito.ArgumentMatchers.any(), anyLong());
        verify(ingest, never()).pending();
    }
}
