package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/** What a fetched reference stores of the search result it was fetched for: its stated frame rate. */
@ExtendWith(MockitoExtension.class)
class ReferenceFetcherTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 20;
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
    }

    private void saves() {
        when(references.saveAndFlush(argThat(saved -> saved.getOsFileId() == 5011L)))
            .thenAnswer(call -> call.getArgument(0));
    }

    /** Nothing stored for the file, and a download that gives one cue. */
    private void downloads() {
        downloads(SRT.getBytes(StandardCharsets.UTF_8));
    }

    private void downloads(byte[] file) {
        saves();
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(SrtParser.parse(file), null, null));
    }

    private OpenSubtitlesReference finalStored(Double fps) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(7L);
        reference.setFilmId(1L);
        reference.setImdbId(IMDB);
        reference.setOsFileId(5011L);
        reference.setCuesText(SRT);
        reference.setCueCount(1);
        reference.setTimedFps(fps);
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L)).thenReturn(Optional.of(reference));
        return reference;
    }

    private OpenSubtitlesReference stored() {
        ArgumentCaptor<OpenSubtitlesReference> saved = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        verify(references).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private static OpenSubtitlesResult stating(Double fps) {
        return new OpenSubtitlesResult(1, 5011, "Taken.2008", fps, 300, true, false, null, null);
    }

    @Test
    void aStoredReferenceWithNoRateTakesTheRateTheCandidateStatesWithATargetedUpdateAndNoDownload() {
        OpenSubtitlesReference reference = finalStored(null);

        OpenSubtitlesReference returned = fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(returned).isSameAs(reference);
        assertThat(returned.getTimedFps()).isEqualTo(25.0);
        verify(references).findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L);
        verify(references).fillTimedFps(7L, 25.0);
        verifyNoMoreInteractions(references);
        verifyNoInteractions(api, budget);
    }

    @Test
    void aStoredReferenceThatAlreadyHasARateKeepsItAndNothingIsWritten() {
        OpenSubtitlesReference reference = finalStored(23.976);

        OpenSubtitlesReference returned = fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(returned).isSameAs(reference);
        assertThat(returned.getTimedFps()).isEqualTo(23.976);
        verify(references).findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L);
        verifyNoMoreInteractions(references);
        verifyNoInteractions(api, budget);
    }

    @Test
    void aStoredReferenceWithNoRateStaysSoAndNothingIsWrittenWhenTheCandidateStatesNone() {
        OpenSubtitlesReference reference = finalStored(null);

        assertThat(fetcher.fetch(1L, IMDB, stating(null), LIMIT)).isSameAs(reference);

        assertThat(reference.getTimedFps()).isNull();
        verify(references).findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L);
        verifyNoMoreInteractions(references);
        verifyNoInteractions(api, budget);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void aFillThatFailsLeavesTheStoredReferenceAsItWasAndTheFetchStillSucceeds(RuntimeException failure) {
        OpenSubtitlesReference reference = finalStored(null);
        doThrow(failure).when(references).fillTimedFps(7L, 25.0);

        OpenSubtitlesReference returned = fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(returned).isSameAs(reference);
        assertThat(returned.getTimedFps()).isNull();
        verify(references).findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L);
        verify(references).fillTimedFps(7L, 25.0);
        verifyNoMoreInteractions(references);
        verifyNoInteractions(api, budget);
    }

    static Stream<RuntimeException> failures() {
        return Stream.of(new DataAccessResourceFailureException("database busy"),
            new CannotCreateTransactionException("no connection"));
    }

    @Test
    void aFetchedReferenceKeepsTheRateTheResultStates() {
        downloads();
        OpenSubtitlesResult result = new OpenSubtitlesResult(1, 5011, "Taken.2008", 23.976, 300, true, false, null,
            null);

        fetcher.fetch(1L, IMDB, result, LIMIT);

        assertThat(stored().getTimedFps()).isEqualTo(23.976);
        assertThat(stored().getCueCount()).isEqualTo(1);
    }

    @Test
    void aFetchedReferenceWhoseResultStatesNoRateHasNone() {
        downloads();
        OpenSubtitlesResult result = new OpenSubtitlesResult(1, 5011, "Taken.2008", null, 300, true, false, null,
            null);

        fetcher.fetch(1L, IMDB, result, LIMIT);

        assertThat(stored().getTimedFps()).isNull();
    }

    @Test
    void anUnusableDownloadIsStoredWithTheStatedRateToo() {
        downloads(new byte[0]);
        OpenSubtitlesResult result = new OpenSubtitlesResult(1, 5011, "Taken.2008", 25.0, 300, true, false, null,
            null);

        fetcher.fetch(1L, IMDB, result, LIMIT);

        OpenSubtitlesReference reference = stored();
        assertThat(reference.isUsable()).isFalse();
        assertThat(reference.getTimedFps()).isEqualTo(25.0);
    }

    @Test
    void aReferenceFetchedAgainKeepsTheRateItHasWhenTheCandidateStatesNone() {
        OpenSubtitlesReference failed = finalStored(23.976);
        failed.setCueCount(0);
        failed.setFailedDownloads(1);
        saves();
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(SRT.getBytes(StandardCharsets.UTF_8)), null, null));

        fetcher.fetch(1L, IMDB, stating(null), LIMIT);

        assertThat(stored().getTimedFps()).isEqualTo(23.976);
    }

    @Test
    void aReferenceFetchedAgainTakesTheRateTheCandidateNowStates() {
        OpenSubtitlesReference failed = finalStored(23.976);
        failed.setCueCount(0);
        failed.setFailedDownloads(1);
        saves();
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(SRT.getBytes(StandardCharsets.UTF_8)), null, null));

        fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(stored().getTimedFps()).isEqualTo(25.0);
    }

    @Test
    void aReferenceFetchedAgainIsNeverMarkedAsAlreadyRetimedAndItsCuesVersionIsRaised() {
        OpenSubtitlesReference failed = finalStored(23.976);
        failed.setCueCount(0);
        failed.setCuesText("");
        failed.setFailedDownloads(1);
        failed.setOriginalCuesText("1\n00:00:01,000 --> 00:00:02,000\nOld\n\n");
        failed.setRetimedBy(23.976 / 25);
        failed.setRetimedForFps(25.0);
        failed.setCuesVersion(4);
        saves();
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(SRT.getBytes(StandardCharsets.UTF_8)), null, null));

        fetcher.fetch(1L, IMDB, stating(23.976), LIMIT);

        OpenSubtitlesReference written = stored();
        assertThat(written.getOriginalCuesText()).isNull();
        assertThat(written.getRetimedBy()).isNull();
        assertThat(written.getRetimedForFps()).isNull();
        assertThat(written.getTimedFps()).isEqualTo(23.976);
        assertThat(written.getCuesText()).isEqualTo(SRT);
        assertThat(written.getCuesVersion()).isEqualTo(5);
    }

    @Test
    void aNewReferenceStartsAtCuesVersionOneWithNothingRetimed() {
        downloads();

        fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        OpenSubtitlesReference written = stored();
        assertThat(written.getCuesVersion()).isEqualTo(1);
        assertThat(written.getRetimedBy()).isNull();
        assertThat(written.getOriginalCuesText()).isNull();
    }

    @Test
    void aReferenceStoredWhileTheArrivalHookRewritesItIsReturnedAsRewritten() {
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, IMDB, 5011L)).thenReturn(Optional.empty());
        when(budget.take(DAY, LIMIT)).thenReturn(true);
        when(api.download(5011L)).thenReturn(new OpenSubtitlesDownload(
            SrtParser.parse(SRT.getBytes(StandardCharsets.UTF_8)), null, null));
        OpenSubtitlesReference rewritten = new OpenSubtitlesReference();
        rewritten.setId(9L);
        org.mockito.Mockito.doAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(9L);
            return reference;
        }).when(references).saveAndFlush(argThat(saved -> saved.getOsFileId() == 5011L));
        when(references.findById(9L)).thenReturn(Optional.of(rewritten));
        long[] asked = new long[1];
        fetcher.onArrival(id -> {
            asked[0] = id;
            return true;
        });

        assertThat(fetcher.fetch(1L, IMDB, stating(25.0), LIMIT)).isSameAs(rewritten);
        assertThat(asked[0]).isEqualTo(9L);
    }

    @Test
    void aReferenceTheArrivalHookLeavesAsItIsIsReturnedAsStored() {
        downloads();
        fetcher.onArrival(id -> false);

        OpenSubtitlesReference fetched = fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(fetched).isSameAs(stored());
    }

    @Test
    void anArrivalHookThatFailsLeavesTheStoredReferenceAndTheFetchStillSucceeds() {
        downloads();
        fetcher.onArrival(id -> {
            throw new IllegalStateException("boom");
        });

        OpenSubtitlesReference fetched = fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(fetched).isSameAs(stored());
    }

    @Test
    void anUnusableDownloadIsNeverGivenToTheArrivalHook() {
        downloads("not a subtitle".getBytes(StandardCharsets.UTF_8));
        boolean[] asked = {false};
        fetcher.onArrival(id -> asked[0] = true);

        fetcher.fetch(1L, IMDB, stating(25.0), LIMIT);

        assertThat(asked[0]).isFalse();
    }
}
