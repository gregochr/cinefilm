package gregochr.cinefilm.library;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.FilmRepository;
import org.junit.jupiter.api.Test;

class ScanSchedulerTest {

    private final ScanService scans = mock(ScanService.class);
    private final FilmRepository films = mock(FilmRepository.class);
    private final ScanScheduler scheduler = new ScanScheduler(scans, films);

    @Test
    void anEmptyLibraryIsScannedAtStartup() {
        when(films.count()).thenReturn(0L);

        scheduler.onStartup();

        verify(scans).start("startup");
    }

    @Test
    void aLibraryWithOneFilmIsNotScannedAtStartup() {
        when(films.count()).thenReturn(1L);

        scheduler.onStartup();

        verify(scans, never()).start("startup");
    }

    @Test
    void theNightlyRunStartsANightlyScan() {
        scheduler.nightly();

        verify(scans).start("nightly");
    }

    @Test
    void aScanAlreadyRunningIsSkippedQuietlyNotAnError() {
        doThrow(new ScanAlreadyRunningException()).when(scans).start("nightly");

        scheduler.nightly();

        verify(scans).start("nightly");
    }
}
