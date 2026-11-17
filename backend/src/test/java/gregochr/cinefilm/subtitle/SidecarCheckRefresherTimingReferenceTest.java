package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import gregochr.cinefilm.subtitle.TimingReferenceService.File;
import gregochr.cinefilm.subtitle.TimingReferenceService.Origin;
import gregochr.cinefilm.subtitle.TimingReferenceService.Target;
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
 * The check job's third duty, the timing reference of phase 3c-1: it runs after the checks of the owner's files, not
 * at all while OpenSubtitles' limit holds, only measures when those checks met a stop, and a failure of it never harms
 * the run.
 */
@ExtendWith(MockitoExtension.class)
class SidecarCheckRefresherTimingReferenceTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int TOTAL_LIMIT = 20;
    private static final int CHECK_LIMIT = 6;
    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);

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
    private TimingReferenceService timingReference;

    @Mock
    private ApplicationEventPublisher events;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(SidecarCheckRefresher.class);
    private final Logger fetchLog = (Logger) LoggerFactory.getLogger(TimingReferenceFetch.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private SidecarCheckRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SidecarCheckRefresher(api, budget, references, service, fullFit, timingReference, CLOCK,
            queued::add, new FakeDailyLimit(TOTAL_LIMIT), events);
        logged.start();
        refresherLog.addAppender(logged);
        fetchLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
        fetchLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void startAndRun() {
        when(api.downloadsEnabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        queued.get(0).run();
    }

    private static List<SubtitleCue> cues(int count) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            cues.add(new SubtitleCue(i * 10_000L, i * 10_000L + 1_000, "line " + i));
        }
        return cues;
    }

    private static OpenSubtitlesReference stored(long filmId, String imdbId, long fileId, List<SubtitleCue> cues) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(fileId);
        reference.setFilmId(filmId);
        reference.setImdbId(imdbId);
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFetchedAt(NOW);
        return reference;
    }

    private static DueCheck dueCheck() {
        OpenSubtitlesResult candidate = new OpenSubtitlesResult(4022, 5022, "Release", 23.976, 10, true, false, null,
            null);
        return new DueCheck(2L, 11L, "tt2", "Original", List.of(candidate), Set.of(5022L), "v11", false,
            List.of(new SubtitleCue(10_000, 11_000, "a"), new SubtitleCue(20_000, 21_000, "b")));
    }

    /** An edition whose full file is stored already, or has to be fetched. */
    private static Target target(boolean referenceStored) {
        return target(1L, referenceStored);
    }

    private static Target target(long filmId, boolean referenceStored) {
        OpenSubtitlesResult candidate = new OpenSubtitlesResult(4012, 5012, "Release", 23.976, 10, true, false, null,
            null);
        List<SubtitleCue> forced = new ArrayList<>();
        for (int k = 1; k <= 12; k++) {
            forced.add(new SubtitleCue(k * 250_000L, k * 250_000L + 2_000, "foreign"));
        }
        return new Target(filmId, filmId * 10, "Original", "tt" + filmId, 3_000_000, null, false,
            List.of(new File(7L, Origin.OWNER, 12, SrtParser.format(forced))), List.of(candidate), 0,
            referenceStored ? Map.of(5012L, stored(filmId, "tt" + filmId, 5012, cues(300))) : Map.of());
    }

    @Test
    void theStoredTimingReferenceIsMeasuredAfterTheChecksOfTheOwnersFiles() {
        DueCheck check = dueCheck();
        OpenSubtitlesReference reference = stored(2L, "tt2", 5022, List.of(new SubtitleCue(10_000, 11_000, "a"),
            new SubtitleCue(20_000, 21_000, "b")));
        when(service.due()).thenReturn(List.of(check));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(reference));
        when(service.save(check, reference, AGREES, NOW)).thenReturn(true);
        when(timingReference.targets()).thenReturn(List.of(target(true)));

        startAndRun();

        var order = inOrder(service, timingReference);
        order.verify(service).save(check, reference, AGREES, NOW);
        order.verify(timingReference).targets();
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).startsWith("Edition 11: used OpenSubtitles file 5022");
        assertThat(messages().get(1)).startsWith("Forced timing reference of edition 10 (film 1): OWNER file 7");
        assertThat(messages().get(2)).startsWith("Subtitle file checks finished: 1 saved");
    }

    @Test
    void aTimingReferenceDownloadIsTakenAtTheComparisonCeilingNeverTheWholeLimit() {
        when(service.due()).thenReturn(List.of());
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        List<SubtitleCue> cues = cues(300);
        when(api.download(5012)).thenReturn(new OpenSubtitlesDownload(new SrtParser.Parsed(cues,
            new SrtParser.Report("UTF-8", 0, 0, 0, false, false, false, cues.size())), 50, null));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));
        when(timingReference.targets()).thenReturn(List.of(target(false)));

        startAndRun();

        verify(budget).take(DAY, CHECK_LIMIT);
        verify(budget, never()).take(DAY, TOTAL_LIMIT);
        verify(api).download(5012);
        assertThat(messages()).anyMatch(m -> m.startsWith("Timing reference for edition 10 (film 1): fetched "
            + "file 5012"));
    }

    @Test
    void aSpentShareInTheChecksStopsTheFetchingButNotTheMeasuringOfStoredReferences() {
        DueCheck check = dueCheck();
        when(service.due()).thenReturn(List.of(check));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(false);
        when(timingReference.targets()).thenReturn(List.of(target(false), target(2L, true)));

        startAndRun();

        verify(budget).take(DAY, CHECK_LIMIT);
        verify(api, never()).download(5012);
        assertThat(messages()).hasSize(3);
        assertThat(messages().get(0)).isEqualTo("Subtitle file checks stopped: today's download budget is spent");
        assertThat(messages().get(1)).startsWith("Forced timing reference of edition 20 (film 2): OWNER file 7");
    }

    @Test
    void anOutageInTheChecksStopsTheFetchingToo() {
        DueCheck check = dueCheck();
        when(service.due()).thenReturn(List.of(check));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5022)).thenThrow(new OpenSubtitlesUnavailableException("down"));
        when(timingReference.targets()).thenReturn(List.of(target(false)));

        startAndRun();

        verify(api, never()).download(5012);
        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage).containsExactly("Subtitle file checks stopped: down");
    }

    @Test
    void whileOpenSubtitlesLimitHoldsNothingIsFetchedOrMeasured() {
        when(budget.blockedUntil(NOW)).thenReturn(Instant.parse("2024-04-01T02:00:00Z"));

        startAndRun();

        verify(timingReference, never()).targets();
        assertThat(messages()).containsExactly("Subtitle file checks wait: OpenSubtitles' download limit is reached "
            + "until 2024-04-01T02:00:00Z");
    }

    @Test
    void aFailureOfTheTimingReferenceIsLoggedWithoutDetailsAndTheRunStillFinishes() {
        when(service.due()).thenReturn(List.of());
        when(timingReference.targets()).thenThrow(new IllegalStateException("database down: secret"));

        startAndRun();

        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Timing reference measurement failed: IllegalStateException");
        assertThat(messages()).last().asString().startsWith("Subtitle file checks finished: 0 saved");
        assertThat(refresher.isRunning()).isFalse();
    }
}
