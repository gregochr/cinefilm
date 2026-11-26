package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.subtitle.SidecarCheckRefresherRetimeTest.due;
import static gregochr.cinefilm.subtitle.SidecarCheckRefresherRetimeTest.reference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
import gregochr.cinefilm.transcode.SessionManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The re-check with the real timing refresher and the real check refresher, only their services mocked below them,
 * a publisher that delivers events at once and executors that hold their work: what happens and in which order.
 */
@ExtendWith(MockitoExtension.class)
class SubtitleRetimeRecheckFlowTest {

    private static final Set<Long> IDS = Set.of(5L, 6L);
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();
    private static final List<Long> TRACK = IntStream.range(0, 120).mapToObj(i -> 1_000L + i * 7_000L).toList();
    private static final List<SubtitleCue> CUES = List.of(new SubtitleCue(10_000, 11_000, "a"),
        new SubtitleCue(20_000, 21_000, "b"));
    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);

    @Mock
    private SubtitleRetimeService retimeService;
    @Mock
    private SidecarTimingService timingService;
    @Mock
    private EmbeddedCueReader reader;
    @Mock
    private LibraryRoot root;
    @Mock
    private SessionManager sessions;
    @Mock
    private OpenSubtitlesApi api;
    @Mock
    private OpenSubtitlesBudget budget;
    @Mock
    private OpenSubtitlesReferenceRepository references;
    @Mock
    private SidecarCheckService checkService;
    @Mock
    private FullFitService fullFit;
    @Mock
    private TimingReferenceService timingReferences;
    @Mock
    private FilmRates filmRates;

    private final List<String> happened = new ArrayList<>();
    private final List<Runnable> checkQueue = new ArrayList<>();
    private final List<Runnable> timingQueue = new ArrayList<>();
    private SidecarCheckRefresher checkRefresher;
    private SidecarTimingRefresher timingRefresher;
    private SubtitleRetimeRecheck recheck;
    private SubtitleRetimeJob job;
    private boolean checksInline;

    @BeforeEach
    void setUp() {
        checkRefresher = new SidecarCheckRefresher(api, budget, references, checkService,
            fullFit, timingReferences, CLOCK, task -> {
                if (checksInline) {
                    task.run();
                } else {
                    checkQueue.add(task);
                }
            }, new FakeDailyLimit(20), event -> { }, filmRates);
        timingRefresher = new SidecarTimingRefresher(timingService, reader, root, sessions,
            CLOCK, timingQueue::add, true, event -> {
                if (event instanceof SidecarTimingFinished finished) {
                    happened.add("timing announced");
                    checkRefresher.onTimingFinished(finished);
                }
            }, new ReadFailures());
        recheck = new SubtitleRetimeRecheck(retimeService, timingRefresher, checkRefresher,
            new BusyRetry(pause -> { }));
        // A publisher that delivers the retime job's event to the real listeners, on the publishing thread.
        job = new SubtitleRetimeJob(retimeService, recheck, new BusyRetry(pause -> { }), Runnable::run, "on",
            event -> {
                if (event instanceof SubtitleCuesRewritten rewritten) {
                    happened.add("cues rewritten announced");
                    checkRefresher.onCuesRewritten(rewritten);
                    timingRefresher.onCuesRewritten(rewritten);
                }
            });
    }

    private void filmFits(long filmId, long sidecarId) {
        FitJob job = new FitJob(filmId, sidecarId, "id" + sidecarId, "key" + sidecarId, STARTS, TRACK, null);
        when(timingService.fitJobs(filmId)).thenReturn(List.of(job));
        when(timingService.save(job, TimingFit.fit(STARTS, TRACK), NOW)).thenAnswer(call -> {
            happened.add("fit film " + filmId);
            return SaveOutcome.APPLIED;
        });
    }

    private void checkSaves(DueCheck due, OpenSubtitlesReference reference) {
        when(checkService.save(due, reference, AGREES, NOW)).thenAnswer(call -> {
            happened.add("check edition " + due.editionId());
            return true;
        });
    }

    @Test
    void eachEditionIsCheckedAfterItsFilmsFitFromStoredReferencesAndNoFullPassStartsEarly() {
        DueCheck due10 = due(1, 10, 5011);
        DueCheck due20 = due(2, 20, 5021, 5022);
        OpenSubtitlesReference ref11 = reference(5011, 1, 5011);
        OpenSubtitlesReference ref22 = reference(5022, 2, 5022);
        when(retimeService.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(api.downloadsEnabled()).thenReturn(true);
        filmFits(1L, 5L);
        filmFits(2L, 6L);
        when(checkService.due(List.of(10L))).thenReturn(List.of(due10));
        when(checkService.due(List.of(20L))).thenReturn(List.of(due20));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(ref11));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5021)).thenReturn(Optional.empty());
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 5022)).thenReturn(Optional.of(ref22));
        checkSaves(due10, ref11);
        checkSaves(due20, ref22);

        assertThat(recheck.run(IDS)).isTrue();

        // Edition B's check is saved only after film 2's fit, edition A's after film 1's.
        assertThat(happened).containsExactly("fit film 1", "check edition 10", "fit film 2", "check edition 20");
        assertThat(checkQueue).isEmpty();
        verify(checkService).due(List.of(10L));
        verify(checkService).due(List.of(20L));
        verify(checkService).markTried(10L, "tt1", 5011, NOW);
        verify(checkService).markTried(20L, "tt2", 5022, NOW);
        verify(checkService).save(due10, ref11, AGREES, NOW);
        verify(checkService).save(due20, ref22, AGREES, NOW);
        verifyNoMoreInteractions(checkService);
        verify(api, atLeastOnce()).downloadsEnabled();
        verifyNoMoreInteractions(api);
    }

    @Test
    void aJobRunChecksItsEditionsInTheRunBecauseTheEventIsPublishedAfterTheReCheck() {
        DueCheck due10 = due(1, 10, 5011);
        OpenSubtitlesReference ref11 = reference(5011, 1, 5011);
        when(retimeService.candidateIds()).thenReturn(List.of(5L));
        when(retimeService.process(5L, RetimeMode.ON)).thenReturn(SubtitleRetimeService.Outcome.REWRITTEN);
        when(retimeService.touchedEditions(Set.of(5L))).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(api.downloadsEnabled()).thenReturn(true);
        when(checkService.isTimingCheckEnabled()).thenReturn(true);
        filmFits(1L, 5L);
        when(checkService.due(List.of(10L))).thenReturn(List.of(due10));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(ref11));
        checkSaves(due10, ref11);

        job.run();

        // The film was fitted by the re-check itself (not left to a booked pass) and its edition was checked in the
        // run; then the event, then the announcement of the fit, which asks for the check pass.
        assertThat(happened).containsExactly("fit film 1", "check edition 10", "cues rewritten announced",
            "timing announced");
        assertThat(timingQueue).hasSize(1);
        assertThat(checkQueue).hasSize(1);
    }

    @Test
    void aFitSavedForADownloadOutsideTheTouchedEditionsIsAnnouncedAndItsEditionIsCheckedByThePassThatFollows() {
        DueCheck due10 = due(1, 10, 5011);
        DueCheck due11 = due(1, 11, 5011);
        OpenSubtitlesReference ref11 = reference(5011, 1, 5011);
        FitJob touched = new FitJob(1L, 5L, "id5", "key5", STARTS, TRACK, null);
        FitJob other = new FitJob(1L, 7L, "id7", "key7", STARTS, TRACK, null);
        when(retimeService.candidateIds()).thenReturn(List.of(5L));
        when(retimeService.process(5L, RetimeMode.ON)).thenReturn(SubtitleRetimeService.Outcome.REWRITTEN);
        when(retimeService.touchedEditions(Set.of(5L))).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(api.downloadsEnabled()).thenReturn(true);
        when(checkService.isTimingCheckEnabled()).thenReturn(true);
        when(timingService.fitJobs(1L)).thenReturn(List.of(touched, other));
        for (FitJob fit : List.of(touched, other)) {
            when(timingService.save(fit, TimingFit.fit(STARTS, TRACK), NOW)).thenAnswer(call -> {
                happened.add("fit sidecar " + fit.sidecarId());
                return SaveOutcome.APPLIED;
            });
        }
        when(checkService.due(List.of(10L))).thenReturn(List.of(due10));
        when(checkService.due()).thenReturn(List.of(due11));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(ref11));
        checkSaves(due10, ref11);
        checkSaves(due11, ref11);
        checksInline = true;

        job.run();

        // The film's other download was fitted too, which was announced, and its edition was checked by that pass.
        assertThat(happened).containsExactly("fit sidecar 5", "fit sidecar 7", "check edition 10",
            "cues rewritten announced", "timing announced", "check edition 11");
    }

    @Test
    void eachRewrittenSidecarIsMeasuredOnceBecauseTheFullPassStartsAfterTheEventsForget() {
        DueCheck due10 = due(1, 10, 5011);
        OpenSubtitlesReference ref11 = reference(5011, 1, 5011);
        List<Set<Long>> measuredWhenDueWasRead = new ArrayList<>();
        int[] served = {0};
        FullFitService.Job full = new FullFitService.Job(1L, 10L, "tt1", 5L, 5011L, CUES, 100_000L, null, List.of(),
            Map.of());
        when(fullFit.due(anySet())).thenAnswer(call -> {
            Set<Long> measured = new HashSet<>(call.getArgument(0));
            measuredWhenDueWasRead.add(measured);
            if (measured.contains(5L)) {
                return List.of();
            }
            served[0]++;
            return List.of(full);
        });
        when(retimeService.candidateIds()).thenReturn(List.of(5L));
        when(retimeService.process(5L, RetimeMode.ON)).thenReturn(SubtitleRetimeService.Outcome.REWRITTEN);
        when(retimeService.touchedEditions(Set.of(5L))).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(api.downloadsEnabled()).thenReturn(true);
        when(checkService.isTimingCheckEnabled()).thenReturn(true);
        filmFits(1L, 5L);
        when(checkService.due(List.of(10L))).thenReturn(List.of(due10));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5011)).thenReturn(Optional.of(ref11));
        checkSaves(due10, ref11);
        // A pass measured the download before its cues were rewritten: it is in the probe's done set.
        checkRefresher.run();

        checksInline = true;
        job.run();
        // The next pass of any kind finds nothing to measure.
        checkRefresher.run();

        // Measured before the rewrite and once after it, not twice after: the pass the job started came after the
        // event's forget, so the sidecar is marked done on its new cues and the next pass leaves it.
        assertThat(served[0]).isEqualTo(2);
        assertThat(measuredWhenDueWasRead).hasSize(3);
        assertThat(measuredWhenDueWasRead.get(1)).isEmpty();
        assertThat(measuredWhenDueWasRead.get(2)).containsExactly(5L);
    }
}
