package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The download run for full English subtitles: they come before replacements and missing forced ones, use the whole
 * limit, need 300 lines or more, and a ceiling stop on them ends the run (a replacement's still skips to missing).
 */
@ExtendWith(MockitoExtension.class)
class ForcedDownloadRefresherFullTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 20;
    private static final int REPLACEMENT_LIMIT = 14;
    private static final List<SubtitleCue> SHORT = lines(2);

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private ForcedDownloadService service;

    @Mock
    private ApplicationEventPublisher events;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(ForcedDownloadRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final FakeDailyLimit limits = new FakeDailyLimit(LIMIT);
    private ForcedDownloadRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new ForcedDownloadRefresher(api, budget, references, service, CLOCK, queued::add, limits, events);
        logged.start();
        refresherLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
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

    private static DueDownload due(Kind kind, long filmId, long editionId, long... fileIds) {
        List<OpenSubtitlesResult> candidates = new ArrayList<>();
        for (long fileId : fileIds) {
            candidates.add(new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true,
                false, null, null));
        }
        return new DueDownload(kind, filmId, editionId, "tt" + filmId, "Original", null, candidates, 3,
            kind == Kind.REPLACEMENT ? "v" + editionId : null);
    }

    private static OpenSubtitlesDownload downloaded(List<SubtitleCue> cues) {
        return new OpenSubtitlesDownload(new SrtParser.Parsed(cues,
            new SrtParser.Report("UTF-8", 0, 0, 0, false, false, false, cues.size())), 50, null);
    }

    private void storingReferences() {
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(reference.getOsFileId());
            return reference;
        });
    }

    private void startAndRun() {
        when(api.downloadsEnabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    @Test
    void aFullFileOfThreeHundredLinesIsDownloadedWithinTheWholeLimitAndMadeTheDownload() {
        DueDownload due = due(Kind.FULL, 1, 10, 5011, 5012);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(lines(300)));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(api).download(5011);
        verify(api, never()).download(5012);
        verify(budget, never()).take(DAY, REPLACEMENT_LIMIT);
        verify(events).publishEvent(new SubtitleDownloadsFinished());
        assertThat(messages()).contains("Edition 10: downloaded OpenSubtitles file 5011 for a full subtitle, ADDED",
            "Subtitle downloads finished: 1 added, 0 with nothing added, 0 failed");
    }

    @Test
    void aFileOfTwoHundredAndNinetyNineLinesIsNotFullSubtitlesAndTheNextCandidateIsTried() {
        DueDownload due = due(Kind.FULL, 1, 10, 5011, 5012);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(lines(299)));
        when(api.download(5012)).thenReturn(downloaded(lines(300)));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        InOrder order = inOrder(api);
        order.verify(api).download(5011);
        order.verify(api).download(5012);
        assertThat(messages()).contains("Edition 10: OpenSubtitles file 5011 skipped, it is not full subtitles",
            "Edition 10: downloaded OpenSubtitles file 5012 for a full subtitle, ADDED");
    }

    @Test
    void whenEveryCandidateIsTooShortNothingIsAdded() {
        DueDownload due = due(Kind.FULL, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(SHORT));
        storingReferences();

        startAndRun();

        verify(service, never()).add(any(DueDownload.class), any(OpenSubtitlesReference.class), any());
        verifyNoInteractions(events);
        assertThat(messages()).contains("Edition 10: OpenSubtitles file 5011 skipped, it is not full subtitles",
            "Subtitle downloads finished: 0 added, 1 with nothing added, 0 failed");
    }

    @Test
    void aForcedFileOfThreeHundredLinesIsStillRejectedAsFullSubtitles() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(lines(300)));
        storingReferences();

        startAndRun();

        verify(service, never()).add(any(DueDownload.class), any(OpenSubtitlesReference.class), any());
        assertThat(messages()).contains("Edition 10: OpenSubtitles file 5011 skipped, it is full subtitles");
    }

    @Test
    void aCeilingStopOnAFullDownloadEndsTheRunSoNoReplacementOrMissingEditionIsTried() {
        when(service.due()).thenReturn(List.of(due(Kind.FULL, 1, 10, 5011), due(Kind.REPLACEMENT, 2, 20, 5022),
            due(Kind.MISSING, 3, 30, 5033)));
        when(budget.take(DAY, LIMIT)).thenReturn(false);

        startAndRun();

        verify(api, never()).download(anyInt());
        verify(budget, never()).take(DAY, REPLACEMENT_LIMIT);
        assertThat(messages()).contains("Subtitle downloads stopped: today's download budget is spent");
        assertThat(messages()).doesNotContain("Subtitle downloads: replacements reached their share of today's "
            + "budget, going on with missing subtitles");
    }

    @Test
    void aReplacementCeilingStopStillSkipsToMissingAfterAFullDownloadWasMade() {
        DueDownload full = due(Kind.FULL, 1, 10, 5011);
        DueDownload missing = due(Kind.MISSING, 3, 30, 5033);
        when(service.due()).thenReturn(List.of(full, due(Kind.REPLACEMENT, 2, 20, 5022), missing));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(budget.take(DAY, REPLACEMENT_LIMIT)).thenReturn(false);
        when(api.download(5011)).thenReturn(downloaded(lines(300)));
        when(api.download(5033)).thenReturn(downloaded(SHORT));
        storingReferences();
        when(service.add(eq(full), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);
        when(service.add(eq(missing), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(api, never()).download(5022);
        assertThat(messages()).contains("Subtitle downloads: replacements reached their share of today's budget, "
            + "going on with missing subtitles",
            "Subtitle downloads finished: 2 added, 0 with nothing added, 0 failed");
    }

    @Test
    void openSubtitlesQuotaDuringAFullDownloadStopsTheRun() {
        when(service.due()).thenReturn(List.of(due(Kind.FULL, 1, 10, 5011), due(Kind.MISSING, 2, 20, 5022)));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesQuotaException(null));

        startAndRun();

        verify(api, never()).download(5022);
        assertThat(messages()).contains("Subtitle downloads stopped: OpenSubtitles' download limit is reached");
    }

    @Test
    void aBusyLibraryLeavesAFullDownloadForTheHourlyNudge() {
        DueDownload due = due(Kind.FULL, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(lines(300)));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.BUSY);

        startAndRun();
        queued.clear();
        refresher.startIfDeferred();

        assertThat(queued).hasSize(1);
    }

    @Test
    void aFreePromotionIsStillMadeAfterTheReplacementCeilingWasReached() {
        OpenSubtitlesReference kept = new OpenSubtitlesReference();
        kept.setId(5033L);
        kept.setFilmId(3L);
        kept.setImdbId("tt3");
        kept.setOsFileId(5033);
        kept.setCuesText(SrtParser.format(SHORT));
        kept.setCueCount(SHORT.size());
        kept.setFetchedAt(NOW);
        DueDownload promoted = new DueDownload(Kind.REPLACEMENT, 3L, 30L, "tt3", "Original", kept, List.of(), 3,
            "v30");
        when(service.due()).thenReturn(List.of(due(Kind.REPLACEMENT, 2, 20, 5022), promoted));
        when(budget.take(DAY, REPLACEMENT_LIMIT)).thenReturn(false);
        when(service.add(promoted, kept, NOW)).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(api, never()).download(anyInt());
        verify(service).add(promoted, kept, NOW);
        assertThat(messages()).contains("Edition 30: promoted OpenSubtitles file 5033 already downloaded, ADDED");
    }
}
