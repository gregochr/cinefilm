package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The one library lock shared by the scan and the Plex import. */
class LibraryLockTest {

    @TempDir
    Path library;

    private final LibraryLock lock = new LibraryLock();
    private final List<Runnable> queued = new ArrayList<>();
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private ScanService scans() {
        return new ScanService(new LibraryRoot(library.toString()), mock(LibraryScanner.class),
            mock(FfprobeService.class), mock(TmdbApi.class), mock(TmdbMatcher.class), ingest, queued::add, CLOCK, lock,
            event -> { }, mock(LibraryGrouping.class));
    }

    @Test
    void onlyOneHolderAtATimeAndReleaseFreesIt() {
        assertThat(lock.holder()).isNull();
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        assertThat(lock.tryAcquire(LibraryLock.IMPORT)).isFalse();
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isFalse();
        assertThat(lock.holder()).isEqualTo("scan");
        assertThat(lock.heldBy(LibraryLock.SCAN)).isTrue();
        assertThat(lock.heldBy(LibraryLock.IMPORT)).isFalse();

        lock.release();

        assertThat(lock.holder()).isNull();
        assertThat(lock.heldBy(LibraryLock.SCAN)).isFalse();
        assertThat(lock.tryAcquire(LibraryLock.IMPORT)).isTrue();
        assertThat(lock.heldBy(LibraryLock.IMPORT)).isTrue();
    }

    @Test
    void aScanCannotStartWhileAnImportRunsAndIsRefusedWithAReason() {
        lock.tryAcquire(LibraryLock.IMPORT);
        ScanService scans = scans();

        assertThatThrownBy(() -> scans.start("admin")).isInstanceOf(ScanAlreadyRunningException.class)
            .hasMessage("A Plex import is running");

        assertThat(queued).isEmpty();
        assertThat(scans.isRunning()).isFalse();
        assertThat(scans.progress()).isNull();
        assertThat(lock.heldBy(LibraryLock.IMPORT)).isTrue();
        verifyNoInteractions(ingest);
    }

    @Test
    void aRunningScanBlocksAnImportAndFreesTheLockWhenItEnds() {
        ScanService scans = scans();

        scans.start("admin");

        assertThat(scans.isRunning()).isTrue();
        assertThat(lock.tryAcquire(LibraryLock.IMPORT)).isFalse();
        queued.get(0).run();
        assertThat(scans.isRunning()).isFalse();
        assertThat(lock.holder()).isNull();
        assertThat(lock.tryAcquire(LibraryLock.IMPORT)).isTrue();
    }

    @Test
    void aScheduledScanThatFindsTheImportRunningIsSkippedAndLogged() {
        lock.tryAcquire(LibraryLock.IMPORT);
        ScanScheduler scheduler = new ScanScheduler(scans(), mock(FilmRepository.class));
        Logger logger = (Logger) LoggerFactory.getLogger(ScanScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.nightly();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(queued).isEmpty();
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
            .isEqualTo("Skipping nightly scan: a scan or import is already running"));
        verify(ingest, org.mockito.Mockito.never()).known();
    }
}
