package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.FullFitService.Job;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The check job's second duty, the measurement of full downloads (phase 5d-1): it runs before the checks, starts when a
 * download is added, is skipped while OpenSubtitles' limit holds, and a failure of it never harms the run.
 */
@ExtendWith(MockitoExtension.class)
class SidecarCheckRefresherFullFitTest {

    private static final int LIMIT = 20;

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private SidecarCheckService service;

    @Mock
    private FullFitService fullFit;

    @Mock
    private ApplicationEventPublisher events;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(SidecarCheckRefresher.class);
    private final Logger probeLog = (Logger) LoggerFactory.getLogger(FullFitProbe.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private SidecarCheckRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SidecarCheckRefresher(api, budget, references, service, fullFit, CLOCK, queued::add,
            new FakeDailyLimit(LIMIT), events);
        logged.start();
        refresherLog.addAppender(logged);
        probeLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
        probeLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<SubtitleCue> lines(int count) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            cues.add(new SubtitleCue(i * 10_000L, i * 10_000L + 1_000, "line " + i));
        }
        return cues;
    }

    private static Job job() {
        return job(true);
    }

    /** A full download with one other candidate; its reference is stored already, or has to be fetched. */
    private static Job job(boolean referenceStored) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(5012L);
        reference.setFilmId(1L);
        reference.setImdbId("tt1");
        reference.setOsFileId(5012);
        reference.setCuesText(SrtParser.format(lines(300)));
        reference.setCueCount(300);
        reference.setFetchedAt(NOW);
        OpenSubtitlesResult other = new OpenSubtitlesResult(4012, 5012, "Release", 23.976, 10, true, false, null,
            null);
        return new Job(1L, 10L, "tt1", 7L, 5011L, lines(300), 3_600_000, null, List.of(other),
            referenceStored ? Map.of(5012L, reference) : Map.of());
    }

    private void startAndRun() {
        when(api.downloadsEnabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);

    private static DueCheck dueCheck() {
        List<SubtitleCue> cues = List.of(new SubtitleCue(10_000, 11_000, "a"), new SubtitleCue(20_000, 21_000, "b"));
        OpenSubtitlesResult candidate = new OpenSubtitlesResult(4022, 5022, "Release", 23.976, 10, true, false, null,
            null);
        return new DueCheck(2L, 11L, "tt2", "Original", List.of(candidate), Set.of(5022L), "v11", false, cues);
    }

    private static OpenSubtitlesReference storedReference() {
        List<SubtitleCue> cues = List.of(new SubtitleCue(10_000, 11_000, "a"), new SubtitleCue(20_000, 21_000, "b"));
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(8L);
        reference.setFilmId(2L);
        reference.setImdbId("tt2");
        reference.setOsFileId(5022);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFetchedAt(NOW);
        return reference;
    }

    @Test
    void theMeasurementRunsBeforeTheChecksInTheSameJobAndWritesItsLine() {
        DueCheck check = dueCheck();
        OpenSubtitlesReference reference = storedReference();
        when(service.due()).thenReturn(List.of(check));
        when(fullFit.due(Set.of())).thenReturn(List.of(job()));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(reference));
        when(service.save(check, reference, AGREES, NOW)).thenReturn(true);

        startAndRun();

        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).startsWith("Full subtitle fit of edition 10 (film 1): download file 5011 "
            + "(300 cues, last cue 0:50:01) against reference file 5012 (300 cues, last cue 0:50:01)");
        assertThat(messages().get(1)).startsWith("Edition 11: used OpenSubtitles file 5022");
        assertThat(messages().get(2)).startsWith("Subtitle file checks finished: 1 saved");
    }

    @Test
    void aSpentShareInTheMeasurementDoesNotSkipTheChecksWhichCompareStoredReferencesForFree() {
        DueCheck check = dueCheck();
        OpenSubtitlesReference reference = storedReference();
        when(service.due()).thenReturn(List.of(check));
        when(fullFit.due(Set.of())).thenReturn(List.of(job(false)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(reference));
        when(budget.take(LocalDate.of(2024, 3, 31), 6)).thenReturn(false);
        when(service.save(check, reference, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(api, never()).download(5022);
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).isEqualTo("Full subtitle measurement stopped: today's download budget is spent");
        assertThat(messages().get(1)).startsWith("Edition 11: used OpenSubtitles file 5022");
        assertThat(messages().get(2)).startsWith("Subtitle file checks finished: 1 saved");
    }

    @Test
    void anOutageInTheMeasurementIsLoggedAsAWarningAndTheChecksStillRun() {
        DueCheck check = dueCheck();
        OpenSubtitlesReference reference = storedReference();
        when(service.due()).thenReturn(List.of(check));
        when(fullFit.due(Set.of())).thenReturn(List.of(job(false)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(reference));
        when(budget.take(LocalDate.of(2024, 3, 31), 6)).thenReturn(true);
        when(api.download(5012)).thenThrow(new OpenSubtitlesUnavailableException("OpenSubtitles is unavailable"));
        when(service.save(check, reference, AGREES, NOW)).thenReturn(true);

        startAndRun();

        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Full subtitle measurement stopped: OpenSubtitles is unavailable");
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(1)).startsWith("Edition 11: used OpenSubtitles file 5022");
        assertThat(messages().get(2)).startsWith("Subtitle file checks finished: 1 saved");
    }

    @Test
    void aDownloadBeingAddedStartsTheJobSoItsFitIsMeasured() {
        when(api.downloadsEnabled()).thenReturn(true);

        refresher.onDownloadsFinished(new SubtitleDownloadsFinished());

        assertThat(queued).hasSize(1);
    }

    @Test
    void whileOpenSubtitlesLimitHoldsNothingIsMeasuredAndNothingIsFetched() {
        when(budget.blockedUntil(NOW)).thenReturn(Instant.parse("2024-04-01T02:00:00Z"));

        startAndRun();

        verify(fullFit, never()).due(any());
        assertThat(messages()).containsExactly("Subtitle file checks wait: OpenSubtitles' download limit is reached "
            + "until 2024-04-01T02:00:00Z");
    }

    @Test
    void aFailureOfTheMeasurementIsLoggedWithoutDetailsAndTheRunStillFinishes() {
        when(service.due()).thenReturn(List.of());
        when(fullFit.due(Set.of())).thenThrow(new IllegalStateException("database down: secret"));

        startAndRun();

        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Full subtitle measurement failed: IllegalStateException");
        assertThat(messages()).last().asString().startsWith("Subtitle file checks finished: 0 saved");
        assertThat(refresher.isRunning()).isFalse();
    }
}
