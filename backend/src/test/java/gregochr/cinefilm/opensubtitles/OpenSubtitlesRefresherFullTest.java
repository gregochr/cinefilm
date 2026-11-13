package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.ImdbRef;
import gregochr.cinefilm.subtitle.FullNeedService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/** The search run's second pass: full English subtitles, only for the films that need them. */
@ExtendWith(MockitoExtension.class)
class OpenSubtitlesRefresherFullTest {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final ImdbRef OLDBOY = new ImdbRef(1L, "tt0364569");
    private static final ImdbRef TAKEN = new ImdbRef(2L, "tt0936501");
    private static final ImdbRef MARTYRS = new ImdbRef(3L, "tt1029234");

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private FilmRepository films;

    @Mock
    private FullNeedService fullNeed;

    @Mock
    private ApplicationEventPublisher events;

    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(OpenSubtitlesRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private OpenSubtitlesRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new OpenSubtitlesRefresher(api, films, fullNeed, CLOCK, Runnable::run, events);
        logged.start();
        refresherLog.addAppender(logged);
    }

    /** Runs one pass with nothing due for the forced search (the full pass is what these tests are about). */
    private void run() {
        when(api.enabled()).thenReturn(true);
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of());
        refresher.startDue();
    }

    @AfterEach
    void detach() {
        refresherLog.detachAppender(logged);
    }

    private static OpenSubtitlesResult result(long id, boolean hearingImpaired) {
        return new OpenSubtitlesResult(id, id + 1000, "Film." + id, 23.976, 10, false, hearingImpaired, null, null);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void onlyTheFilmsThatNeedFullSubtitlesAreSearchedAndTheirCandidatesAreSaved() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L, 3L));
        when(films.findOpenSubtitlesFullDue(CUTOFF)).thenReturn(List.of(OLDBOY, TAKEN, MARTYRS));
        OpenSubtitlesResult plain = result(7, false);
        OpenSubtitlesResult impaired = result(8, true);
        when(api.searchFull("tt0364569")).thenReturn(new OpenSubtitlesSearch(2, plain, List.of(plain, impaired)));
        when(api.searchFull("tt1029234")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitlesFull(1L, "tt0364569", OpenSubtitlesResult.listToJson(List.of(plain, impaired)),
            NOW)).thenReturn(1);
        when(films.saveOpenSubtitlesFull(3L, "tt1029234", "[]", NOW)).thenReturn(1);

        run();

        verify(api).searchFull("tt0364569");
        verify(api).searchFull("tt1029234");
        verify(api, never()).searchFull("tt0936501");
        verify(films).saveOpenSubtitlesFull(1L, "tt0364569", OpenSubtitlesResult.listToJson(List.of(plain, impaired)),
            NOW);
        verify(films).saveOpenSubtitlesFull(3L, "tt1029234", "[]", NOW);
        assertThat(messages()).contains("Full subtitle search finished: 2 saved, 0 failed");
    }

    @Test
    void theFullSearchUsesTheSameThirtyDayCutoffAsTheForcedOne() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L));
        when(films.findOpenSubtitlesFullDue(CUTOFF)).thenReturn(List.of());

        run();

        verify(films).findOpenSubtitlesFullDue(CUTOFF);
        verify(api, never()).searchFull(anyString());
    }

    @Test
    void whenNoFilmNeedsFullSubtitlesNothingIsAskedOrQueried() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of());

        run();

        verify(films, never()).findOpenSubtitlesFullDue(CUTOFF);
        verify(api, never()).searchFull(anyString());
    }

    @Test
    void aSaveThatMatchesNothingBecauseTheImdbIdChangedIsNotCounted() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L));
        when(films.findOpenSubtitlesFullDue(CUTOFF)).thenReturn(List.of(OLDBOY));
        when(api.searchFull("tt0364569")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitlesFull(1L, "tt0364569", "[]", NOW)).thenReturn(0);

        run();

        verify(films).saveOpenSubtitlesFull(1L, "tt0364569", "[]", NOW);
        assertThat(messages()).noneMatch(m -> m.startsWith("Full subtitle search finished"));
    }

    @Test
    void anOutageDuringTheFullSearchStopsItWithoutSavingAnything() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L, 3L));
        when(films.findOpenSubtitlesFullDue(CUTOFF)).thenReturn(List.of(OLDBOY, MARTYRS));
        when(api.searchFull("tt0364569")).thenThrow(new OpenSubtitlesUnavailableException("down"));

        run();

        verify(api, never()).searchFull("tt1029234");
        verify(films, never()).saveOpenSubtitlesFull(anyLong(), anyString(), anyString(), any());
        assertThat(messages()).contains("Full subtitle search stopped: down");
    }

    @Test
    void aFailureOnOneFilmIsCountedAndTheNextIsStillSearched() {
        when(fullNeed.neededFilmIds()).thenReturn(Set.of(1L, 3L));
        when(films.findOpenSubtitlesFullDue(CUTOFF)).thenReturn(List.of(OLDBOY, MARTYRS));
        when(api.searchFull("tt0364569")).thenThrow(new IllegalStateException("boom"));
        when(api.searchFull("tt1029234")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitlesFull(3L, "tt1029234", "[]", NOW)).thenReturn(1);

        run();

        assertThat(messages()).contains("Full subtitle search finished: 1 saved, 1 failed");
    }

    @Test
    void anOutageDuringTheForcedSearchSkipsTheFullPass() {
        when(api.enabled()).thenReturn(true);
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN));
        when(api.searchForced("tt0936501")).thenThrow(new OpenSubtitlesUnavailableException("down"));

        refresher.startDue();

        verify(fullNeed, never()).neededFilmIds();
        verify(api, never()).searchFull(anyString());
        verify(films, never()).saveOpenSubtitlesFull(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void aFailureReadingTheFullNeedIsLoggedAndTheRunStillEnds() {
        when(fullNeed.neededFilmIds()).thenThrow(new IllegalStateException("database down"));

        run();

        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage).containsExactly("Full subtitle search failed");
        verify(events).publishEvent(new OpenSubtitlesSearchFinished());
        assertThat(refresher.isRunning()).isFalse();
    }
}
