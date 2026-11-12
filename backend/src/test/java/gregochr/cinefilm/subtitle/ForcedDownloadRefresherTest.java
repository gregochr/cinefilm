package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearchFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/**
 * The download run: which editions it works on and in what order, promotion before any fetch, the daily budget (missing
 * first, replacements up to their share, no refund once OpenSubtitles has charged), every way a run stops, and the
 * event that starts the timing check. Runs on an executor the test controls.
 */
@ExtendWith(MockitoExtension.class)
class ForcedDownloadRefresherTest {

    /** The UTC day of {@code NOW} (in London it is already 1 April). */
    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 90;
    private static final int REPLACEMENT_LIMIT = 63;
    private static final String NOTHING_DONE = "Subtitle downloads finished: 0 added, 0 with nothing added, 0 failed";
    private static final List<SubtitleCue> CUES = List.of(new SubtitleCue(10_000, 11_000, "a"),
        new SubtitleCue(20_000, 21_000, "b"));

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
    private ForcedDownloadRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new ForcedDownloadRefresher(api, budget, references, service, CLOCK, queued::add, LIMIT, events);
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

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null);
    }

    private static DueDownload due(Kind kind, long filmId, long editionId, long... fileIds) {
        List<OpenSubtitlesResult> candidates = new ArrayList<>();
        for (long fileId : fileIds) {
            candidates.add(candidate(fileId));
        }
        return new DueDownload(kind, filmId, editionId, "tt" + filmId, "Original", null, candidates, 3,
            kind == Kind.REPLACEMENT ? "v" + editionId : null);
    }

    private static OpenSubtitlesReference stored(long filmId, long fileId, List<SubtitleCue> cues) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(fileId);
        reference.setFilmId(filmId);
        reference.setImdbId("tt" + filmId);
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFailedDownloads(cues.isEmpty() ? OpenSubtitlesReference.MAX_FAILED_DOWNLOADS : 0);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private static List<SubtitleCue> lines(int count) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            cues.add(new SubtitleCue(i * 10_000L, i * 10_000L + 1_000, "line " + i));
        }
        return cues;
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

    // --- starting ---

    @Test
    void downloadsAreOffWithoutACredentialAndNothingStartsAndStartUpSaysSoOnce() {
        when(api.downloadsEnabled()).thenReturn(false);

        assertThat(refresher.startDue()).isFalse();
        refresher.onScanFinished(new ScanFinished("admin"));
        refresher.onSearchFinished(new OpenSubtitlesSearchFinished());
        refresher.onChecksFinished(new SidecarChecksFinished());
        refresher.onDownloadRemoved(new SubtitleDownloadRemoved(10L));
        refresher.onStartup();

        assertThat(queued).isEmpty();
        assertThat(messages()).containsExactly("Subtitle downloads are off: OpenSubtitles downloads need "
            + "OPENSUBTITLES_API_KEY, OPENSUBTITLES_USERNAME and OPENSUBTITLES_PASSWORD");
    }

    @Test
    void startUpStartsARunWhenDownloadsAreOnAndSaysNothing() throws NoSuchMethodException {
        when(api.downloadsEnabled()).thenReturn(true);

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
        assertThat(ForcedDownloadRefresher.class.getMethod("onStartup").getAnnotation(EventListener.class).value())
            .containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void startUpWhileARunIsAlreadyGoingSaysNothingAboutDownloadsBeingOff() {
        when(api.downloadsEnabled()).thenReturn(true);
        refresher.startDue();

        refresher.onStartup();

        assertThat(messages()).isEmpty();
        assertThat(queued).hasSize(1);
    }

    @Test
    void anEditionWithNothingToPromoteOrFetchAddsNothing() {
        DueDownload due = new DueDownload(Kind.MISSING, 1L, 10L, "tt1", "Original", null, List.of(), 3, null);
        when(service.due()).thenReturn(List.of(due));

        startAndRun();

        verify(service, never()).add(due, null, NOW);
        assertThat(messages()).contains("Subtitle downloads finished: 0 added, 1 with nothing added, 0 failed");
    }

    @Test
    void everyStepOfTheChainStartsARunAScanASearchTheChecksAndARemoval() {
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of());

        refresher.onScanFinished(new ScanFinished("nightly"));
        queued.remove(0).run();
        refresher.onSearchFinished(new OpenSubtitlesSearchFinished());
        queued.remove(0).run();
        refresher.onChecksFinished(new SidecarChecksFinished());
        queued.remove(0).run();
        refresher.onDownloadRemoved(new SubtitleDownloadRemoved(10L));

        assertThat(queued).hasSize(1);
        verify(service, times(3)).due();
    }

    @Test
    void aSecondStartWhileARunIsGoingIsRefusedButTheRunGoesRoundOnce() {
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of());

        assertThat(refresher.startDue()).isTrue();
        assertThat(refresher.startDue()).isFalse();
        assertThat(refresher.isRunning()).isTrue();
        queued.get(0).run();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(refresher.lastFinishedAt()).isEqualTo(NOW);
        verify(service, times(1)).due();
        assertThat(refresher.startDue()).isTrue();
    }

    @Test
    void aRequestThatArrivesWhileTheRunIsGoingMakesItGoRoundAgain() {
        when(api.downloadsEnabled()).thenReturn(true);
        int[] calls = new int[1];
        when(service.due()).thenAnswer(call -> {
            if (calls[0]++ == 0) {
                refresher.onChecksFinished(new SidecarChecksFinished());
            }
            return List.of();
        });
        refresher.startDue();

        queued.get(0).run();

        verify(service, times(2)).due();
    }

    @Test
    void anExecutorThatRefusesTheRunDoesNotLeaveTheServiceStuckRunning() {
        ForcedDownloadRefresher refusing = new ForcedDownloadRefresher(api, budget, references, service, CLOCK,
            command -> {
                throw new RejectedExecutionException("full");
            }, LIMIT, events);
        when(api.downloadsEnabled()).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(refusing::startDue)
            .isInstanceOf(RejectedExecutionException.class);

        assertThat(refusing.isRunning()).isFalse();
    }

    // --- a run ---

    @Test
    void whenOpenSubtitlesSaysTheLimitIsReachedTheRunWaitsAndAsksForNothing() {
        Instant until = NOW.plusSeconds(3600);
        when(budget.blockedUntil(NOW)).thenReturn(until);

        startAndRun();

        verifyNoInteractions(service);
        assertThat(messages()).containsExactly("Subtitle downloads wait: OpenSubtitles' download limit is reached "
            + "until " + until);
    }

    @Test
    void aPromotionCostsNoDownloadAndNoBudgetAndStartsTheTimingCheck() {
        OpenSubtitlesReference kept = stored(1, 5011, CUES);
        DueDownload due = new DueDownload(Kind.REPLACEMENT, 1L, 10L, "tt1", "Original", kept,
            List.of(candidate(5012)), 3, "v10");
        when(service.due()).thenReturn(List.of(due));
        when(service.add(due, kept, NOW)).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(budget, never()).take(any(LocalDate.class), anyInt());
        verify(api, never()).download(anyInt());
        verify(events).publishEvent(new SubtitleDownloadsFinished());
        assertThat(messages()).containsExactly(
            "Edition 10: promoted OpenSubtitles file 5011 already downloaded, ADDED",
            "Subtitle downloads finished: 1 added, 0 with nothing added, 0 failed");
    }

    @Test
    void aPromotionThatWasDroppedAddsNothingAndStartsNoTimingCheck() {
        OpenSubtitlesReference kept = stored(1, 5011, CUES);
        DueDownload due = new DueDownload(Kind.REPLACEMENT, 1L, 10L, "tt1", "Original", kept, List.of(), 3, "v10");
        when(service.due()).thenReturn(List.of(due));
        when(service.add(due, kept, NOW)).thenReturn(AddOutcome.DROPPED);

        startAndRun();

        verifyNoInteractions(events);
        assertThat(messages()).contains(
            "Subtitle downloads finished: 0 added, 1 with nothing added, 0 failed");
    }

    @Test
    void theBestCandidateIsDownloadedStoredAtOnceAndMadeTheDownloadWithinTheMissingShare() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011, 5012);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(api).download(5011);
        verify(api, never()).download(5012);
        verify(budget, never()).refund(any());
        verify(events).publishEvent(new SubtitleDownloadsFinished());
        assertThat(messages()).contains(
            "Edition 10: downloaded OpenSubtitles file 5011 for a missing subtitle, ADDED");
    }

    @Test
    void aReplacementIsDownloadedWithinItsLowerShareOfTheBudget() {
        DueDownload due = due(Kind.REPLACEMENT, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, REPLACEMENT_LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(budget).take(DAY, REPLACEMENT_LIMIT);
        verify(budget, never()).take(DAY, LIMIT);
    }

    @Test
    void aFullSubtitlesFileIsNotMadeTheDownloadAndTheNextCandidateIsTried() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011, 5012);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(lines(300)));
        when(api.download(5012)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(api).download(5011);
        verify(api).download(5012);
        assertThat(messages()).contains("Edition 10: OpenSubtitles file 5011 skipped, it is full subtitles");
        verify(service).add(eq(due), argThat(r -> r.getOsFileId() == 5012), eq(NOW));
    }

    @Test
    void aFileThatCouldNotBeReadIsSkippedAndNeverFetchedAgain() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011, 5012);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(List.of()));
        when(api.download(5012)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        assertThat(messages()).contains("Edition 10: OpenSubtitles file 5011 skipped, it could not be read");
        verify(references).saveAndFlush(argThat(r -> r.getOsFileId() == 5011
            && r.getFailedDownloads() == OpenSubtitlesReference.MAX_FAILED_DOWNLOADS));
    }

    @Test
    void anEditionIsNeverTriedWithMoreFilesThanItHasLeftUnderTheCap() {
        DueDownload due = new DueDownload(Kind.MISSING, 1L, 10L, "tt1", "Original", null,
            List.of(candidate(5011), candidate(5012)), 1, null);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(lines(300)));
        storingReferences();

        startAndRun();

        verify(api, never()).download(5012);
        verify(service, never()).add(any(), any(), any());
        assertThat(messages()).contains(
            "Edition 10: no more OpenSubtitles files are tried (at most 3 are ever made into downloads)");
        verifyNoInteractions(events);
    }

    @Test
    void aStoredReferenceOfACandidateIsUsedWithNoDownload() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011);
        OpenSubtitlesReference kept = stored(1, 5011, CUES);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(java.util.Optional.of(kept));
        when(service.add(due, kept, NOW)).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(budget, never()).take(any(LocalDate.class), anyInt());
        verify(api, never()).download(anyInt());
    }

    @Test
    void missingSubtitlesAreWorkedOnBeforeReplacementsAsTheServiceOrdersThem() {
        DueDownload missing = due(Kind.MISSING, 2, 20, 5022);
        DueDownload replacement = due(Kind.REPLACEMENT, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(missing, replacement));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(budget.take(DAY, REPLACEMENT_LIMIT)).thenReturn(true);
        when(api.download(anyInt())).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(any(), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        var order = org.mockito.Mockito.inOrder(api);
        order.verify(api).download(5022);
        order.verify(api).download(5011);
    }

    @Test
    void whenTheBudgetIsSpentTheRunStopsAndLaterEditionsAreLeftForTomorrow() {
        when(service.due()).thenReturn(List.of(due(Kind.MISSING, 1, 10, 5011), due(Kind.MISSING, 2, 20, 5022)));
        when(budget.take(DAY, LIMIT)).thenReturn(false);

        startAndRun();

        verify(budget, times(1)).take(DAY, LIMIT);
        verify(api, never()).download(anyInt());
        verifyNoInteractions(events);
        assertThat(messages()).containsExactly("Subtitle downloads stopped: today's download budget is spent",
            NOTHING_DONE);
    }

    @Test
    void whenTheReplacementShareIsSpentNoReplacementIsDownloaded() {
        when(service.due()).thenReturn(List.of(due(Kind.REPLACEMENT, 1, 10, 5011)));
        when(budget.take(DAY, REPLACEMENT_LIMIT)).thenReturn(false);

        startAndRun();

        verify(api, never()).download(anyInt());
        assertThat(messages()).contains("Subtitle downloads stopped: today's download budget is spent");
    }

    @Test
    void aQuotaAnswerSpendsTheDayAndStopsTheRunWithNothingStored() {
        Instant reset = Instant.parse("2024-04-01T00:00:00Z");
        when(service.due()).thenReturn(List.of(due(Kind.MISSING, 1, 10, 5011), due(Kind.MISSING, 2, 20, 5022)));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesQuotaException(reset));

        startAndRun();

        verify(budget).exhaust(DAY, LIMIT, reset);
        verify(api, never()).download(5022);
        verify(references, never()).saveAndFlush(any());
        verifyNoInteractions(events);
        assertThat(messages()).contains("Subtitle downloads stopped: OpenSubtitles' download limit is reached");
    }

    @Test
    void anOutageGivesTheDownloadBackStopsTheRunAndAddsNothing() {
        when(service.due()).thenReturn(List.of(due(Kind.MISSING, 1, 10, 5011), due(Kind.MISSING, 2, 20, 5022)));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesUnavailableException("down"));

        startAndRun();

        verify(budget).refund(DAY);
        verify(api, never()).download(5022);
        verify(service, never()).add(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void onceOpenSubtitlesHasChargedNothingIsGivenBackWhateverGoesWrongAfter() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(due, due(Kind.MISSING, 2, 20, 5022)));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesChargedException("timed out", false));
        storingReferences();

        startAndRun();

        verify(budget, never()).refund(any());
        verify(api, never()).download(5022);
        verify(references).saveAndFlush(argThat(r -> r.getOsFileId() == 5011
            && r.getFailedDownloads() == 1));
        verifyNoInteractions(events);
    }

    @Test
    void aPermanentChargedFailureMakesThatFileFinalAndTheNextCandidateIsTriedWithNoRefund() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011, 5012);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesChargedException("refused", true));
        when(api.download(5012)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        verify(budget, never()).refund(any());
        verify(references).saveAndFlush(argThat(r -> r.getOsFileId() == 5011
            && r.getFailedDownloads() == OpenSubtitlesReference.MAX_FAILED_DOWNLOADS));
        verify(service).add(eq(due), argThat(r -> r.getOsFileId() == 5012), eq(NOW));
    }

    @Test
    void aFailureOnOneEditionIsCountedAndTheRunGoesOn() {
        DueDownload first = due(Kind.MISSING, 1, 10, 5011);
        DueDownload second = due(Kind.MISSING, 2, 20, 5022);
        when(service.due()).thenReturn(List.of(first, second));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new IllegalStateException("boom"));
        when(api.download(5022)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(second), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.ADDED);

        startAndRun();

        assertThat(messages()).contains("Subtitle downloads finished: 1 added, 0 with nothing added, 1 failed");
        verify(events).publishEvent(new SubtitleDownloadsFinished());
    }

    @Test
    void aBusyLibraryAddsNothingButTheStoredReferenceIsKeptForTheNextRun() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), any(OpenSubtitlesReference.class), eq(NOW))).thenReturn(AddOutcome.BUSY);

        startAndRun();

        verify(references).saveAndFlush(argThat(r -> r.getOsFileId() == 5011 && r.getCueCount() == 2));
        verifyNoInteractions(events);
    }

    @Test
    void aBusyLibraryIsRetriedByTheHourlyNudgeAndOnlyThen() {
        DueDownload due = due(Kind.MISSING, 1, 10, 5011);
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of(due), List.of());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES));
        storingReferences();
        when(service.add(eq(due), argThat(r -> r.getOsFileId() == 5011), eq(NOW))).thenReturn(AddOutcome.BUSY);
        refresher.startIfDeferred();
        assertThat(queued).isEmpty();

        refresher.startDue();
        queued.remove(0).run();
        refresher.startIfDeferred();
        assertThat(queued).hasSize(1);

        queued.remove(0).run();
        refresher.startIfDeferred();
        assertThat(queued).isEmpty();
    }

    @Test
    void aBusyPromotionIsDeferredToo() {
        OpenSubtitlesReference kept = stored(1, 5011, CUES);
        DueDownload due = new DueDownload(Kind.REPLACEMENT, 1L, 10L, "tt1", "Original", kept, List.of(), 3, "v10");
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of(due));
        when(service.add(due, kept, NOW)).thenReturn(AddOutcome.BUSY);

        refresher.startDue();
        queued.remove(0).run();
        refresher.startIfDeferred();

        assertThat(queued).hasSize(1);
    }

    @Test
    void aFailingListenerOfTheDownloadsDoesNotTouchTheRun() {
        OpenSubtitlesReference kept = stored(1, 5011, CUES);
        DueDownload due = new DueDownload(Kind.REPLACEMENT, 1L, 10L, "tt1", "Original", kept, List.of(), 3, "v10");
        when(service.due()).thenReturn(List.of(due));
        when(service.add(due, kept, NOW)).thenReturn(AddOutcome.ADDED);
        doThrow(new IllegalStateException("listener")).when(events).publishEvent(any(SubtitleDownloadsFinished.class));

        startAndRun();

        assertThat(messages()).contains("A listener of the subtitle downloads failed: IllegalStateException");
    }

    @Test
    void aFailureOfTheWholeRunIsLoggedAndNeverThrown() {
        when(service.due()).thenThrow(new IllegalStateException("database"));

        startAndRun();

        assertThat(logged.list).anyMatch(e -> e.getFormattedMessage().equals("Subtitle downloads failed")
            && e.getLevel() == ch.qos.logback.classic.Level.ERROR);
        assertThat(refresher.isRunning()).isFalse();
    }
}
