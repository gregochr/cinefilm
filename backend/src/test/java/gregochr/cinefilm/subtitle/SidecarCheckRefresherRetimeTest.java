package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What the check refresher does for the retime job: forgets the sidecars whose cues were rewritten, and checks the
 * due files of chosen editions at once (stored references first), without overlapping the pass over every edition.
 */
@ExtendWith(MockitoExtension.class)
class SidecarCheckRefresherRetimeTest {

    private static final List<SubtitleCue> CUES = List.of(new SubtitleCue(10_000, 11_000, "a"),
        new SubtitleCue(20_000, 21_000, "b"));
    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);

    @Mock
    private OpenSubtitlesApi api;
    @Mock
    private OpenSubtitlesBudget budget;
    @Mock
    private ReferenceFetcher fetcher;
    @Mock
    private SidecarCheckService service;
    @Mock
    private FullFitProbe probe;
    @Mock
    private TimingReferenceFetch timingReference;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private FilmRates filmRates;

    private final List<Runnable> queued = new ArrayList<>();
    private final FakeDailyLimit limits = new FakeDailyLimit(20);
    private SidecarCheckRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new SidecarCheckRefresher(api, budget, fetcher, service, probe, timingReference, CLOCK,
            queued::add, limits, events, filmRates);
    }

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null);
    }

    private static DueCheck due(long filmId, long editionId, long... fileIds) {
        List<OpenSubtitlesResult> candidates = new ArrayList<>();
        for (long fileId : fileIds) {
            candidates.add(candidate(fileId));
        }
        return new DueCheck(filmId, editionId, "tt" + filmId, "Original", candidates, Set.of(), "v" + editionId,
            false, CUES);
    }

    private static OpenSubtitlesReference reference(long id, long filmId, long fileId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(id);
        reference.setFilmId(filmId);
        reference.setImdbId("tt" + filmId);
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(CUES));
        reference.setCueCount(CUES.size());
        reference.setFetchedAt(NOW);
        return reference;
    }

    @Test
    void sidecarsWhoseCuesWereRewrittenAreForgottenByTheProbeAndTheTimingReference() {
        refresher.onCuesRewritten(new SubtitleCuesRewritten(Set.of(5L, 12L)));

        verify(probe).forget(Set.of(5L, 12L));
        verify(timingReference).forget(Set.of(5L, 12L));
    }

    @Test
    void theRetimeChecksStampTheTimeTheyEndSoAdminReadsTheFilesAgain() {
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of());
        assertThat(refresher.lastFinishedAt()).isNull();

        refresher.checkEditions(List.of(10L));

        assertThat(refresher.lastFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void withDownloadsOffNothingIsChecked() {
        when(api.downloadsEnabled()).thenReturn(false);

        assertThat(refresher.checkEditions(List.of(10L))).isFalse();

        assertThat(refresher.lastFinishedAt()).isNull();
        verifyNoInteractions(service, budget, events);
    }

    @Test
    void onlyTheNamedEditionsAreCheckedInTheOrderGiven() {
        DueCheck first = due(1, 10, 5011);
        DueCheck second = due(2, 20, 5022);
        DueCheck other = due(3, 30, 5033);
        OpenSubtitlesReference secondReference = reference(8, 2, 5022);
        OpenSubtitlesReference firstReference = reference(9, 1, 5011);
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of(first, second, other));
        when(fetcher.fetch(1L, "tt1", candidate(5011), 6)).thenReturn(firstReference);
        when(fetcher.fetch(2L, "tt2", candidate(5022), 6)).thenReturn(secondReference);
        when(service.save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class))).thenReturn(true);

        assertThat(refresher.checkEditions(List.of(20L, 10L))).isTrue();

        InOrder order = inOrder(service);
        order.verify(service).save(second, secondReference, AGREES, NOW);
        order.verify(service).save(first, firstReference, AGREES, NOW);
        verify(events).publishEvent(new SidecarChecksFinished());
    }

    @Test
    void aSpentBudgetChecksNothing() {
        when(api.downloadsEnabled()).thenReturn(true);
        when(budget.blockedUntil(NOW)).thenReturn(NOW.plusSeconds(3_600));

        assertThat(refresher.checkEditions(List.of(10L))).isTrue();

        verifyNoInteractions(service, events);
    }

    @Test
    void aFailureIsLoggedNotThrownAndTheRunIsGivenBack() {
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenThrow(new IllegalStateException("db down"));

        assertThat(refresher.checkEditions(List.of(10L))).isTrue();
        assertThat(refresher.checkEditions(List.of(10L))).isTrue();

        verify(service, times(2)).due();
        assertThat(refresher.lastFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRequestDuringTheRetimeChecksIsNotLostAndTheCheckPassGoesRoundOnceMore() {
        when(api.downloadsEnabled()).thenReturn(true);
        List<Boolean> asked = new ArrayList<>();
        when(service.due()).thenAnswer(call -> {
            if (asked.isEmpty()) {
                // The nightly pass asks while the retime's checks are going.
                asked.add(refresher.startDue());
            }
            return List.of();
        });

        refresher.checkEditions(List.of(10L));

        assertThat(asked).containsExactly(false);
        // The booked pass is queued once the retime's checks end.
        assertThat(queued).hasSize(1);
    }

    @Test
    void theRetimeChecksDoNotOverlapAPassThatIsGoing() {
        SidecarCheckRefresher inline = new SidecarCheckRefresher(api, budget, fetcher, service, probe,
            timingReference, CLOCK, Runnable::run, limits, events, filmRates);
        when(api.downloadsEnabled()).thenReturn(true);
        List<Boolean> asked = new ArrayList<>();
        when(service.due()).thenAnswer(call -> {
            if (asked.isEmpty()) {
                // Called from inside the pass: it is left to the pass, which goes round once more.
                asked.add(inline.checkEditions(List.of(10L)));
            }
            return List.of();
        });

        inline.startDue();

        assertThat(asked).containsExactly(true);
        verify(service, times(2)).due();
    }
}
