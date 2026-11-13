package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.FullFitService.Job;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

/**
 * The measurement of full downloads (phase 5d-1): one INFO line per download with both fits, nothing stored, each
 * download once per process, and every fetch within the comparison ceiling through the one fetcher.
 */
@ExtendWith(MockitoExtension.class)
class FullFitProbeTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 20;
    private static final int CHECK_LIMIT = 6;
    private static final long HOUR = 3_600_000L;

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private FullFitService fullFit;

    private final Logger probeLog = (Logger) LoggerFactory.getLogger(FullFitProbe.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private FullFitProbe probe;

    @BeforeEach
    void setUp() {
        probe = new FullFitProbe(new ReferenceFetcher(api, budget, references, CLOCK, new FakeDailyLimit(LIMIT)),
            fullFit);
        logged.start();
        probeLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        probeLog.detachAppender(logged);
    }

    private List<String> infoLines() {
        return logged.list.stream().filter(e -> e.getLevel() == Level.INFO).map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static List<SubtitleCue> lines(int count, long shiftMs) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            long start = i * 10_000L + shiftMs;
            cues.add(new SubtitleCue(start, start + 1_000, "line " + i));
        }
        return cues;
    }

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null);
    }

    private static OpenSubtitlesReference stored(long fileId, List<SubtitleCue> cues) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(fileId);
        reference.setFilmId(1L);
        reference.setImdbId("tt1");
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFetchedAt(NOW);
        return reference;
    }

    private static Job job(Long sidecarId, Long creditsStartMs, List<OpenSubtitlesResult> others,
                           Map<Long, OpenSubtitlesReference> storedByFile) {
        return new Job(1L, 10L, "tt1", sidecarId, 5011L, lines(300, 0), 3 * HOUR + 125_000, creditsStartMs, others,
            storedByFile);
    }

    private static OpenSubtitlesDownload downloaded(List<SubtitleCue> cues) {
        return new OpenSubtitlesDownload(new SrtParser.Parsed(cues,
            new SrtParser.Report("UTF-8", 0, 0, 0, false, false, false, cues.size())), 50, null);
    }

    /** The service as it behaves: a download measured by this process is not handed back again. */
    private void serving(Job... jobs) {
        when(fullFit.due(any())).thenAnswer(call -> {
            Set<Long> measured = call.getArgument(0);
            return List.of(jobs).stream().filter(j -> !measured.contains(j.sidecarId())).toList();
        });
    }

    @Test
    void aStoredSecondFileIsComparedBothWaysAndOneInfoLineIsWrittenWithNoDownload() {
        serving(job(7L, 3 * HOUR + 60_000, List.of(candidate(5012)), Map.of(5012L, stored(5012, lines(300, 2_000)))));

        int written = probe.measure(CHECK_LIMIT);

        assertThat(written).isEqualTo(1);
        assertThat(infoLines()).containsExactly("Full subtitle fit of edition 10 (film 1): download file 5011 "
            + "(300 cues, last cue 0:50:01) against reference file 5012 (300 cues, last cue 0:50:03); "
            + "download to reference ALIGNED at 2000 ms, 100% of 300 lines matched; "
            + "reference to download ALIGNED at -2000 ms, 100% of 300 lines matched; "
            + "runtime 3:02:05; credits start 3:01:00");
        verifyNoInteractions(api, budget);
    }

    @Test
    void anUnknownCreditsStartIsSaidSoAndAFitThatOnlyHoldsOneWayIsLoggedAsSuch() {
        // The reference has every line of the download and as many more: forward everything matches, the other way
        // round only half of the reference's lines do.
        List<SubtitleCue> superset = new ArrayList<>(lines(300, 0));
        superset.addAll(lines(300, 5_000));
        superset.sort(Comparator.comparingLong(SubtitleCue::startMs));
        serving(job(7L, null, List.of(candidate(5012)), Map.of(5012L, stored(5012, superset))));

        probe.measure(CHECK_LIMIT);

        assertThat(infoLines()).singleElement().satisfies(line -> {
            assertThat(line).contains("(600 cues, last cue 0:50:06)");
            assertThat(line).contains("download to reference ALIGNED at 0 ms, 100% of 300 lines matched");
            assertThat(line).contains("50% of 600 lines matched").doesNotContain("reference to download ALIGNED");
            assertThat(line).endsWith("credits start unknown");
        });
    }

    @Test
    void eachDownloadIsMeasuredAndLoggedOnlyOncePerProcess() {
        serving(job(7L, null, List.of(candidate(5012)), Map.of(5012L, stored(5012, lines(300, 0)))));

        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(probe.measure(CHECK_LIMIT)).isZero();

        assertThat(infoLines()).hasSize(1);
    }

    @Test
    void aSecondFileNotStoredIsFetchedWithinTheComparisonCeilingAndNothingElseIsStored() {
        serving(job(7L, null, List.of(candidate(5012)), Map.of()));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenReturn(downloaded(lines(300, 500)));
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(reference.getOsFileId());
            saved.put(reference.getOsFileId(), reference);
            return reference;
        });

        int written = probe.measure(CHECK_LIMIT);

        assertThat(written).isEqualTo(1);
        verify(budget).take(DAY, CHECK_LIMIT);
        verify(budget, never()).take(DAY, LIMIT);
        // The reference the fetcher keeps is the only thing stored; the probe itself saves no fit and no ledger row.
        assertThat(saved).containsOnlyKeys(5012L);
        assertThat(infoLines()).singleElement().satisfies(line -> assertThat(line)
            .contains("reference file 5012 (300 cues").contains("ALIGNED at 500 ms"));
        verify(fullFit).due(Set.of(7L));
        verifyNoMoreInteractions(fullFit);
    }

    @Test
    void aSpentComparisonBudgetStopsThePassWithoutALineAndTheDownloadIsMeasuredOnALaterPass() {
        serving(job(7L, null, List.of(candidate(5012)), Map.of()));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(false, true);
        when(api.download(5012)).thenReturn(downloaded(lines(300, 0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(reference.getOsFileId());
            return reference;
        });

        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).containsExactly("Full subtitle measurement stopped: today's download budget is "
            + "spent");
        verify(api, never()).download(5012);

        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
    }

    @Test
    void aStoredReferenceThatIsNotFullIsPassedOverForTheNextCandidateWhichIsFetched() {
        serving(job(7L, null, List.of(candidate(5012), candidate(5013)),
            Map.of(5012L, stored(5012, lines(299, 0)))));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5013)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5013)).thenReturn(downloaded(lines(300, 0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));

        probe.measure(CHECK_LIMIT);

        verify(api, never()).download(5012);
        assertThat(infoLines()).singleElement().satisfies(line -> assertThat(line).contains("reference file 5013"));
    }

    @Test
    void aFetchedFileThatIsNotFullGivesNoMeasurementAndIsNotTriedAgainThisProcess() {
        serving(job(7L, null, List.of(candidate(5012)), Map.of()));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenReturn(downloaded(lines(299, 0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(probe.measure(CHECK_LIMIT)).isZero();

        assertThat(infoLines()).containsExactly("Full subtitle fit of edition 10 (film 1): not measured, OpenSubtitles "
            + "has no second full file to compare with");
        verify(api).download(5012);
    }

    @Test
    void withNoOtherCandidateTheDownloadIsNotMeasuredAndNothingIsSpent() {
        serving(job(7L, null, List.of(), Map.of()));

        assertThat(probe.measure(CHECK_LIMIT)).isZero();

        assertThat(infoLines()).containsExactly("Full subtitle fit of edition 10 (film 1): not measured, OpenSubtitles "
            + "has no second full file to compare with");
        verifyNoInteractions(api, budget);
    }

    @Test
    void anOutageStopsThePassWithAWarningAndLeavesTheDownloadForALaterPass() {
        serving(job(7L, null, List.of(candidate(5012)), Map.of()));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenThrow(new OpenSubtitlesUnavailableException("down"));

        assertThat(probe.measure(CHECK_LIMIT)).isZero();

        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN).extracting(
            ILoggingEvent::getFormattedMessage).containsExactly("Full subtitle measurement stopped: down");
        verify(fullFit).due(Set.of());
    }

    @Test
    void aFailureOnOneDownloadIsLoggedWithoutDetailsAndTheNextIsStillMeasured() {
        OpenSubtitlesReference broken = stored(6012, lines(300, 0));
        broken.setCuesText(null);
        Job failing = new Job(2L, 20L, "tt2", 8L, 6011L, lines(300, 0), HOUR, null, List.of(candidate(6012)),
            Map.of(6012L, broken));
        Job good = job(7L, null, List.of(candidate(5012)), Map.of(5012L, stored(5012, lines(300, 0))));
        serving(failing, good);

        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);

        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.WARN).extracting(
            ILoggingEvent::getFormattedMessage).containsExactly(
            "Could not measure the full subtitle of edition 20: NullPointerException");
        assertThat(infoLines()).singleElement().satisfies(line -> assertThat(line).contains("edition 10"));
    }

    private static OpenSubtitlesReference failed(long fileId, int failures) {
        OpenSubtitlesReference reference = stored(fileId, List.of());
        reference.setFailedDownloads(failures);
        return reference;
    }

    @Test
    void aTransientlyFailedSecondFileIsRetriedAndTheDownloadIsNotMarkedDoneUntilItIsMeasured() {
        OpenSubtitlesReference retryable = failed(5012, 1);
        serving(job(7L, null, List.of(candidate(5012)), Map.of(5012L, retryable)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.of(retryable));
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenThrow(new OpenSubtitlesChargedException("timed out", false))
            .thenReturn(downloaded(lines(300, 0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).isEmpty();

        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(infoLines()).singleElement().satisfies(line -> assertThat(line).contains("reference file 5012"));
    }

    @Test
    void aPermanentlyFailedSecondFileIsSkippedWithoutAnyFetch() {
        serving(job(7L, null, List.of(candidate(5012)),
            Map.of(5012L, failed(5012, OpenSubtitlesReference.MAX_FAILED_DOWNLOADS))));

        assertThat(probe.measure(CHECK_LIMIT)).isZero();

        assertThat(infoLines()).containsExactly("Full subtitle fit of edition 10 (film 1): not measured, OpenSubtitles "
            + "has no second full file to compare with");
        verifyNoInteractions(api, budget);
    }

    @Test
    void theClockFormatIsHoursMinutesAndSecondsAndNeverNegative() {
        assertThat(FullFitProbe.clock(0)).isEqualTo("0:00:00");
        assertThat(FullFitProbe.clock(-5_000)).isEqualTo("0:00:00");
        assertThat(FullFitProbe.clock(59_999)).isEqualTo("0:00:59");
        assertThat(FullFitProbe.clock(3_600_000)).isEqualTo("1:00:00");
        assertThat(FullFitProbe.clock(36_000_000 + 61_000)).isEqualTo("10:01:01");
    }
}
