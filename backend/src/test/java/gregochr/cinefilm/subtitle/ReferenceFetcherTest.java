package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** What a fetched reference stores of the search result it was fetched for: its stated frame rate. */
@ExtendWith(MockitoExtension.class)
class ReferenceFetcherTest {

    private static final String IMDB = "tt0936501";
    private static final String SRT = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private DailyDownloadLimit limits;

    private ReferenceFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new ReferenceFetcher(api, budget, references, CLOCK, limits);
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L)).thenReturn(Optional.empty());
        when(budget.take(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(true);
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(SRT.getBytes(StandardCharsets.UTF_8)), null, null));
        when(references.saveAndFlush(org.mockito.ArgumentMatchers.any(OpenSubtitlesReference.class)))
            .thenAnswer(call -> call.getArgument(0));
    }

    private OpenSubtitlesReference stored() {
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        verify(references).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void aFetchedReferenceKeepsTheRateTheResultStates() {
        OpenSubtitlesResult result = new OpenSubtitlesResult(1, 5011, "Taken.2008", 23.976, 300, true, false, null,
            null);

        fetcher.fetch(1L, IMDB, result, 20);

        assertThat(stored().getTimedFps()).isEqualTo(23.976);
        assertThat(stored().getCueCount()).isEqualTo(1);
    }

    @Test
    void aFetchedReferenceWhoseResultStatesNoRateHasNone() {
        OpenSubtitlesResult result = new OpenSubtitlesResult(1, 5011, "Taken.2008", null, 300, true, false, null,
            null);

        fetcher.fetch(1L, IMDB, result, 20);

        assertThat(stored().getTimedFps()).isNull();
    }

    @Test
    void anUnusableDownloadIsStoredWithTheStatedRateToo() {
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(new byte[0]), null, null));
        OpenSubtitlesResult result = new OpenSubtitlesResult(1, 5011, "Taken.2008", 25.0, 300, true, false, null,
            null);

        fetcher.fetch(1L, IMDB, result, 20);

        OpenSubtitlesReference reference = stored();
        assertThat(reference.isUsable()).isFalse();
        assertThat(reference.getTimedFps()).isEqualTo(25.0);
    }
}
