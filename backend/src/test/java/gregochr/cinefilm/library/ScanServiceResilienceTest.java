package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scan keeps going when one part of it fails, and never marks what it could not see as gone. */
class ScanServiceResilienceTest {

    private static final Instant T1 = Instant.parse("2024-01-01T10:00:00Z");
    private static final MatchOutcome UNAVAILABLE = new MatchOutcome(Status.UNAVAILABLE, null, List.of());

    @TempDir
    Path library;

    private final LibraryScanner scanner = mock(LibraryScanner.class);
    private final FfprobeService ffprobe = mock(FfprobeService.class);
    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final TmdbMatcher matcher = mock(TmdbMatcher.class);
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private ScanService service;

    @BeforeEach
    void setUp() {
        service = new ScanService(new LibraryRoot(library.toString()), scanner, ffprobe, tmdb, matcher, ingest,
            Runnable::run, CLOCK, new LibraryLock(), event -> { });
        when(tmdb.enabled()).thenReturn(true);
        when(ingest.startRun("admin", CLOCK.instant())).thenReturn(5L);
        when(ingest.known()).thenReturn(Map.of());
        when(scanner.scan()).thenReturn(new Found(List.of(), 0));
    }

    private static Known known(long id, String path, long size, Instant modified) {
        return new Known(id, path, size, modified, Presence.PRESENT, Playability.PLAYABLE, "yuv420p");
    }

    @Test
    void oneFilmWhoseMatchCannotBeAppliedDoesNotStopTheOthersBeingRetried() {
        MatchOutcome heat = new MatchOutcome(Status.NEEDS_MATCH, null, List.of());
        when(ingest.pending()).thenReturn(List.of(new Pending(10L, "Heat", 1995, 1L),
            new Pending(11L, "Alien", null, 2L), new Pending(12L, "Dune", null, 3L)));
        when(matcher.match(new TitleGuess("Heat", 1995), 1L)).thenReturn(heat);
        when(matcher.match(new TitleGuess("Alien", null), 2L)).thenReturn(heat);
        when(matcher.match(new TitleGuess("Dune", null), 3L)).thenReturn(heat);
        doThrow(new IllegalStateException("constraint")).when(ingest).applyOutcome(11L, heat);

        service.start("admin");

        verify(ingest).applyOutcome(10L, heat);
        verify(ingest).applyOutcome(11L, heat);
        verify(ingest).applyOutcome(12L, heat);
        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, "1 files could not be processed");
    }

    @Test
    void filesInsideAFolderTheWalkCouldNotReadAreNotMarkedMissing() {
        Instant modified = T1;
        when(scanner.scan()).thenReturn(new Found(List.of(new FoundFile("Other.m4v", 10, modified, modified, false)),
            0, List.of("Locked")));
        when(ingest.known()).thenReturn(Map.of(
            "Other.m4v", known(1, "Other.m4v", 10, modified),
            "Locked/Heat.m4v", known(2, "Locked/Heat.m4v", 20, modified),
            "Locked/Deep/Alien.m4v", known(3, "Locked/Deep/Alien.m4v", 30, modified),
            "Lockedness.m4v", known(4, "Lockedness.m4v", 40, modified)));

        service.start("admin");

        verify(ingest).markMissing(List.of(4L));
        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 1,
            "1 folders could not be read; their films were left as they were");
    }

    @Test
    void aFileInAnUnreadableFolderIsNotTakenForTheSourceOfARename() throws IOException {
        FoundFile renamed = new FoundFile("New.m4v", 20, T1, T1, false);
        Files.writeString(library.resolve("New.m4v"), "x");
        when(scanner.scan()).thenReturn(new Found(List.of(renamed), 0, List.of("Locked")));
        when(ingest.known()).thenReturn(Map.of("Locked/Heat.m4v", known(2, "Locked/Heat.m4v", 20, T1)));
        when(ffprobe.probe(library.resolve("New.m4v").toRealPath())).thenThrow(new ProbeException("x", null));
        when(tmdb.enabled()).thenReturn(false);

        service.start("admin");

        verify(ingest, never()).repoint(2L, renamed);
        verify(ingest, never()).markMissing(List.of(2L));
    }

    @Test
    void everyKindOfNoteIsReportedTogether() throws IOException {
        FoundFile bad = new FoundFile("Bad.m4v", 10, T1, T1, false);
        when(scanner.scan()).thenReturn(new Found(List.of(bad), 2, List.of("Locked", "Other")));
        when(ingest.known()).thenReturn(Map.of("Bad.m4v", known(1, "Bad.m4v", 99, T1)));
        doThrow(new IllegalStateException("db")).when(ingest).refresh(1L, bad, Playability.UNREADABLE, null);

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 1, 0, 0, "1 files could not be processed; "
            + "2 files outside the library folder were refused; 2 folders could not be read; "
            + "their films were left as they were");
    }

    @Test
    void collectionsThatCouldNotBeCompletedAreRetriedAtTheEndOfAScan() {
        service.start("admin");

        verify(ingest).completeCollections();
    }

    @Test
    void collectionsAreNotRetriedWithoutATokenOrAfterAnOutageDuringTheScan() {
        when(tmdb.enabled()).thenReturn(false);
        service.start("admin");
        verify(ingest, never()).completeCollections();

        when(tmdb.enabled()).thenReturn(true);
        when(ingest.pending()).thenReturn(List.of(new Pending(10L, "Heat", 1995, 1L)));
        when(matcher.match(new TitleGuess("Heat", 1995), 1L)).thenReturn(UNAVAILABLE);
        service.start("admin");
        verify(ingest, never()).completeCollections();
    }

    @Test
    void aFailureCompletingCollectionsIsLoggedNotFatal() {
        doThrow(new IllegalStateException("db")).when(ingest).completeCollections();

        service.start("admin");

        verify(ingest).finishRun(5L, CLOCK.instant(), 0, 0, 0, null);
        assertThat(service.isRunning()).isFalse();
    }
}
