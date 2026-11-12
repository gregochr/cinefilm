package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** The languages backfill: what it asks TMDB, what it saves and when, and that only one run is ever going. */
@ExtendWith(MockitoExtension.class)
class LanguagesRefresherTest {

    private static final TmdbRef HEAT = new TmdbRef(1L, 949L);
    private static final TmdbRef AMELIE = new TmdbRef(2L, 194L);
    private static final TmdbRef TAKEN = new TmdbRef(3L, 8681L);

    @Mock
    private TmdbApi tmdb;

    @Mock
    private FilmRepository films;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(LanguagesRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private LanguagesRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new LanguagesRefresher(tmdb, films, CLOCK, queued::add);
        logged.start();
        refresherLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
    }

    private static MovieDetails movie(long id, String original, List<String> spoken) {
        return new MovieDetails(id, "Film " + id, "Film " + id, 2000, null, 100, List.of(), null, null, 0,
            List.of(), List.of(), null, null, original, spoken);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Starts a run and executes what was queued, as the executor's thread would. */
    private void startAndRun() {
        when(tmdb.enabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    @Test
    void eachFilmWithoutLanguagesIsAskedAboutAndSavedWithTheClocksStamp() {
        when(films.findWithoutLanguages()).thenReturn(List.of(HEAT, AMELIE));
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949, "en", List.of("en", "es"))));
        when(tmdb.movie(194)).thenReturn(Optional.of(movie(194, "fr", List.of("fr", "en"))));
        when(films.saveLanguages(1L, 949L, "en", "en,es", NOW)).thenReturn(1);
        when(films.saveLanguages(2L, 194L, "fr", "fr,en", NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveLanguages(1L, 949L, "en", "en,es", NOW);
        verify(films).saveLanguages(2L, 194L, "fr", "fr,en", NOW);
        assertThat(messages()).contains("Languages refresh finished: 2 saved, 0 changed meanwhile, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aFilmTmdbNoLongerHasIsStampedWithNoLanguagesSoItIsNotAskedAgain() {
        when(films.findWithoutLanguages()).thenReturn(List.of(HEAT));
        when(tmdb.movie(949)).thenReturn(Optional.empty());
        when(films.saveLanguages(1L, 949L, null, null, NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveLanguages(1L, 949L, null, null, NOW);
    }

    @Test
    void aFilmTmdbListsNoSpokenLanguagesForIsSavedWithTheEmptyString() {
        when(films.findWithoutLanguages()).thenReturn(List.of(HEAT));
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949, "en", List.of())));
        when(films.saveLanguages(1L, 949L, "en", "", NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveLanguages(1L, 949L, "en", "", NOW);
    }

    @Test
    void aTmdbOutageStopsTheRunAndNothingAfterItIsAskedOrStamped() {
        when(films.findWithoutLanguages()).thenReturn(List.of(HEAT, AMELIE, TAKEN));
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949, "en", List.of("en"))));
        when(tmdb.movie(194)).thenThrow(new TmdbUnavailableException("TMDB is down", null));
        when(films.saveLanguages(1L, 949L, "en", "en", NOW)).thenReturn(1);

        startAndRun();

        verify(films).findWithoutLanguages();
        verify(films).saveLanguages(1L, 949L, "en", "en", NOW);
        verifyNoMoreInteractions(films);
        verify(tmdb, never()).movie(8681);
        assertThat(messages()).contains("Languages refresh stopped: TMDB is down",
            "Languages refresh finished: 1 saved, 0 changed meanwhile, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aFilmRematchedWhileTmdbWasAskedIsLeftAloneAndTheRunCarriesOn() {
        when(films.findWithoutLanguages()).thenReturn(List.of(HEAT, AMELIE));
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949, "en", List.of("en"))));
        when(tmdb.movie(194)).thenReturn(Optional.of(movie(194, "fr", List.of("fr"))));
        // The statement matches on the TMDB id the languages were fetched for: a rematch makes it change no row.
        when(films.saveLanguages(1L, 949L, "en", "en", NOW)).thenReturn(0);
        when(films.saveLanguages(2L, 194L, "fr", "fr", NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveLanguages(1L, 949L, "en", "en", NOW);
        verify(films).saveLanguages(2L, 194L, "fr", "fr", NOW);
        assertThat(messages()).contains("Languages refresh finished: 1 saved, 1 changed meanwhile, 0 failed");
    }

    @Test
    void aFilmThatFailsIsSkippedAndTheRestAreStillDone() {
        when(films.findWithoutLanguages()).thenReturn(List.of(HEAT, AMELIE));
        when(tmdb.movie(949)).thenThrow(new IllegalStateException("bad json"));
        when(tmdb.movie(194)).thenReturn(Optional.of(movie(194, "fr", List.of("fr"))));
        when(films.saveLanguages(2L, 194L, "fr", "fr", NOW)).thenReturn(1);

        startAndRun();

        InOrder order = inOrder(films);
        order.verify(films).findWithoutLanguages();
        order.verify(films).saveLanguages(2L, 194L, "fr", "fr", NOW);
        order.verifyNoMoreInteractions();
        assertThat(messages()).contains("Could not refresh the languages of film 1: IllegalStateException",
            "Languages refresh finished: 1 saved, 0 changed meanwhile, 1 failed");
    }

    @Test
    void aFailureListingTheFilmsIsLoggedAndTheServiceCanRunAgain() {
        when(films.findWithoutLanguages()).thenThrow(new IllegalStateException("database down"));

        startAndRun();

        assertThat(messages()).contains("Languages refresh failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aSecondStartWhileARunIsGoingIsRefusedAndOnlyOneRunIsQueued() {
        when(tmdb.enabled()).thenReturn(true);

        assertThat(refresher.startDue()).isTrue();
        assertThat(refresher.isRunning()).isTrue();
        assertThat(refresher.startDue()).isFalse();
        refresher.onScanFinished(new ScanFinished("admin"));
        refresher.onPlexImportFinished(new PlexImportFinished());

        assertThat(queued).hasSize(1);
        when(films.findWithoutLanguages()).thenReturn(List.of());
        queued.get(0).run();
        assertThat(refresher.isRunning()).isFalse();
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(2);
    }

    @Test
    void nothingHappensWhenTmdbIsOff() {
        when(tmdb.enabled()).thenReturn(false);

        assertThat(refresher.startDue()).isFalse();

        assertThat(queued).isEmpty();
        assertThat(refresher.isRunning()).isFalse();
        verifyNoInteractions(films);
    }

    @Test
    void aScanAndAPlexImportBothStartARun() {
        when(tmdb.enabled()).thenReturn(true);

        refresher.onScanFinished(new ScanFinished("nightly"));
        assertThat(queued).hasSize(1);
        when(films.findWithoutLanguages()).thenReturn(List.of());
        queued.get(0).run();
        refresher.onPlexImportFinished(new PlexImportFinished());

        assertThat(queued).hasSize(2);
    }

    @Test
    void startUpStartsARunWhenTmdbIsOn() {
        when(tmdb.enabled()).thenReturn(true);

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    @Test
    void startUpSaysTmdbIsOffAndStartsNothing() {
        when(tmdb.enabled()).thenReturn(false);

        refresher.onStartup();

        assertThat(queued).isEmpty();
        assertThat(messages()).containsExactly("Film languages are off: no TMDB token is configured");
    }

    @Test
    void startUpStaysQuietWhenARunIsAlreadyGoing() {
        when(tmdb.enabled()).thenReturn(true);
        refresher.startDue();

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    @Test
    void anExecutorThatRefusesTheRunDoesNotLeaveTheServiceStuckRunning() {
        LanguagesRefresher refusing = new LanguagesRefresher(tmdb, films, CLOCK, command -> {
            throw new RejectedExecutionException("full");
        });
        when(tmdb.enabled()).thenReturn(true);

        assertThatThrownBy(refusing::startDue).isInstanceOf(RejectedExecutionException.class);

        assertThat(refusing.isRunning()).isFalse();
    }

    @Test
    void theApplicationReadyEventIsWhatStartUpListensTo() throws NoSuchMethodException {
        var listener = LanguagesRefresher.class.getMethod("onStartup")
            .getAnnotation(org.springframework.context.event.EventListener.class);

        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
