package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearchFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.LoggerFactory;

/**
 * The check of the owner's subtitle files against OpenSubtitles: which editions are compared with what, the stored
 * reference that is reused, the download budget and every way a run stops, and that an outage is never stored as a
 * verdict. Runs on an executor the test controls.
 */
@ExtendWith(MockitoExtension.class)
class SidecarCheckRefresherTest {

    /** The UTC day of {@code NOW} (in London it is already 1 April). */
    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 90;
    private static final String NOTHING_DONE =
        "Subtitle file checks finished: 0 saved, 0 changed meanwhile, 0 unusable references, 0 failed";
    private static final List<SubtitleCue> CUES = List.of(new SubtitleCue(10_000, 11_000, "a"),
        new SubtitleCue(20_000, 21_000, "b"));
    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of());

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private SidecarCheckService service;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(SidecarCheckRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private SidecarCheckRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SidecarCheckRefresher(api, budget, references, service, CLOCK, queued::add, LIMIT);
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

    private static DueCheck due(long filmId, long editionId, long fileId) {
        return new DueCheck(filmId, editionId, "tt" + filmId, fileId, "Release " + fileId, 1, "v" + editionId, false,
            CUES);
    }

    private static OpenSubtitlesReference stored(long id, long filmId, long fileId, List<SubtitleCue> cues) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(id);
        reference.setFilmId(filmId);
        reference.setImdbId("tt" + filmId);
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFailedDownloads(cues.isEmpty() ? OpenSubtitlesReference.MAX_FAILED_DOWNLOADS : 0);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private static OpenSubtitlesDownload downloaded(List<SubtitleCue> cues, Integer remaining, Instant reset) {
        return new OpenSubtitlesDownload(new SrtParser.Parsed(cues,
            new SrtParser.Report("UTF-8", 0, 0, 0, false, false, false, cues.size())), remaining, reset);
    }

    /** Stores what {@code saveAndFlush} is given, as the database would, with the id 77. */
    private void storingReferences() {
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(77L);
            return reference;
        });
    }

    private void savingEverything() {
        when(service.save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class))).thenReturn(true);
    }

    /** Starts a run and executes what was queued, as the executor's thread would. */
    private void startAndRun() {
        when(api.downloadsEnabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    @Test
    void downloadsAreOffWithoutACredentialAndNothingStartsAndStartUpSaysSoOnce() {
        when(api.downloadsEnabled()).thenReturn(false);

        assertThat(refresher.startDue()).isFalse();
        refresher.onScanFinished(new ScanFinished("admin"));
        refresher.onSearchFinished(new OpenSubtitlesSearchFinished());
        refresher.onStartup();

        assertThat(queued).isEmpty();
        assertThat(refresher.startDue()).isFalse();
        assertThat(messages()).containsExactly("Subtitle file checks are off: OpenSubtitles downloads need "
            + "OPENSUBTITLES_API_KEY, OPENSUBTITLES_USERNAME and OPENSUBTITLES_PASSWORD");
    }

    @Test
    void startUpStartsARunWhenDownloadsAreOnAndSaysNothing() {
        when(api.downloadsEnabled()).thenReturn(true);

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    @Test
    void theApplicationReadyEventIsWhatStartUpListensTo() throws NoSuchMethodException {
        var listener = SidecarCheckRefresher.class.getMethod("onStartup")
            .getAnnotation(org.springframework.context.event.EventListener.class);

        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void aScanAndAFinishedSearchEachStartARun() {
        when(api.downloadsEnabled()).thenReturn(true);

        refresher.onScanFinished(new ScanFinished("nightly"));
        queued.get(0).run();
        refresher.onSearchFinished(new OpenSubtitlesSearchFinished());

        assertThat(queued).hasSize(2);
    }

    @Test
    void aSecondStartWhileARunIsGoingIsRefusedAndOnlyOneRunIsQueued() {
        when(api.downloadsEnabled()).thenReturn(true);

        assertThat(refresher.startDue()).isTrue();
        assertThat(refresher.startDue()).isFalse();
        refresher.onScanFinished(new ScanFinished("admin"));
        refresher.onSearchFinished(new OpenSubtitlesSearchFinished());

        assertThat(queued).hasSize(1);
        when(service.due()).thenReturn(List.of());
        queued.get(0).run();
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(2);
    }

    @Test
    void anExecutorThatRefusesTheRunDoesNotLeaveTheServiceStuckRunning() {
        SidecarCheckRefresher refusing = new SidecarCheckRefresher(api, budget, references, service, CLOCK,
            command -> {
                throw new RejectedExecutionException("full");
            }, LIMIT);
        when(api.downloadsEnabled()).thenReturn(true);

        assertThatThrownBy(refusing::startDue).isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(refusing::startDue).isInstanceOf(RejectedExecutionException.class);
    }

    /** The refresher on an executor that runs the work at once, so a trigger can arrive from inside a run. */
    private SidecarCheckRefresher synchronous() {
        return new SidecarCheckRefresher(api, budget, references, service, CLOCK, Runnable::run, LIMIT);
    }

    @Test
    void aTriggerDuringARunMakesExactlyOneMoreRunHoweverManyArrive() {
        SidecarCheckRefresher inline = synchronous();
        when(api.downloadsEnabled()).thenReturn(true);
        List<Boolean> started = new ArrayList<>();
        when(service.due()).thenAnswer(call -> {
            if (started.isEmpty()) {
                started.add(inline.startDue());
                inline.onScanFinished(new ScanFinished("admin"));
                inline.onSearchFinished(new OpenSubtitlesSearchFinished());
            }
            return List.of();
        });

        assertThat(inline.startDue()).isTrue();

        assertThat(started).containsExactly(false);
        verify(service, times(2)).due();
    }

    @Test
    void aRunWithNoTriggerDuringItRunsOnce() {
        SidecarCheckRefresher inline = synchronous();
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of());

        inline.startDue();

        verify(service).due();
    }

    @Test
    void anEditionIsComparedWithItsStoredReferenceAndTheResultIsSavedWithNoDownload() {
        DueCheck due = due(1, 10, 5011);
        OpenSubtitlesReference reference = stored(8, 1, 5011, CUES);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(reference));
        when(service.save(due, reference, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(due, reference, AGREES, NOW);
        verify(api).downloadsEnabled();
        verify(budget).blockedUntil(NOW);
        verifyNoMoreInteractions(api, budget);
        assertThat(messages()).containsExactly("Subtitle file checks finished: 1 saved, 0 changed meanwhile, "
            + "0 unusable references, 0 failed");
    }

    @Test
    void theResultComparesTheServedCuesWithTheReferenceCues() {
        List<SubtitleCue> reference = List.of(new SubtitleCue(11_200, 12_000, "x"),
            new SubtitleCue(21_200, 22_000, "y"));
        DueCheck due = due(1, 10, 5011);
        OpenSubtitlesReference stored = stored(8, 1, 5011, reference);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(stored));
        Result expected = new Result(2, 2, 1.0, 1200, Verdict.AGREES, List.of("OpenSubtitles' file is 1.2 s later"));
        when(service.save(due, stored, expected, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(due, stored, expected, NOW);
    }

    @Test
    void aMissingReferenceIsDownloadedWithinTheBudgetStoredAtOnceAndThenUsed() {
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, 50, Instant.parse("2024-04-01T00:00:00Z")));
        storingReferences();
        savingEverything();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);

        startAndRun();

        InOrder order = inOrder(budget, api, references, service);
        order.verify(budget).take(DAY, LIMIT);
        order.verify(api).download(5011);
        order.verify(references).saveAndFlush(saved.capture());
        order.verify(service).save(due, saved.getValue(), AGREES, NOW);
        OpenSubtitlesReference reference = saved.getValue();
        assertThat(reference.getFilmId()).isEqualTo(1L);
        assertThat(reference.getImdbId()).isEqualTo("tt1");
        assertThat(reference.getOsFileId()).isEqualTo(5011L);
        assertThat(reference.getRelease()).isEqualTo("Release 5011");
        assertThat(reference.getCuesText()).isEqualTo(SrtParser.format(CUES));
        assertThat(reference.getCueCount()).isEqualTo(2);
        assertThat(reference.getFetchedAt()).isEqualTo(NOW);
        assertThat(reference.isUsable()).isTrue();
    }

    @Test
    void theBudgetDayIsTheUtcDayNotTheLondonDay() {
        // NOW is 31 March 23:30 UTC, which is 1 April in London.
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(false);

        startAndRun();

        verify(budget).take(DAY, LIMIT);
        assertThat(CLOCK.getZone().getId()).isEqualTo("Europe/London");
    }

    @Test
    void theDailyLimitIsWhatTheBudgetIsAskedFor() {
        SidecarCheckRefresher five = new SidecarCheckRefresher(api, budget, references, service, CLOCK,
            queued::add, 5);
        DueCheck due = due(1, 10, 5011);
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, 5)).thenReturn(false);

        five.startDue();
        queued.get(0).run();

        verify(budget).take(DAY, 5);
    }

    @Test
    void twoEditionsOfOneFilmShareOneDownload() {
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(1, 11, 5011);
        when(service.due()).thenReturn(List.of(first, second));
        OpenSubtitlesReference[] kept = new OpenSubtitlesReference[1];
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011))
            .thenAnswer(call -> Optional.ofNullable(kept[0]));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            kept[0] = call.getArgument(0);
            kept[0].setId(77L);
            return kept[0];
        });
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, 50, null));
        savingEverything();

        startAndRun();

        verify(api, times(1)).download(5011);
        verify(service).save(first, kept[0], AGREES, NOW);
        verify(service).save(second, kept[0], AGREES, NOW);
        assertThat(messages()).contains("Subtitle file checks finished: 2 saved, 0 changed meanwhile, "
            + "0 unusable references, 0 failed");
    }

    @Test
    void whenTheBudgetIsSpentTheRunStopsWithNothingDownloadedOrStoredAndLaterEditionsAreLeftForTomorrow() {
        when(service.due()).thenReturn(List.of(due(1, 10, 5011), due(2, 20, 5022)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(false);

        startAndRun();

        verify(api).downloadsEnabled();
        verify(service).due();
        verifyNoMoreInteractions(api, references, service);
        assertThat(messages()).containsExactly("Subtitle file checks stopped: today's download budget is spent",
            NOTHING_DONE);
    }

    @Test
    void aReferenceAlreadyStoredIsStillUsedWhenTheBudgetIsSpent() {
        DueCheck stored = due(1, 10, 5011);
        DueCheck missing = due(2, 20, 5022);
        OpenSubtitlesReference reference = stored(8, 1, 5011, CUES);
        when(service.due()).thenReturn(List.of(stored, missing));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(reference));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(false);
        when(service.save(stored, reference, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(stored, reference, AGREES, NOW);
        verify(budget).take(DAY, LIMIT);
    }

    @Test
    void aDownloadThatLeavesNothingInTheAllowanceSpendsTheDayWithTheResetTimeAndTheNextDownloadStops() {
        Instant reset = Instant.parse("2024-04-01T00:00:00Z");
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(2, 20, 5022);
        when(service.due()).thenReturn(List.of(first, second));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true, false);
        when(api.download(5011)).thenReturn(downloaded(CUES, 0, reset));
        storingReferences();
        savingEverything();

        startAndRun();

        ArgumentCaptor<OpenSubtitlesReference> kept = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        InOrder order = inOrder(budget, service);
        order.verify(budget).take(DAY, LIMIT);
        order.verify(budget).exhaust(DAY, LIMIT, reset);
        order.verify(service).save(org.mockito.ArgumentMatchers.eq(first), kept.capture(),
            org.mockito.ArgumentMatchers.eq(AGREES), org.mockito.ArgumentMatchers.eq(NOW));
        order.verify(budget).take(DAY, LIMIT);
        assertThat(kept.getValue().getOsFileId()).isEqualTo(5011L);
        assertThat(messages()).contains("Subtitle file checks stopped: today's download budget is spent");
    }

    @Test
    void anAllowanceOfOneLeftDoesNotSpendTheDayButZeroAndLessDo() {
        for (int remaining : new int[] {1, 0, -1}) {
            DueCheck due = due(1, 10, 5011);
            when(service.due()).thenReturn(List.of(due));
            when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
            when(budget.take(DAY, LIMIT)).thenReturn(true);
            when(api.download(5011)).thenReturn(downloaded(CUES, remaining, null));
            storingReferences();
            when(api.downloadsEnabled()).thenReturn(true);
            queued.clear();

            SidecarCheckRefresher fresh = new SidecarCheckRefresher(api, budget, references, service, CLOCK,
                queued::add, LIMIT);
            fresh.startDue();
            queued.get(0).run();

            verify(budget, times(remaining > 0 ? 0 : 1)).exhaust(DAY, LIMIT, null);
            org.mockito.Mockito.clearInvocations(budget);
        }
    }

    @Test
    void anAllowanceThatIsNotStatedLeavesTheDayAlone() {
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, null, null));
        storingReferences();

        startAndRun();

        verify(budget).blockedUntil(NOW);
        verify(budget).take(DAY, LIMIT);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void aQuotaAnswerSpendsTheDayUntilTheResetTimeAndStopsTheRunStoringNothing() {
        Instant reset = Instant.parse("2024-04-01T00:00:00Z");
        when(service.due()).thenReturn(List.of(due(1, 10, 5011), due(2, 20, 5022)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesQuotaException(reset));

        startAndRun();

        verify(budget).exhaust(DAY, LIMIT, reset);
        verify(service).due();
        verifyNoMoreInteractions(references, service);
        assertThat(messages()).containsExactly(
            "Subtitle file checks stopped: OpenSubtitles' download limit is reached", NOTHING_DONE);
    }

    @Test
    void aResetTimeBeyondThirtySixHoursIsNotBelievedAndOneAtTheLimitIs() {
        Instant limit = NOW.plus(Duration.ofHours(36));
        when(service.due()).thenReturn(List.of(due(1, 10, 5011)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesQuotaException(limit.plusMillis(1)));

        startAndRun();

        verify(budget).exhaust(DAY, LIMIT, limit);
        assertThat(SidecarCheckRefresher.MAX_BLOCK).isEqualTo(Duration.ofHours(36));
    }

    @Test
    void aResetTimeAtTheLimitOrAnEarlierOneIsKept() {
        for (Instant reset : new Instant[] {NOW.plus(Duration.ofHours(36)), NOW.plus(Duration.ofHours(1)), NOW}) {
            queued.clear();
            when(service.due()).thenReturn(List.of(due(1, 10, 5011)));
            when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
            when(budget.take(DAY, LIMIT)).thenReturn(true);
            org.mockito.Mockito.doThrow(new OpenSubtitlesQuotaException(reset)).when(api).download(5011);
            when(api.downloadsEnabled()).thenReturn(true);
            SidecarCheckRefresher fresh = new SidecarCheckRefresher(api, budget, references, service, CLOCK,
                queued::add, LIMIT);

            fresh.startDue();
            queued.get(0).run();

            verify(budget).exhaust(DAY, LIMIT, reset);
            org.mockito.Mockito.clearInvocations(budget);
        }
    }

    @Test
    void aQuotaAnswerWithNoResetTimeSpendsTheDayWithNone() {
        when(service.due()).thenReturn(List.of(due(1, 10, 5011)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesQuotaException(null));

        startAndRun();

        verify(budget).exhaust(DAY, LIMIT, null);
    }

    @Test
    void anOutageStopsTheRunGivesTheDownloadBackAndStoresNothingNotEvenAVerdict() {
        when(service.due()).thenReturn(List.of(due(1, 10, 5011), due(2, 20, 5022)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesUnavailableException("OpenSubtitles unavailable"));

        startAndRun();

        verify(budget).refund(DAY);
        verify(service).due();
        verifyNoMoreInteractions(references, service);
        assertThat(messages()).containsExactly("Subtitle file checks stopped: OpenSubtitles unavailable", NOTHING_DONE);
        assertThat(refresher.startDue()).isTrue();
    }

    @Test
    void anOutageNeverTouchesTheExhaustedFlag() {
        when(service.due()).thenReturn(List.of(due(1, 10, 5011)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesUnavailableException("OpenSubtitles unavailable"));

        startAndRun();

        verify(budget).blockedUntil(NOW);
        verify(budget).take(DAY, LIMIT);
        verify(budget).refund(DAY);
        verifyNoMoreInteractions(budget);
    }

    @Test
    void whileOpenSubtitlesSaidItsLimitIsReachedNothingIsAskedOrDownloadedUntilTheResetTime() {
        Instant until = NOW.plus(Duration.ofHours(3));
        when(budget.blockedUntil(NOW)).thenReturn(until);

        startAndRun();

        verify(api).downloadsEnabled();
        verifyNoMoreInteractions(api, references, service);
        assertThat(messages()).containsExactly(
            "Subtitle file checks wait: OpenSubtitles' download limit is reached until " + until);
    }

    @Test
    void anEditionThatChangedWhileItWasBeingCheckedHasItsResultDroppedAndTheRunCarriesOn() {
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(2, 20, 5022);
        OpenSubtitlesReference one = stored(8, 1, 5011, CUES);
        OpenSubtitlesReference two = stored(9, 2, 5022, CUES);
        when(service.due()).thenReturn(List.of(first, second));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(one));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(two));
        when(service.save(first, one, AGREES, NOW)).thenReturn(false);
        when(service.save(second, two, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(first, one, AGREES, NOW);
        verify(service).save(second, two, AGREES, NOW);
        assertThat(messages()).containsExactly("Subtitle file checks finished: 1 saved, 1 changed meanwhile, "
            + "0 unusable references, 0 failed");
    }

    @Test
    void aDownloadThatHasNoCuesIsStoredAsUnusableAndNothingIsComparedOrSaved() {
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(List.of(), 50, null));
        storingReferences();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);

        startAndRun();

        verify(references).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isUsable()).isFalse();
        assertThat(saved.getValue().getCueCount()).isZero();
        assertThat(saved.getValue().getCuesText()).isEmpty();
        assertThat(saved.getValue().getOsFileId()).isEqualTo(5011L);
        verifyNoMoreInteractions(service);
        assertThat(messages()).containsExactly("Subtitle file checks finished: 0 saved, 0 changed meanwhile, "
            + "1 unusable references, 0 failed");
    }

    @Test
    void anUnusableReferenceIsNeverDownloadedAgainOnALaterRun() {
        DueCheck due = due(1, 10, 5011);
        OpenSubtitlesReference unusable = stored(8, 1, 5011, List.of());
        when(service.due()).thenReturn(List.of(due), List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(unusable));
        when(api.downloadsEnabled()).thenReturn(true);

        refresher.startDue();
        queued.get(0).run();
        refresher.startDue();
        queued.get(1).run();

        verify(api, times(2)).downloadsEnabled();
        verify(budget, times(2)).blockedUntil(NOW);
        verify(service, times(2)).due();
        verifyNoMoreInteractions(api, budget, service);
        assertThat(messages()).containsExactly(
            "Subtitle file checks finished: 0 saved, 0 changed meanwhile, 1 unusable references, 0 failed",
            "Subtitle file checks finished: 0 saved, 0 changed meanwhile, 1 unusable references, 0 failed");
    }

    @Test
    void aDownloadWhoseNormalisedTextIsOverAMegabyteIsStoredAsUnusable() {
        String big = "x".repeat(SidecarCheckRefresher.MAX_REFERENCE_CHARS);
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(List.of(new SubtitleCue(0, 1000, big)), 50, null));
        storingReferences();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);

        startAndRun();

        verify(references).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isUsable()).isFalse();
        assertThat(saved.getValue().getCuesText()).isEmpty();
        assertThat(SidecarCheckRefresher.MAX_REFERENCE_CHARS).isEqualTo(1024 * 1024);
    }

    @Test
    void aReferenceWhoseNormalisedTextIsExactlyAMegabyteIsKept() {
        // "1\n00:00:00,000 --> 00:00:01,000\n" + text + "\n\n" is 33 + text + 2 characters, less one.
        String text = "x".repeat(SidecarCheckRefresher.MAX_REFERENCE_CHARS - 34);
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        List<SubtitleCue> cues = List.of(new SubtitleCue(0, 1000, text));
        when(api.download(5011)).thenReturn(downloaded(cues, 50, null));
        storingReferences();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);

        startAndRun();

        verify(references).saveAndFlush(saved.capture());
        assertThat(SrtParser.format(cues)).hasSize(SidecarCheckRefresher.MAX_REFERENCE_CHARS);
        assertThat(saved.getValue().isUsable()).isTrue();
    }

    @Test
    void anEditionThatFailsIsCountedAndTheRestAreStillChecked() {
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(2, 20, 5022);
        OpenSubtitlesReference one = stored(8, 1, 5011, CUES);
        OpenSubtitlesReference two = stored(9, 2, 5022, CUES);
        when(service.due()).thenReturn(List.of(first, second));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(one));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(two));
        when(service.save(first, one, AGREES, NOW)).thenThrow(new IllegalStateException("db"));
        when(service.save(second, two, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(second, two, AGREES, NOW);
        assertThat(messages()).containsExactly("Could not check the subtitle of edition 10: IllegalStateException",
            "Subtitle file checks finished: 1 saved, 0 changed meanwhile, 0 unusable references, 1 failed");
    }

    @Test
    void aDownloadThatIsRejectedAsABadRequestFailsOnlyThatEdition() {
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(2, 20, 5022);
        OpenSubtitlesReference two = stored(9, 2, 5022, CUES);
        when(service.due()).thenReturn(List.of(first, second));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(two));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new IllegalArgumentException("Not a file id"));
        when(service.save(second, two, AGREES, NOW)).thenReturn(true);

        startAndRun();

        assertThat(messages()).contains("Could not check the subtitle of edition 10: IllegalArgumentException",
            "Subtitle file checks finished: 1 saved, 0 changed meanwhile, 0 unusable references, 1 failed");
    }

    @Test
    void aReferenceAnotherWriterStoredFirstIsUsedInsteadOfFailing() {
        DueCheck due = due(1, 10, 5011);
        OpenSubtitlesReference theirs = stored(5, 1, 5011, CUES);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011))
            .thenReturn(Optional.empty(), Optional.of(theirs));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, 50, null));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(service.save(due, theirs, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(due, theirs, AGREES, NOW);
    }

    @Test
    void aReferenceThatCannotBeStoredAndIsNotThereEitherFailsThatEdition() {
        DueCheck due = due(1, 10, 5011);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011))
            .thenReturn(Optional.empty(), Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, 50, null));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        startAndRun();

        assertThat(messages())
            .contains("Could not check the subtitle of edition 10: DataIntegrityViolationException");
    }

    @Test
    void aFailureListingTheDueEditionsIsLoggedAndTheServiceCanRunAgain() {
        when(service.due()).thenThrow(new IllegalStateException("database down"));

        startAndRun();

        assertThat(messages()).contains("Subtitle file checks failed");
        assertThat(refresher.startDue()).isTrue();
    }

    @Test
    void theRunningFlagAndTheFinishTimeAreSetAndClearedAroundARun() {
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of());
        assertThat(refresher.isRunning()).isFalse();
        assertThat(refresher.lastFinishedAt()).isNull();

        assertThat(refresher.startDue()).isTrue();
        assertThat(refresher.isRunning()).isTrue();
        assertThat(refresher.lastFinishedAt()).isNull();
        queued.get(0).run();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(refresher.lastFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRunThatFailsStillClearsTheRunningFlagAndStampsTheFinishTime() {
        when(service.due()).thenThrow(new IllegalStateException("database down"));

        startAndRun();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(refresher.lastFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void nothingDueIsAQuietFinish() {
        when(service.due()).thenReturn(List.of());

        startAndRun();

        assertThat(messages()).containsExactly(NOTHING_DONE);
    }

    @Test
    void anEditionWhoseBestOpenSubtitlesResultIsSplitOverSeveralFilesIsSkippedWithNoDownloadAndNoVerdict() {
        DueCheck split = new DueCheck(1L, 10L, "tt1", 5011, "LOTR.Extended", 2, "v10", false, CUES);
        DueCheck single = due(2, 20, 5022);
        OpenSubtitlesReference two = stored(9, 2, 5022, CUES);
        when(service.due()).thenReturn(List.of(split, single));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(two));
        when(service.save(single, two, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(service).save(single, two, AGREES, NOW);
        verify(api).downloadsEnabled();
        verify(budget).blockedUntil(NOW);
        verifyNoMoreInteractions(api, budget);
        assertThat(messages()).containsExactly("Edition 10 not checked: Only multi-part subtitles on OpenSubtitles",
            "Subtitle file checks finished: 1 saved, 0 changed meanwhile, 0 unusable references, 0 failed");
        assertThat(SidecarCheckRefresher.MULTI_PART).isEqualTo("Only multi-part subtitles on OpenSubtitles");
    }

    @Test
    void startUpStaysQuietWhenARunIsAlreadyGoing() {
        when(api.downloadsEnabled()).thenReturn(true);
        refresher.startDue();

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    private ArgumentCaptor<OpenSubtitlesReference> failureStored(DueCheck due, OpenSubtitlesReference existing,
                                                                 boolean permanent) {
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(due.filmId(), due.imdbId(),
            due.osFileId()))
            .thenReturn(Optional.ofNullable(existing));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(due.osFileId())).thenThrow(new OpenSubtitlesChargedException("OpenSubtitles file refused",
            permanent));
        storingReferences();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        startAndRun();
        verify(references).saveAndFlush(saved.capture());
        return saved;
    }

    @Test
    void aFailureAfterTheLinkWasIssuedIsNeverGivenBackToTheBudgetWhateverItWas() {
        for (boolean permanent : new boolean[] {true, false}) {
            queued.clear();
            org.mockito.Mockito.reset(service, references, api, budget);
            failureStored(due(1, 10, 5011), null, permanent);

            verify(budget).take(DAY, LIMIT);
            verify(budget).blockedUntil(NOW);
            verifyNoMoreInteractions(budget);
        }
    }

    @Test
    void aPermanentFailureAfterChargingMakesTheFileFinalAtOnceAndTheRunCarriesOn() {
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(2, 20, 5022);
        OpenSubtitlesReference two = stored(9, 2, 5022, CUES);
        when(service.due()).thenReturn(List.of(first, second));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(two));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesChargedException("OpenSubtitles file refused", true));
        storingReferences();
        when(service.save(second, two, AGREES, NOW)).thenReturn(true);
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);

        startAndRun();

        verify(references).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOsFileId()).isEqualTo(5011L);
        assertThat(saved.getValue().getCueCount()).isZero();
        assertThat(saved.getValue().getCuesText()).isEmpty();
        assertThat(saved.getValue().getFailedDownloads()).isEqualTo(3);
        assertThat(saved.getValue().isFinal()).isTrue();
        verify(service).save(second, two, AGREES, NOW);
        assertThat(messages()).containsExactly(
            "Subtitle file checks finished: 1 saved, 0 changed meanwhile, 1 unusable references, 0 failed");
    }

    @Test
    void aTransientFailureAfterChargingCountsOneAttemptKeepsTheFileRetryableAndStopsTheRun() {
        ArgumentCaptor<OpenSubtitlesReference> saved = failureStored(due(1, 10, 5011), null, false);

        assertThat(saved.getValue().getFailedDownloads()).isEqualTo(1);
        assertThat(saved.getValue().isFinal()).isFalse();
        assertThat(saved.getValue().getCueCount()).isZero();
        assertThat(messages()).containsExactly("Subtitle file checks stopped: OpenSubtitles file refused",
            NOTHING_DONE);
    }

    @Test
    void theThirdChargedTransientFailureMakesTheFileFinalAndAFourthAttemptNeverHappens() {
        OpenSubtitlesReference twice = stored(8, 1, 5011, List.of());
        twice.setFailedDownloads(2);

        ArgumentCaptor<OpenSubtitlesReference> saved = failureStored(due(1, 10, 5011), twice, false);

        assertThat(saved.getValue()).isSameAs(twice);
        assertThat(twice.getFailedDownloads()).isEqualTo(3);
        assertThat(twice.isFinal()).isTrue();
    }

    @Test
    void aFileThatHasFailedThreeChargedTimesIsNotDownloadedAgain() {
        OpenSubtitlesReference spent = stored(8, 1, 5011, List.of());
        spent.setFailedDownloads(3);
        when(service.due()).thenReturn(List.of(due(1, 10, 5011)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(spent));

        startAndRun();

        verify(api).downloadsEnabled();
        verify(budget).blockedUntil(NOW);
        verifyNoMoreInteractions(api, budget);
    }

    @Test
    void aFileWithTwoFailuresIsTriedAgainAndASuccessUpdatesTheSameRow() {
        DueCheck due = due(1, 10, 5011);
        OpenSubtitlesReference twice = stored(8, 1, 5011, List.of());
        twice.setFailedDownloads(2);
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(twice));
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, 50, null));
        storingReferences();
        when(service.save(due, twice, AGREES, NOW)).thenReturn(true);

        startAndRun();

        verify(references).saveAndFlush(twice);
        assertThat(twice.isUsable()).isTrue();
        assertThat(twice.getCueCount()).isEqualTo(2);
        assertThat(twice.getFailedDownloads()).isEqualTo(2);
        verify(service).save(due, twice, AGREES, NOW);
    }

    @Test
    void aDownloadWithNoCuesIsFinalAtOnce() {
        when(service.due()).thenReturn(List.of(due(1, 10, 5011)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(List.of(), 50, null));
        storingReferences();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);

        startAndRun();

        verify(references).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isFinal()).isTrue();
        assertThat(saved.getValue().getFailedDownloads()).isEqualTo(3);
    }

    @Test
    void threeChargedTransientFailuresInThreeRunsMakeTheFileFinalAndAFourthRunDoesNotDownload() {
        DueCheck due = due(1, 10, 5011);
        OpenSubtitlesReference[] kept = new OpenSubtitlesReference[1];
        when(service.due()).thenReturn(List.of(due));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011))
            .thenAnswer(call -> Optional.ofNullable(kept[0]));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            kept[0] = call.getArgument(0);
            return kept[0];
        });
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenThrow(new OpenSubtitlesChargedException("request failed", false));
        when(api.downloadsEnabled()).thenReturn(true);

        for (int run = 1; run <= 4; run++) {
            queued.clear();
            refresher.startDue();
            queued.get(0).run();
            if (run <= 3) {
                assertThat(kept[0].getFailedDownloads()).isEqualTo(run);
                assertThat(kept[0].isFinal()).isEqualTo(run == 3);
            }
        }

        verify(api, times(3)).download(5011);
        verify(budget, times(3)).take(DAY, LIMIT);
    }

    @Test
    void aReferenceFetchedForAnOldImdbIdIsNotReusedAndTheNewIdGetsItsOwnDownloadAndCheck() {
        DueCheck due = new DueCheck(1L, 10L, "tt9", 5011, "Release", 1, "v10", false, CUES);
        when(service.due()).thenReturn(List.of(due));
        // Only the lookup for the film's current IMDb id is asked, and nothing is stored under it.
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt9", 5011)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011)).thenReturn(downloaded(CUES, 50, null));
        storingReferences();
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        when(service.save(org.mockito.ArgumentMatchers.eq(due), saved.capture(),
            org.mockito.ArgumentMatchers.eq(AGREES), org.mockito.ArgumentMatchers.eq(NOW))).thenReturn(true);

        startAndRun();

        verify(api).download(5011);
        assertThat(saved.getValue().getImdbId()).isEqualTo("tt9");
        assertThat(saved.getValue().getId()).isEqualTo(77L);
    }

    @Test
    void anEditionWhoseBestResultHasAnUnknownFileCountIsSkippedWithNoDownload() {
        DueCheck unknown = new DueCheck(1L, 10L, "tt1", 5011, "Legacy", null, "v10", false, CUES);
        when(service.due()).thenReturn(List.of(unknown));

        startAndRun();

        verify(api).downloadsEnabled();
        verify(budget).blockedUntil(NOW);
        verifyNoMoreInteractions(api, budget);
        assertThat(messages()).contains("Edition 10 not checked: Only multi-part subtitles on OpenSubtitles");
    }
}
