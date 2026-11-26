package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private FilmRates filmRates;

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
            fullFit, filmRates);
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
        verifyNoInteractions(api, budget, filmRates);
    }

    /** A 23.976 download retimed for the film; its original cues are the film's cues 90 s earlier. */
    private static final RateComparison.Side RETIMED = new RateComparison.Side(RateComparison.Kind.DOWNLOAD, 23.976,
        RetimeChoice.AUTO, 0.959, SrtParser.format(lines(300, 0)));
    private static final String NO_VERDICT = "Full subtitle fit of edition 10 (film 1): not measured, no verdict: ";

    private static OpenSubtitlesResult noRate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, null, 10, true, false, null, null);
    }

    private static Job retimedJobOf(RateComparison.Side side, List<OpenSubtitlesResult> others,
                                    OpenSubtitlesReference... references) {
        Map<Long, OpenSubtitlesReference> byFile = new java.util.LinkedHashMap<>();
        for (OpenSubtitlesReference reference : references) {
            byFile.put(reference.getOsFileId(), reference);
        }
        return new Job(1L, 10L, "tt1", 7L, 5011L, lines(300, 90_000), 3 * HOUR + 125_000, null, others, byFile, side);
    }

    /** The retimed download with one stored reference, whose candidate states 23.976. */
    private static Job retimedJob(OpenSubtitlesReference reference) {
        return retimedJobOf(RETIMED, List.of(candidate(reference.getOsFileId())), reference);
    }

    /** The retimed download with several stored references to try in order; their candidates state no rate. */
    private static Job retimedJobWith(OpenSubtitlesReference... references) {
        return retimedJobOf(RETIMED, java.util.Arrays.stream(references).map(r -> noRate(r.getOsFileId())).toList(),
            references);
    }

    private static OpenSubtitlesReference rated(long fileId, Double fps) {
        OpenSubtitlesReference reference = stored(fileId, lines(300, 0));
        reference.setTimedFps(fps);
        return reference;
    }

    @Test
    void aRetimedDownloadIsComparedWithARawReferenceOfTheSameRateOnTheirOriginals() {
        serving(retimedJob(rated(5012, 23.976)));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        // Both directions and the download's last cue are those of the original cues (0:50:01), not the film's.
        assertThat(infoLines()).containsExactly("Full subtitle fit of edition 10 (film 1): download file 5011 "
            + "(300 cues, last cue 0:50:01) against reference file 5012 (300 cues, last cue 0:50:01); "
            + "download to reference ALIGNED at 0 ms, 100% of 300 lines matched; "
            + "reference to download ALIGNED at 0 ms, 100% of 300 lines matched; "
            + "runtime 3:02:05; credits start unknown");
    }

    @Test
    void aRatelessFinalReferenceGetsTheRateItsCandidateStatesAndIsMeasured() {
        OpenSubtitlesReference reference = rated(5012, null);
        serving(retimedJob(reference));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        verify(references).fillTimedFps(5012L, 23.976);
        assertThat(reference.getTimedFps()).isEqualTo(23.976);
        assertThat(infoLines()).singleElement().asString()
            .contains("download to reference ALIGNED at 0 ms, 100% of 300 lines matched");
    }

    @Test
    void aPairWithNoVerdictForWantOfARateIsTriedEachPassWithItsLineOnceAndMeasuredOnceTheRateArrives() {
        OpenSubtitlesReference reference = rated(5012, null);
        serving(retimedJobOf(RETIMED, List.of(noRate(5012)), reference));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        String line = NO_VERDICT + "UNKNOWN_RATE (sidecar 7, reference 5012)";
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        // Still no rate: tried again, and its line is not written a second time. Nothing is filled: no rate stated.
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).containsExactly(line);
        verify(references, never()).fillTimedFps(5012L, 23.976);
        // A forgotten sidecar writes its line again.
        probe.forget(List.of(7L));
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).containsExactly(line, line);
        reference.setTimedFps(23.976);
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(infoLines()).hasSize(3);
        // And once measured it is not measured again.
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
    }

    @Test
    void aFillThatThrowsIsLoggedAndTheNextPassFillsAndMeasures() {
        OpenSubtitlesReference reference = rated(5012, null);
        // The first fill fails, the second works.
        doThrow(new IllegalStateException("boom")).doReturn(1).when(references).fillTimedFps(5012L, 23.976);
        serving(retimedJob(reference));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .contains("Could not fill the stated frame rate of OpenSubtitles file 5012: IllegalStateException");
        assertThat(infoLines()).containsExactly(NO_VERDICT + "UNKNOWN_RATE (sidecar 7, reference 5012)");
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(reference.getTimedFps()).isEqualTo(23.976);
        assertThat(infoLines()).hasSize(2);
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
    void aForgottenSidecarIsMeasuredAgainAndTheOthersAreNot() {
        Map<Long, OpenSubtitlesReference> second = Map.of(5012L, stored(5012, lines(300, 0)));
        serving(job(7L, null, List.of(candidate(5012)), second), job(8L, null, List.of(candidate(5012)), second));
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(2);

        probe.forget(List.of(7L));

        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(infoLines()).hasSize(3);
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
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

    @ParameterizedTest(name = "{0} stored references, every one a rate conflict")
    @ValueSource(ints = {1, 2})
    void everyStoredReferenceBeingARateConflictMarksTheDownloadDoneWithOneLineAndNoFetch(int count) {
        serving(retimedJobWith(java.util.stream.IntStream.range(0, count).mapToObj(i -> rated(5012 + i, 25.0))
            .toArray(OpenSubtitlesReference[]::new)));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        // A conflict is never changed by indexing: the pair is not judged again and its line is not repeated.
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).containsExactly(NO_VERDICT + "RATE_CONFLICT (sidecar 7, reference 5012)");
        verifyNoInteractions(api, budget);
    }

    @ParameterizedTest(name = "a retimed {0} with no original cues")
    @ValueSource(strings = {"download", "reference", "download, no candidates"})
    void aRetimedSideWithNoOriginalCuesIsNeverJudgedAgainAfterOnePass(String side) {
        OpenSubtitlesReference reference = rated(5012, 23.976);
        boolean none = side.endsWith("candidates");
        boolean download = side.startsWith("download");
        RateComparison.Side downloadSide = download ? new RateComparison.Side(RateComparison.Kind.DOWNLOAD, 23.976,
            RetimeChoice.AUTO, 0.959, null) : RETIMED;
        reference.setRetimedBy(download ? null : 0.959);
        // A download with no original is out before any fetch, even with a candidate not stored yet.
        serving(retimedJobOf(downloadSide, none ? List.of() : download
            ? List.of(candidate(5012), candidate(5013)) : List.of(candidate(5012)), reference));
        if (!download) {
            when(filmRates.forEdition(10L)).thenReturn(25.0);
        }
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        // The download's own gap is said without a reference; a reference's is judged once, with the film rate.
        assertThat(infoLines()).containsExactly(none ? "Full subtitle fit of edition 10 (film 1): not measured, "
            + "OpenSubtitles has no second full file to compare with" : download
            ? NO_VERDICT + "UNKNOWN_RATE (sidecar 7)" : NO_VERDICT + "UNKNOWN_RATE (sidecar 7, reference 5012)");
        verifyNoInteractions(api, budget);
        verify(filmRates, download ? never() : times(1)).forEdition(10L);
    }

    @ParameterizedTest(name = "a first reference giving {0} is passed over for the second")
    @CsvSource(value = {"UNKNOWN_RATE, NULL", "RATE_CONFLICT, 25.0"}, nullValues = "NULL")
    void aFirstStoredReferenceWithNoVerdictIsPassedOverAndTheSecondIsMeasuredWithNoFetchAndTheDownloadIsDone(
        String reason, Double firstRate) {
        serving(retimedJobWith(rated(5012, firstRate), rated(5013, 23.976)));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(infoLines()).hasSize(2);
        assertThat(infoLines().get(0)).isEqualTo(NO_VERDICT + reason + " (sidecar 7, reference 5012)");
        assertThat(infoLines().get(1)).contains("against reference file 5013 (300 cues, last cue 0:50:01); "
            + "download to reference ALIGNED at 0 ms");
        // Done: not judged again.
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).hasSize(2);
        verifyNoInteractions(api, budget);
    }

    /** What a fetch of file 5013 needs: no stored row, budget, a download of the original cues, a save. */
    private Map<Long, OpenSubtitlesReference> fetching5013() {
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5013)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5013)).thenReturn(downloaded(lines(300, 0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(reference.getOsFileId());
            saved.put(reference.getOsFileId(), reference);
            return reference;
        });
        return saved;
    }

    @ParameterizedTest(name = "a stored reference giving {0}, then a candidate not stored")
    @CsvSource(value = {"UNKNOWN_RATE, NULL", "RATE_CONFLICT, 25.0"}, nullValues = "NULL")
    void whenNoStoredReferenceGivesAVerdictOneNewReferenceIsFetchedOnceAndMeasured(String reason, Double storedRate) {
        fetching5013();
        serving(retimedJobOf(RETIMED, List.of(noRate(5012), candidate(5013)), rated(5012, storedRate)));
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
        assertThat(infoLines()).hasSize(2);
        assertThat(infoLines().get(0)).isEqualTo(NO_VERDICT + reason + " (sidecar 7, reference 5012)");
        assertThat(infoLines().get(1)).contains("against reference file 5013 (300 cues, last cue 0:50:01)");
        // Done: nothing more is written and nothing more is downloaded.
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).hasSize(2);
        verify(api).download(5013);
    }

    @Test
    void aStoredConflictThenAFetchedReferenceOfUnknownRateIsNotDoneSoTheNextPassesJudgeItAgainWithoutAnotherFetch() {
        Map<Long, OpenSubtitlesReference> saved = fetching5013();
        Job job = retimedJobOf(RETIMED, List.of(noRate(5012), noRate(5013)), rated(5012, 25.0));
        serving(job);
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        // The next job read finds the fetched reference stored.
        job.stored().put(5013L, saved.get(5013L));
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        // Only the first no-verdict line is written, and there was no second fetch.
        assertThat(infoLines()).containsExactly(NO_VERDICT + "RATE_CONFLICT (sidecar 7, reference 5012)");
        verify(api).download(5013);
        // Not done: once the fetched reference's rate is known the pair is measured.
        saved.get(5013L).setTimedFps(23.976);
        assertThat(probe.measure(CHECK_LIMIT)).isEqualTo(1);
    }

    @Test
    void aFetchedReferenceOfUnknownRateGivesNoVerdictAndIsFetchedOnlyOncePerDownload() {
        serving(retimedJobOf(RETIMED, List.of(noRate(5012))));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenReturn(downloaded(lines(300, 0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(reference.getOsFileId());
            return reference;
        });
        when(filmRates.forEdition(10L)).thenReturn(25.0);
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        assertThat(infoLines()).containsExactly(NO_VERDICT + "UNKNOWN_RATE (sidecar 7, reference 5012)");
        // A second pass makes no second fetch: one new reference per download.
        assertThat(probe.measure(CHECK_LIMIT)).isZero();
        verify(api).download(5012);
    }

    @Test
    void aJobGivenNoSideIsADownloadOfNoKnownRateNeverTheOwnersFile() {
        Job job = new Job(1L, 40L, "tt1", 7L, 5011L, List.of(), 0, null, List.of(), Map.of());
        assertThat(job.rateSide()).isEqualTo(RateComparison.Side.download());
    }
}
