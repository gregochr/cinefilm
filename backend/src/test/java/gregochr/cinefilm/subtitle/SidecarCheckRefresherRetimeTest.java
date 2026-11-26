package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.mockito.Mockito.verify;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** What the check refresher does for the retime job: forgets the sidecars whose cues were rewritten. */
@ExtendWith(MockitoExtension.class)
class SidecarCheckRefresherRetimeTest {

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

    @Test
    void sidecarsWhoseCuesWereRewrittenAreForgottenByTheProbeAndTheTimingReference() {
        SidecarCheckRefresher refresher = new SidecarCheckRefresher(api, budget, fetcher, service, probe,
            timingReference, CLOCK, Runnable::run, new FakeDailyLimit(20), events, filmRates);

        refresher.onCuesRewritten(new SubtitleCuesRewritten(Set.of(5L, 12L)));

        verify(probe).forget(Set.of(5L, 12L));
        verify(timingReference).forget(Set.of(5L, 12L));
    }
}
