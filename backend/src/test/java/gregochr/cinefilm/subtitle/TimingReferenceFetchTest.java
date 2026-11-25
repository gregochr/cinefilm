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
import gregochr.cinefilm.subtitle.TimingReferenceService.File;
import gregochr.cinefilm.subtitle.TimingReferenceService.Origin;
import gregochr.cinefilm.subtitle.TimingReferenceService.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Phase 3c-1's fetch and measurement: the candidates tried in order, what makes a reference usable and matching, the
 * cap counted from stored references, the two fetches a run, a stop ending the pass quietly, and one line per forced
 * file and reference per process with nothing stored but the references.
 */
@ExtendWith(MockitoExtension.class)
class TimingReferenceFetchTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 20;
    private static final int CHECK_LIMIT = 6;
    private static final long RUNTIME = 3_000_000;

    @Mock
    private FilmRates filmRates;

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private OpenSubtitlesBudget budget;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private TimingReferenceService service;

    private final Logger fetchLog = (Logger) LoggerFactory.getLogger(TimingReferenceFetch.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final MutableClock clock = new MutableClock(NOW);
    private TimingReferenceFetch fetch;

    /** A clock the test moves, for the day the fetch counts in. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        fetch = new TimingReferenceFetch(new ReferenceFetcher(api, budget, references, CLOCK,
            new FakeDailyLimit(LIMIT)), service, clock, filmRates);
        logged.start();
        fetchLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        fetchLog.detachAppender(logged);
    }

    private List<String> lines(Level level) {
        return logged.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    /** A full file: a cue every 10 s from 0:10, {@code count} of them, so it ends 1 s after its last start. */
    private static List<SubtitleCue> full(int count) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            cues.add(new SubtitleCue(i * 10_000L, i * 10_000L + 1_000, "line " + i));
        }
        return cues;
    }

    /** A forced file of twelve cues, each on one of the full file's, all 2 s late: it fits at -2000 ms. */
    private static File forced(long sidecarId, Origin origin) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int k = 1; k <= 12; k++) {
            cues.add(new SubtitleCue(k * 250_000L + 2_000, k * 250_000L + 4_000, "foreign " + k));
        }
        return new File(sidecarId, origin, cues.size(), SrtParser.format(cues));
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

    private static Target target(long filmId, int kept, Long creditsStartMs, List<File> files,
                                 List<OpenSubtitlesResult> candidates, Map<Long, OpenSubtitlesReference> stored) {
        return new Target(filmId, filmId * 10, "Original", "tt" + filmId, RUNTIME, creditsStartMs, false, files,
            candidates, kept, stored);
    }

    private static Target target(long filmId, List<OpenSubtitlesResult> candidates,
                                 Map<Long, OpenSubtitlesReference> stored) {
        return target(filmId, stored.size(), null, List.of(forced(7, Origin.CHOSEN)), candidates, stored);
    }

    private static OpenSubtitlesDownload downloaded(List<SubtitleCue> cues) {
        return new OpenSubtitlesDownload(new SrtParser.Parsed(cues,
            new SrtParser.Report("UTF-8", 0, 0, 0, false, false, false, cues.size())), 50, null);
    }

    /** What the fetch needs to download a file for a film: no stored row yet, budget left, a download, a save. */
    private void downloading(long filmId, long fileId, List<SubtitleCue> cues,
                             Map<Long, OpenSubtitlesReference> saved) {
        when(references.findByFilmIdAndImdbIdAndOsFileId(filmId, "tt" + filmId, fileId)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(fileId)).thenReturn(downloaded(cues));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            reference.setId(reference.getOsFileId());
            saved.put(reference.getOsFileId(), reference);
            return reference;
        });
    }

    @Test
    void aStoredFullFileThatIsNotACandidateIsMeasuredAfterTheCandidatesAndNothingIsFetchedForIt() {
        OpenSubtitlesReference extra = stored(4000, full(300));
        extra.setRelease("Extra.2008");
        OpenSubtitlesReference tooShort = stored(3000, full(299));
        when(service.targets()).thenReturn(List.of(target(1, List.of(candidate(5012)),
            Map.of(4000L, extra, 3000L, tooShort, 5012L, stored(5012, full(300))))));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(2);
        assertThat(lines(Level.INFO)).hasSize(2);
        assertThat(lines(Level.INFO).get(0)).contains("against OpenSubtitles file 5012 (\"Release 5012\"");
        assertThat(lines(Level.INFO).get(1)).contains("against OpenSubtitles file 4000 (\"Extra.2008\", fps unknown, "
            + "hearing-impaired unknown, 300 lines); ALIGNED at -2000 ms");
        verifyNoInteractions(api, budget);
    }

    @Test
    void aDownloadIsNeverMeasuredAgainstTheOpenSubtitlesFileItCameFrom() {
        File owner = forced(7, Origin.OWNER);
        File download = new File(8L, Origin.DOWNLOAD, owner.lines(), owner.cuesText(), 5012L);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(owner, download),
            List.of(candidate(5012)), Map.of(5012L, stored(5012, full(300))))));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(1);
        assertThat(lines(Level.INFO)).singleElement().asString().contains("OWNER file 7 ");
        verifyNoInteractions(api, budget);
    }

    @Test
    void aFilmWithNoCandidatesAtAllIsMeasuredAgainstItsStoredFullFile() {
        when(service.targets()).thenReturn(List.of(target(1, List.of(), Map.of(4000L, stored(4000, full(300))))));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(1);
        verifyNoInteractions(api, budget);
    }

    @Test
    void aStoredUsableMatchingReferenceIsMeasuredWithNoFetchAndTheLineHoldsEveryFact() {
        OpenSubtitlesResult impaired = new OpenSubtitlesResult(4012, 5012, "Film.2008.HI", 23.976, 10, true, true,
            null, null);
        when(service.targets()).thenReturn(List.of(target(1, List.of(impaired),
            Map.of(5012L, stored(5012, full(300))))));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(1);
        assertThat(lines(Level.INFO)).containsExactly("Forced timing reference of edition 10 (film 1): CHOSEN file 7 "
            + "(12 lines, under 300) against OpenSubtitles file 5012 (\"Film.2008.HI\", fps 23.976, "
            + "hearing-impaired true, "
            + "300 lines); ALIGNED at -2000 ms, 100% of 12 lines matched, median residual 0 ms; reference's last cue "
            + "ends 0:50:01, runtime 0:50:00, credits start unknown");
        verifyNoInteractions(api, budget, filmRates);
    }

    /** A forced download retimed from 23.976: its stored cues are the original twelve, 90 s later. */
    private static File retimedDownload() {
        File plain = forced(8, Origin.DOWNLOAD);
        List<SubtitleCue> onFilm = SrtParser.cues(plain.cuesText()).stream()
            .map(c -> new SubtitleCue(c.startMs() + 90_000, c.endMs() + 90_000, c.text())).toList();
        RateComparison.Side side = new RateComparison.Side(RateComparison.Kind.DOWNLOAD, 23.976, RetimeChoice.AUTO,
            0.959, plain.cuesText());
        return new File(8L, Origin.DOWNLOAD, 12, SrtParser.format(onFilm), 5099L, side);
    }

    @Test
    void aRetimedForcedFileAndAReferenceOfUnknownRateAreNotMeasuredAndTheReasonIsLogged() {
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(retimedDownload()),
            List.of(candidate(5012)), Map.of(5012L, stored(5012, full(300))))));
        when(filmRates.forEdition(10L)).thenReturn(25.0);

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isZero();
        assertThat(lines(Level.INFO)).contains("Forced timing reference of edition 10 (film 1): no verdict, "
            + "UNKNOWN_RATE (sidecar 8, reference 5012)");
    }

    @Test
    void aRetimedForcedFileIsMeasuredAgainstARawReferenceOfTheSameFamilyOnTheirOriginals() {
        OpenSubtitlesReference reference = stored(5012, full(300));
        reference.setTimedFps(23.976);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(retimedDownload()),
            List.of(candidate(5012)), Map.of(5012L, reference))));
        when(filmRates.forEdition(10L)).thenReturn(25.0);

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(1);
        assertThat(lines(Level.INFO)).singleElement().asString().contains("ALIGNED at -2000 ms");
    }

    @Test
    void theLineSaysWhenTheFileHasThreeHundredLinesAndWhereCreditsStart() {
        File big = new File(8L, Origin.DOWNLOAD, 300, SrtParser.format(full(300)));
        when(service.targets()).thenReturn(List.of(target(1, 1, 2_900_000L, List.of(big), List.of(candidate(5012)),
            Map.of(5012L, stored(5012, full(300))))));

        fetch.run(CHECK_LIMIT, true);

        assertThat(lines(Level.INFO)).singleElement().satisfies(line -> assertThat(line)
            .contains("DOWNLOAD file 8 (300 lines, 300 or more)").contains("100% of 300 lines matched")
            .endsWith("credits start 0:48:20"));
    }

    @Test
    void everyFileOfTheEditionIsMeasuredOnceAndNeverAgainWhileTheProcessRuns() {
        when(service.targets()).thenReturn(List.of(target(1, 1, null,
            List.of(forced(7, Origin.OWNER), forced(8, Origin.DOWNLOAD)), List.of(candidate(5012)),
            Map.of(5012L, stored(5012, full(300))))));

        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(2);
        assertThat(fetch.run(CHECK_LIMIT, true)).isZero();

        assertThat(lines(Level.INFO)).hasSize(2);
        assertThat(lines(Level.INFO).get(0)).contains("OWNER file 7");
        assertThat(lines(Level.INFO).get(1)).contains("DOWNLOAD file 8");
    }

    @Test
    void forgettingASidecarMeasuresItAgainAndTwelveNeverTakesOneHundredTwentyThree() {
        when(service.targets()).thenReturn(List.of(target(1, 1, null,
            List.of(forced(12, Origin.OWNER), forced(123, Origin.DOWNLOAD)), List.of(candidate(5012)),
            Map.of(5012L, stored(5012, full(300))))));
        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(2);

        fetch.forget(List.of(12L));

        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(1);
        assertThat(lines(Level.INFO)).hasSize(3);
        assertThat(lines(Level.INFO).get(2)).contains("OWNER file 12");
        assertThat(fetch.run(CHECK_LIMIT, true)).isZero();
    }

    @Test
    void forgettingEverySidecarOfAFilmMeasuresAllOfThemAgain() {
        when(service.targets()).thenReturn(List.of(target(1, 1, null,
            List.of(forced(7, Origin.OWNER), forced(8, Origin.DOWNLOAD)), List.of(candidate(5012)),
            Map.of(5012L, stored(5012, full(300))))));
        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(2);

        fetch.forget(List.of(7L, 8L));

        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(2);
    }

    @Test
    void aReferenceNotStoredIsFetchedWithinTheCheckCeilingStoredAndMeasured() {
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5012, full(300), saved);
        when(service.targets()).thenReturn(List.of(target(1, 0, null, List.of(forced(7, Origin.OWNER)),
            List.of(candidate(5012)), Map.of())));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(1);
        verify(budget).take(DAY, CHECK_LIMIT);
        verify(budget, never()).take(DAY, LIMIT);
        assertThat(saved).containsOnlyKeys(5012L);
        assertThat(saved.get(5012L).getLastCueEndMs()).isEqualTo(3_001_000L);
        assertThat(lines(Level.INFO)).hasSize(2);
        assertThat(lines(Level.INFO).get(0)).isEqualTo("Timing reference for edition 10 (film 1): fetched file 5012 "
            + "(\"Release 5012\", hearing-impaired false), 300 lines, usable true, best match 100% of a forced "
            + "file's lines");
        assertThat(lines(Level.INFO).get(1)).contains("OWNER file 7").contains("against OpenSubtitles file 5012");
    }

    @Test
    void aDownloadsOwnOpenSubtitlesFileDoesNotStopTheWalkAsAMatch() {
        File owner = forced(7, Origin.OWNER);
        File download = new File(8L, Origin.DOWNLOAD, owner.lines(), owner.cuesText(), 5012L);
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5013, full(300), saved);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(download),
            List.of(candidate(5012), candidate(5013)), Map.of(5012L, stored(5012, full(300))))));

        fetch.run(CHECK_LIMIT, true);

        // The download matches file 5012 only because it came from it: the walk goes on to 5013.
        verify(api).download(5013L);
        assertThat(saved).containsOnlyKeys(5013L);
    }

    @Test
    void aReferenceMatchingUnderThirtyPercentOfTheLinesTriesTheNextCandidateAndEveryUsableReferenceIsMeasured() {
        File file = forced(7, Origin.OWNER);
        assertThat(TimingFit.fit(starts(SrtParser.cues(file.cuesText())), starts(scatteredReference()))
            .matchedShare()).isLessThan(0.30);
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5013, full(300), saved);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(file),
            List.of(candidate(5012), candidate(5013)), Map.of(5012L, stored(5012, scatteredReference())))));

        fetch.run(CHECK_LIMIT, true);

        verify(api).download(5013);
        assertThat(lines(Level.INFO).get(0)).contains("fetched file 5013").contains("usable true")
            .contains("best match 100%");
        // Every usable stored reference is measured, the low share one and the one fetched after it, in rank order.
        assertThat(lines(Level.INFO)).hasSize(3);
        assertThat(lines(Level.INFO).get(1)).contains("against OpenSubtitles file 5012");
        assertThat(lines(Level.INFO).get(2)).contains("against OpenSubtitles file 5013")
            .contains("ALIGNED at -2000 ms");
    }

    private static List<SubtitleCue> scatteredReference() {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            long start = i * 10_000L + i * i % 7_919;
            cues.add(new SubtitleCue(start, start + 1_000, "line " + i));
        }
        return cues;
    }

    private static List<Long> starts(List<SubtitleCue> cues) {
        return cues.stream().map(SubtitleCue::startMs).toList();
    }

    @Test
    void theCapOfThreeStoredReferencesIsCountedFromTheStoredReferencesAndStopsAFetch() {
        when(service.targets()).thenReturn(List.of(target(1, 3, null, List.of(forced(7, Origin.OWNER)),
            List.of(candidate(5012), candidate(5013)), Map.of(5012L, stored(5012, full(299))))));

        assertThat(fetch.run(CHECK_LIMIT, true)).isZero();

        verifyNoInteractions(api, budget);
    }

    @Test
    void aTransientlyFailedStoredReferenceIsFetchedAgainWithoutCountingTwiceSoTheNextCandidateStillFits() {
        OpenSubtitlesReference retryable = stored(5012, List.of());
        retryable.setFailedDownloads(1);
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.of(retryable));
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5013)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenReturn(downloaded(full(299)));
        when(api.download(5013)).thenReturn(downloaded(full(300)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference reference = call.getArgument(0);
            saved.put(reference.getOsFileId(), reference);
            return reference;
        });
        // Two references are kept already: the retry of 5012 adds none, so 5013 may still be fetched (the third).
        when(service.targets()).thenReturn(List.of(target(1, 2, null, List.of(forced(7, Origin.OWNER)),
            List.of(candidate(5012), candidate(5013)), Map.of(5012L, retryable))));

        fetch.run(CHECK_LIMIT, true);

        verify(api).download(5012);
        verify(api).download(5013);
        assertThat(saved).containsOnlyKeys(5012L, 5013L);
        assertThat(lines(Level.INFO).get(0)).contains("fetched file 5012").contains("299 lines, usable false");
        assertThat(lines(Level.INFO).get(1)).contains("fetched file 5013").contains("usable true, best match 100%");
    }

    @Test
    void aChargedFailureCountsAsAFetchOfTheDaySoALaterRunMakesOnlyOneMore() {
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 1012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(1012)).thenThrow(new OpenSubtitlesChargedException("timed out", false));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 2012)).thenReturn(Optional.empty());
        when(api.download(2012)).thenReturn(downloaded(full(300)));
        when(service.targets()).thenReturn(needingAFetch(1), needingAFetch(2, 3));

        fetch.run(CHECK_LIMIT, true);
        fetch.run(CHECK_LIMIT, true);

        verify(api).download(1012);
        verify(api).download(2012);
        verify(api, never()).download(3012);
    }

    @Test
    void aFullLengthForcedFileDoesNotStopTheWalkWhileTheShortOneDoesNotMatch() {
        File bigFile = new File(8L, Origin.DOWNLOAD, 300, SrtParser.format(scatteredReference()));
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5013, full(300), saved);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(forced(7, Origin.OWNER), bigFile),
            List.of(candidate(5012), candidate(5013)), Map.of(5012L, stored(5012, scatteredReference())))));

        fetch.run(CHECK_LIMIT, true);

        verify(api).download(5013);
        assertThat(lines(Level.INFO).stream().filter(l -> l.contains("DOWNLOAD file 8 (300 lines, 300 or more)"))
            .count()).isEqualTo(2);
    }

    @Test
    void aRunThatCrossesMidnightBooksItsFetchesOnTheNewDay() {
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 1012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));
        when(api.download(1012)).thenAnswer(call -> {
            clock.now = Instant.parse("2024-04-01T00:00:01Z");
            return downloaded(full(300));
        });
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 2012)).thenReturn(Optional.empty());
        when(api.download(2012)).thenReturn(downloaded(full(300)));
        when(service.targets()).thenReturn(needingAFetch(1, 2), needingAFetch(3));

        fetch.run(CHECK_LIMIT, true);
        fetch.run(CHECK_LIMIT, true);

        verify(api).download(2012);
        verify(api, never()).download(3012);
    }

    @Test
    void aReferenceEndingBeforeTheCreditsLessTenMinutesIsNotMeasuredAndOneEndingAfterItIs() {
        Map<Long, OpenSubtitlesReference> both = Map.of(5012L, stored(5012, full(300)),
            5013L, stored(5013, full(330)));
        // The credits start at 1:00:50: a reference must end after 0:50:50; 5012 ends 0:50:01, 5013 ends 0:55:01.
        when(service.targets()).thenReturn(List.of(new Target(1L, 10L, "Original", "tt1", 3_700_000, 3_650_000L,
            false, List.of(forced(7, Origin.OWNER)), List.of(candidate(5012), candidate(5013)), 2, both)));

        fetch.run(CHECK_LIMIT, true);

        assertThat(lines(Level.INFO)).singleElement().satisfies(line -> assertThat(line)
            .contains("against OpenSubtitles file 5013").contains("credits start 1:00:50"));
        verifyNoInteractions(api, budget);
    }

    /** Ten cues, the first {@code onGrid} on the full file's 10 s grid, the rest each off it by a different second. */
    private static File tenCues(int onGrid) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            long jitter = i <= onGrid ? 0 : (i - onGrid) * 1_000L;
            cues.add(new SubtitleCue(i * 250_000L + jitter, i * 250_000L + jitter + 2_000, "foreign " + i));
        }
        return new File(7L, Origin.OWNER, 10, SrtParser.format(cues));
    }

    /** Twelve cues taken from the irregular reference's own cue starts, all {@code shiftMs} late. */
    private static File shifted(long shiftMs) {
        List<SubtitleCue> reference = scatteredReference();
        List<SubtitleCue> cues = new ArrayList<>();
        for (int k = 1; k <= 12; k++) {
            long start = reference.get(25 * k - 1).startMs() + shiftMs;
            cues.add(new SubtitleCue(start, start + 2_000, "foreign " + k));
        }
        return new File(7L, Origin.OWNER, 12, SrtParser.format(cues));
    }

    @Test
    void threeOfTenLinesMatchingStopsTheWalkAndTwoOfTenFetchesTheNextCandidate() {
        Map<Long, OpenSubtitlesReference> kept = Map.of(5012L, stored(5012, full(300)));
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(tenCues(3)),
            List.of(candidate(5012), candidate(5013)), kept)));

        fetch.run(CHECK_LIMIT, true);

        verifyNoInteractions(api, budget);

        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5013, full(300), saved);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(tenCues(2)),
            List.of(candidate(5012), candidate(5013)), kept)));

        fetch.run(CHECK_LIMIT, true);

        verify(api).download(5013);
    }

    @Test
    void aBestOffsetOfThirtySecondsStaysAndFortySecondsIsAnotherCutSoTheNextCandidateIsFetched() {
        Map<Long, OpenSubtitlesReference> kept = Map.of(5012L, stored(5012, scatteredReference()));
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(shifted(30_000)),
            List.of(candidate(5012), candidate(5013)), kept)));

        fetch.run(CHECK_LIMIT, true);

        verifyNoInteractions(api, budget);

        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5013, full(300), saved);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(shifted(40_000)),
            List.of(candidate(5012), candidate(5013)), kept)));

        fetch.run(CHECK_LIMIT, true);

        verify(api).download(5013);
    }

    @Test
    void twoEditionsOfOneFilmShareTheStoredReferencesSoOneDownloadServesBoth() {
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5012, full(300), saved);
        List<OpenSubtitlesResult> candidates = List.of(candidate(5012), candidate(5013));
        when(service.targets()).thenReturn(List.of(
            new Target(1L, 10L, "Original", "tt1", RUNTIME, null, false, List.of(forced(7, Origin.OWNER)), candidates,
                2, Map.of()),
            new Target(1L, 11L, "Extended", "tt1", RUNTIME, null, false, List.of(forced(8, Origin.OWNER)), candidates,
                2, Map.of())));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(2);
        verify(api).download(5012);
        verify(api, never()).download(5013);
        assertThat(lines(Level.INFO).stream().filter(l -> l.startsWith("Timing reference for")).count()).isEqualTo(1);
        assertThat(lines(Level.INFO).get(2)).contains("edition 11").contains("OWNER file 8")
            .contains("against OpenSubtitles file 5012");
    }

    private List<Target> needingAFetch(long... filmIds) {
        List<Target> targets = new ArrayList<>();
        for (long filmId : filmIds) {
            targets.add(target(filmId, 0, null, List.of(forced(filmId, Origin.OWNER)),
                List.of(candidate(filmId * 1000 + 12)), Map.of()));
        }
        return targets;
    }

    @Test
    void atMostTwoFetchesAreMadeInAUtcDayAcrossRunsAndTheCountStartsAgainTheNextDay() {
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 1012, full(300), saved);
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 2012)).thenReturn(Optional.empty());
        when(api.download(2012)).thenReturn(downloaded(full(300)));
        when(references.findByFilmIdAndImdbIdAndOsFileId(3L, "tt3", 3012)).thenReturn(Optional.empty());
        when(api.download(3012)).thenReturn(downloaded(full(300)));
        when(service.targets()).thenReturn(needingAFetch(1, 2), needingAFetch(3), needingAFetch(3));

        fetch.run(CHECK_LIMIT, true);
        fetch.run(CHECK_LIMIT, true);

        verify(api).download(1012);
        verify(api).download(2012);
        verify(api, never()).download(3012);

        clock.now = NOW.plus(Duration.ofDays(1));
        fetch.run(CHECK_LIMIT, true);

        verify(api).download(3012);
    }

    @Test
    void aFileWhoseCuesChangedIsMeasuredAgainAgainstTheSameReference() {
        Map<Long, OpenSubtitlesReference> kept = Map.of(5012L, stored(5012, full(300)));
        File changed = new File(7L, Origin.OWNER, 12, forced(7, Origin.OWNER).cuesText().replace("foreign 1\n",
            "foreign one\n"));
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(forced(7, Origin.OWNER)),
            List.of(candidate(5012)), kept)), List.of(target(1, 1, null, List.of(changed),
            List.of(candidate(5012)), kept)));

        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(1);
        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(1);

        assertThat(lines(Level.INFO)).hasSize(2);
    }

    @Test
    void aReferenceOfAnUnknownFrameRateIsMeasuredAndTheLineSaysSo() {
        OpenSubtitlesResult unknown = new OpenSubtitlesResult(4012, 5012, "Film.NoFps", null, 10, true, false, null,
            null);
        when(service.targets()).thenReturn(List.of(target(1, List.of(unknown),
            Map.of(5012L, stored(5012, full(300))))));

        fetch.run(CHECK_LIMIT, true);

        assertThat(lines(Level.INFO)).singleElement().satisfies(line -> assertThat(line)
            .contains("(\"Film.NoFps\", fps unknown, hearing-impaired false, 300 lines)"));
    }

    @Test
    void withTwoReferencesKeptOneMoreIsFetchedAndWhenItFailsNoMoreIsTried() {
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5013, full(299), saved);
        when(service.targets()).thenReturn(List.of(target(1, 2, null, List.of(forced(7, Origin.OWNER)),
            List.of(candidate(5013), candidate(5014)), Map.of())));

        assertThat(fetch.run(CHECK_LIMIT, true)).isZero();

        assertThat(saved).containsOnlyKeys(5013L);
        verify(api, never()).download(5014);
        assertThat(lines(Level.INFO)).containsExactly("Timing reference for edition 10 (film 1): fetched file 5013 "
            + "(\"Release 5013\", hearing-impaired false), 299 lines, usable false, best match 0% of a forced "
            + "file's lines");
    }

    @Test
    void atMostTwoReferencesAreFetchedInARunInTheOrderOfTheTargets() {
        Map<Long, OpenSubtitlesReference> saved = new HashMap<>();
        downloading(1, 5012, full(300), saved);
        when(references.findByFilmIdAndImdbIdAndOsFileId(2L, "tt2", 6012)).thenReturn(Optional.empty());
        when(api.download(6012)).thenReturn(downloaded(full(300)));
        when(service.targets()).thenReturn(List.of(
            target(1, 0, null, List.of(forced(7, Origin.OWNER)), List.of(candidate(5012)), Map.of()),
            target(2, 0, null, List.of(forced(8, Origin.OWNER)), List.of(candidate(6012)), Map.of()),
            target(3, 0, null, List.of(forced(9, Origin.OWNER)), List.of(candidate(7012)), Map.of())));

        fetch.run(CHECK_LIMIT, true);

        verify(api).download(5012);
        verify(api).download(6012);
        verify(api, never()).download(7012);
        assertThat(saved).containsOnlyKeys(5012L, 6012L);
    }

    @Test
    void aSpentBudgetEndsTheFetchingQuietlyButTheStoredReferencesOfLaterTargetsAreStillMeasured() {
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(false);
        when(service.targets()).thenReturn(List.of(
            target(1, 0, null, List.of(forced(7, Origin.OWNER)), List.of(candidate(5012)), Map.of()),
            target(2, 0, null, List.of(forced(8, Origin.OWNER)), List.of(candidate(6012)), Map.of()),
            target(3, List.of(candidate(7012)), Map.of(7012L, stored(7012, full(300))))));

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isEqualTo(1);
        assertThat(lines(Level.WARN)).isEmpty();
        assertThat(lines(Level.INFO).get(0)).isEqualTo("Timing reference fetching stopped: today's download budget "
            + "is spent");
        verify(api, never()).download(5012);
        verify(api, never()).download(6012);
        assertThat(lines(Level.INFO).get(1)).contains("(film 3)");
    }

    @Test
    void anOutageEndsTheFetchingWithAWarningAndNothingIsStored() {
        when(references.findByFilmIdAndImdbIdAndOsFileId(1L, "tt1", 5012)).thenReturn(Optional.empty());
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenThrow(new OpenSubtitlesUnavailableException("down"));
        when(service.targets()).thenReturn(List.of(
            target(1, 0, null, List.of(forced(7, Origin.OWNER)), List.of(candidate(5012)), Map.of()),
            target(2, 0, null, List.of(forced(8, Origin.OWNER)), List.of(candidate(6012)), Map.of())));

        assertThat(fetch.run(CHECK_LIMIT, true)).isZero();

        assertThat(lines(Level.WARN)).containsExactly("Timing reference fetching stopped: down");
        verify(api, never()).download(6012);
        verifyNoMoreInteractions(references);
    }

    @Test
    void whenTheRunHasAlreadyStoppedNothingIsFetchedAndStoredReferencesAreStillMeasured() {
        when(service.targets()).thenReturn(List.of(
            target(1, 0, null, List.of(forced(7, Origin.OWNER)), List.of(candidate(5012)), Map.of()),
            target(2, List.of(candidate(6012)), Map.of(6012L, stored(6012, full(300))))));

        assertThat(fetch.run(CHECK_LIMIT, false)).isEqualTo(1);

        verifyNoInteractions(api, budget);
        assertThat(lines(Level.INFO)).singleElement().satisfies(line -> assertThat(line).contains("(film 2)"));
    }

    @Test
    void aFailureOnOneEditionIsLoggedWithoutDetailsAndTheNextIsStillMeasured() {
        OpenSubtitlesReference broken = stored(6012, full(300));
        broken.setCuesText(null);
        when(service.targets()).thenReturn(List.of(
            target(2, List.of(candidate(6012)), Map.of(6012L, broken)),
            target(1, List.of(candidate(5012)), Map.of(5012L, stored(5012, full(300))))));

        assertThat(fetch.run(CHECK_LIMIT, true)).isEqualTo(1);

        assertThat(lines(Level.WARN)).containsExactly(
            "Could not fetch a timing reference for edition 20: NullPointerException",
            "Could not measure the forced subtitles of edition 20: NullPointerException");
        assertThat(lines(Level.INFO)).singleElement().satisfies(line -> assertThat(line).contains("edition 10"));
    }

    @Test
    void aRetimedForcedFileAndAReferenceStated25ThatOnlyAgreesOnTheOriginalsIsARateConflictAndNotMeasured() {
        // An irregular reference: only the originals, 2 s apart, line up; 92 s apart they do not.
        OpenSubtitlesReference reference = stored(5012, scatteredReference());
        reference.setTimedFps(25.0);
        RateComparison.Side side = new RateComparison.Side(RateComparison.Kind.DOWNLOAD, 23.976, RetimeChoice.AUTO,
            0.959, shifted(2_000).cuesText());
        File retimed = new File(8L, Origin.DOWNLOAD, 12, shifted(92_000).cuesText(), 5099L, side);
        when(service.targets()).thenReturn(List.of(target(1, 1, null, List.of(retimed),
            List.of(candidate(5012)), Map.of(5012L, reference))));
        when(filmRates.forEdition(10L)).thenReturn(25.0);

        int written = fetch.run(CHECK_LIMIT, true);

        assertThat(written).isZero();
        assertThat(lines(Level.INFO)).contains("Forced timing reference of edition 10 (film 1): no verdict, "
            + "RATE_CONFLICT (sidecar 8, reference 5012)");
    }
}
