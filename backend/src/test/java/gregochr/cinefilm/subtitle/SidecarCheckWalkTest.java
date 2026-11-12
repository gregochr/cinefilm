package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.LoggerFactory;

/**
 * Walking an edition's OpenSubtitles candidates: stored references are compared locally first, the next candidate is
 * tried only when a file was full subtitles or looks like another cut, never more than three files per edition and
 * IMDb id, the best result is kept, and a charged download is never given back.
 */
@ExtendWith(MockitoExtension.class)
class SidecarCheckWalkTest {

    private static final LocalDate DAY = LocalDate.of(2024, 3, 31);
    private static final int LIMIT = 90;
    /** What the checks may bring the day's downloads to: half the limit. */
    private static final int CHECK_LIMIT = 45;
    private static final long FILM = 1;
    private static final long EDITION = 10;
    private static final String IMDB = "tt1";

    /** Ten cues, one every 10 s from 10 s: the served subtitle. */
    private static final List<SubtitleCue> SERVED = cues(10_000, 10);

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private ApplicationEventPublisher events;

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
        refresher = new SidecarCheckRefresher(api, budget, references, service, CLOCK, queued::add, LIMIT, events);
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

    private static List<SubtitleCue> cues(long first, int count) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cues.add(new SubtitleCue(first + 10_000L * i, first + 10_000L * i + 1000, "x"));
        }
        return cues;
    }

    /** Cues that match the served ones: all of them, or only the first {@code matching}. */
    private static List<SubtitleCue> matching(int matching) {
        List<SubtitleCue> cues = new ArrayList<>(SERVED.subList(0, matching));
        cues.addAll(cues(5_000_000, SERVED.size() - matching));
        return cues;
    }

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null);
    }

    private static DueCheck due(Set<Long> tried, long... fileIds) {
        List<OpenSubtitlesResult> candidates = new ArrayList<>();
        for (long fileId : fileIds) {
            candidates.add(candidate(fileId));
        }
        return new DueCheck(FILM, EDITION, IMDB, "Extended Edition", candidates, tried, "v10", false, SERVED);
    }

    private static OpenSubtitlesReference stored(long id, long fileId, List<SubtitleCue> cues) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(id);
        reference.setFilmId(FILM);
        reference.setImdbId(IMDB);
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFailedDownloads(cues.isEmpty() ? OpenSubtitlesReference.MAX_FAILED_DOWNLOADS : 0);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private void storedIs(OpenSubtitlesReference reference) {
        when(references.findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, reference.getOsFileId()))
            .thenReturn(Optional.of(reference));
    }

    private void notStored(long fileId) {
        when(references.findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, fileId)).thenReturn(Optional.empty());
    }

    private static OpenSubtitlesDownload downloaded(List<SubtitleCue> cues) {
        return new OpenSubtitlesDownload(new SrtParser.Parsed(cues,
            new SrtParser.Report("UTF-8", 0, 0, 0, false, false, false, cues.size())), 50, null);
    }

    private void run(DueCheck... due) {
        when(api.downloadsEnabled()).thenReturn(true);
        when(service.due()).thenReturn(List.of(due));
        refresher.startDue();
        queued.get(0).run();
    }

    private void savingEverything() {
        when(service.save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class))).thenReturn(true);
    }

    private Result savedResult(DueCheck due, OpenSubtitlesReference reference) {
        ArgumentCaptor<Result> result = ArgumentCaptor.forClass(Result.class);
        verify(service).save(eq(due), eq(reference), result.capture(), eq(NOW));
        return result.getValue();
    }

    @Test
    void aFullFirstReferenceMovesOnAndTheSecondThatAgreesIsKeptWithNoDownloadWhenBothAreStored() {
        DueCheck due = due(Set.of(5011L, 5012L), 5011, 5012);
        OpenSubtitlesReference full = stored(8, 5011, cues(2_000_000, 300));
        OpenSubtitlesReference second = stored(9, 5012, SERVED);
        storedIs(full);
        storedIs(second);
        savingEverything();

        run(due);

        Result result = savedResult(due, second);
        assertThat(result.verdict()).isEqualTo(Verdict.AGREES);
        verify(budget, never()).take(DAY, CHECK_LIMIT);
        verify(api, never()).download(5012);
        assertThat(messages()).contains(
            "Edition 10: candidate 1 (file 5011) was full subtitles, trying the next",
            "Edition 10: used OpenSubtitles file 5012 (compared 2), AGREES");
    }

    @Test
    void aStoredOtherCutIsComparedLocallyAndOnlyTheNextCandidateThatIsNotStoredIsDownloaded() {
        DueCheck due = due(Set.of(5011L), 5011, 5012);
        OpenSubtitlesReference otherCut = stored(8, 5011, matching(0));
        storedIs(otherCut);
        notStored(5012);
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenReturn(downloaded(SERVED));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference saved = call.getArgument(0);
            saved.setId(77L);
            return saved;
        });
        savingEverything();

        run(due);

        InOrder order = inOrder(references, budget, api, service);
        order.verify(references).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5011);
        order.verify(budget).take(DAY, CHECK_LIMIT);
        order.verify(api).download(5012);
        ArgumentCaptor<OpenSubtitlesReference> kept = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        ArgumentCaptor<Result> result = ArgumentCaptor.forClass(Result.class);
        order.verify(service).save(eq(due), kept.capture(), result.capture(), eq(NOW));
        assertThat(kept.getValue().getOsFileId()).isEqualTo(5012L);
        assertThat(result.getValue().verdict()).isEqualTo(Verdict.AGREES);
        verify(api, never()).download(5011);
        // The first was already on the ledger; the second is put on it now.
        verify(service).markTried(EDITION, IMDB, 5012, NOW);
        verify(service, never()).markTried(EDITION, IMDB, 5011, NOW);
    }

    @Test
    void theWalkStopsAtTheFirstAgreementAndNeverLooksAtTheNextCandidate() {
        DueCheck due = due(Set.of(), 5011, 5012);
        OpenSubtitlesReference first = stored(8, 5011, SERVED);
        storedIs(first);
        savingEverything();

        run(due);

        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5012);
        verify(service).markTried(EDITION, IMDB, 5011, NOW);
        verify(service, never()).markTried(EDITION, IMDB, 5012, NOW);
        assertThat(savedResult(due, first).verdict()).isEqualTo(Verdict.AGREES);
    }

    @Test
    void aGenuineDisagreementStopsTheWalkAtOnceAndIsStoredAsItIs() {
        // Served 10 lines against a reference of 41 with every served line in time: incomplete, not another cut.
        DueCheck due = due(Set.of(), 5011, 5012);
        List<SubtitleCue> all = cues(10_000, 41);
        OpenSubtitlesReference first = stored(8, 5011, all);
        storedIs(first);
        savingEverything();

        run(due);

        Result result = savedResult(due, first);
        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).containsExactly("OpenSubtitles has 41 lines, this file has 10");
        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5012);
    }

    @Test
    void aServedFileThatIsItselfFullSubtitlesIsNotWalkedPastItsFirstShortReference() {
        // Amsterdam in production: the owner's "forced" file has full subtitles' length.
        List<SubtitleCue> longServed = cues(10_000, 400);
        DueCheck due = new DueCheck(FILM, EDITION, IMDB, "Original", List.of(candidate(5011), candidate(5012)),
            Set.of(), "v10", false, longServed);
        OpenSubtitlesReference first = stored(8, 5011, cues(10_000, 43));
        storedIs(first);
        savingEverything();

        run(due);

        Result result = savedResult(due, first);
        assertThat(result.reasons()).contains("This file has 400 lines, OpenSubtitles has 43: probably full "
            + "subtitles, not forced");
        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5012);
    }

    @Test
    void whenNothingAgreesTheMostPlausibleReferenceIsKeptWithWhatWasTried() {
        DueCheck due = due(Set.of(5011L, 5012L, 5013L), 5011, 5012, 5013);
        OpenSubtitlesReference none = stored(8, 5011, matching(0));
        OpenSubtitlesReference some = stored(9, 5012, matching(2));
        OpenSubtitlesReference full = stored(10, 5013, cues(2_000_000, 300));
        storedIs(none);
        storedIs(some);
        storedIs(full);
        savingEverything();

        run(due);

        Result result = savedResult(due, some);
        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).startsWith("Tried 3 OpenSubtitles files; they look like a different cut");
        assertThat(result.reasons().get(1)).startsWith("Only 20% of this file's lines");
        assertThat(messages()).contains("Edition 10: used OpenSubtitles file 5012 (compared 3), CHECK");
    }

    @Test
    void onlyFullReferencesThatDoNotLineUpAreStoredAsTheFullReferenceWithTheMostLinesInTime() {
        DueCheck due = due(Set.of(5011L, 5012L), 5011, 5012);
        // Both are full; the second has 2 of the 10 served lines in time (20%), the first none.
        OpenSubtitlesReference first = stored(8, 5011, cues(2_000_000, 300));
        List<SubtitleCue> secondCues = new ArrayList<>(cues(2_000_000, 300));
        secondCues.addAll(SERVED.subList(0, 2));
        OpenSubtitlesReference second = stored(9, 5012, secondCues);
        storedIs(first);
        storedIs(second);
        savingEverything();

        run(due);

        Result result = savedResult(due, second);
        assertThat(result.fullReference()).isTrue();
        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.reasons()).containsExactly("Tried 2 OpenSubtitles files; they look like a different cut",
            "OpenSubtitles only had full subtitles, and this file's lines don't line up with them");
    }

    @Test
    void anEditionThatHasAlreadyTriedThreeFilesNeverTriesAFourthDownloadedOrStored() {
        DueCheck due = due(Set.of(5011L, 5012L, 5013L), 5011, 5012, 5013, 5014);
        storedIs(stored(8, 5011, matching(0)));
        storedIs(stored(9, 5012, matching(1)));
        storedIs(stored(10, 5013, matching(0)));
        savingEverything();

        run(due);

        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5014);
        verify(budget, never()).take(DAY, CHECK_LIMIT);
        verify(api, never()).download(5014);
        verify(service, never()).markTried(EDITION, IMDB, 5014, NOW);
        assertThat(messages()).contains(
            "Edition 10: candidate 4 (file 5014) skipped, 3 OpenSubtitles files were already tried");
    }

    @Test
    void anUntriedCandidateRankedAheadIsSkippedWhenTheLedgerIsFullAndTheTriedOnesBelowItAreStillReused() {
        // The edition was renamed, so a file it never tried now ranks first.
        DueCheck due = due(Set.of(5011L, 5012L, 5013L), 5014, 5011, 5012, 5013);
        storedIs(stored(8, 5011, matching(0)));
        OpenSubtitlesReference best = stored(9, 5012, matching(2));
        storedIs(best);
        storedIs(stored(10, 5013, matching(0)));
        savingEverything();

        run(due);

        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5014);
        verify(api, never()).download(5014);
        verify(service, never()).markTried(EDITION, IMDB, 5014, NOW);
        assertThat(savedResult(due, best).reasons().get(0)).startsWith("Tried 3 OpenSubtitles files;");
        assertThat(messages()).contains(
            "Edition 10: candidate 1 (file 5014) skipped, 3 OpenSubtitles files were already tried");
    }

    @Test
    void anEditionWhoseLedgerIsFullAndWhoseCandidatesAreAllNewDoesNothingAtAll() {
        DueCheck due = due(Set.of(6001L, 6002L, 6003L), 5011, 5012);

        run(due);

        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5011);
        verify(api, never()).download(5011);
        verify(service, never()).save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class));
        assertThat(messages()).contains(
            "Edition 10: candidate 1 (file 5011) skipped, 3 OpenSubtitles files were already tried",
            "Subtitle file checks finished: 0 saved, 0 changed meanwhile, 1 unusable references, 0 failed");
    }

    @Test
    void theCapIsCountedAcrossRunsSoTwoTriedPlusOneNewMakesThreeAndTheNextRunStopsThere() {
        // First run: two on the ledger, the third candidate is new and is allowed (and recorded).
        DueCheck firstRun = due(Set.of(5011L, 5012L), 5011, 5012, 5013, 5014);
        storedIs(stored(8, 5011, matching(0)));
        storedIs(stored(9, 5012, matching(0)));
        notStored(5013);
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5013)).thenReturn(downloaded(matching(0)));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> {
            OpenSubtitlesReference saved = call.getArgument(0);
            saved.setId(11L);
            return saved;
        });
        savingEverything();

        run(firstRun);

        verify(service).markTried(EDITION, IMDB, 5013, NOW);
        verify(references, never()).findByFilmIdAndImdbIdAndOsFileId(FILM, IMDB, 5014);
        verify(api, never()).download(5014);
    }

    @Test
    void anUnreadableCandidateIsSkippedAndTheNextOneIsUsed() {
        DueCheck due = due(Set.of(5011L, 5012L), 5011, 5012);
        storedIs(stored(8, 5011, List.of()));
        OpenSubtitlesReference second = stored(9, 5012, SERVED);
        storedIs(second);
        savingEverything();

        run(due);

        assertThat(savedResult(due, second).verdict()).isEqualTo(Verdict.AGREES);
        assertThat(messages()).contains("Edition 10: candidate 1 (file 5011) skipped, it could not be read");
    }

    @Test
    void whenEveryCandidateIsUnreadableNothingIsSavedAndItIsCountedAsUnusable() {
        DueCheck due = due(Set.of(5011L, 5012L), 5011, 5012);
        storedIs(stored(8, 5011, List.of()));
        storedIs(stored(9, 5012, List.of()));

        run(due);

        verify(service, never()).save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class));
        assertThat(messages()).contains(
            "Subtitle file checks finished: 0 saved, 0 changed meanwhile, 1 unusable references, 0 failed");
    }

    @Test
    void aChargedFailureOnALaterCandidateIsNeverRefundedIsPutOnTheLedgerStopsTheRunAndSavesNothing() {
        DueCheck due = due(Set.of(5011L), 5011, 5012);
        storedIs(stored(8, 5011, matching(0)));
        notStored(5012);
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(true);
        when(api.download(5012)).thenThrow(new OpenSubtitlesChargedException("OpenSubtitles file refused", false));
        when(references.saveAndFlush(any(OpenSubtitlesReference.class))).thenAnswer(call -> call.getArgument(0));

        run(due);

        verify(budget, never()).refund(DAY);
        verify(service).markTried(EDITION, IMDB, 5012, NOW);
        verify(service, never()).save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class));
        assertThat(messages()).contains("Subtitle file checks stopped: OpenSubtitles file refused");
    }

    @Test
    void aSpentBudgetOnALaterCandidateStopsTheRunWithNothingSavedForTheEdition() {
        DueCheck due = due(Set.of(5011L), 5011, 5012);
        storedIs(stored(8, 5011, matching(0)));
        notStored(5012);
        when(budget.take(DAY, CHECK_LIMIT)).thenReturn(false);

        run(due);

        verify(api, never()).download(5012);
        verify(service, never()).markTried(EDITION, IMDB, 5012, NOW);
        verify(service, never()).save(any(DueCheck.class), any(OpenSubtitlesReference.class), any(Result.class),
            any(Instant.class));
        assertThat(messages()).contains("Subtitle file checks stopped: today's download budget is spent");
    }

    @Test
    void aNewCandidateAlreadyStoredForAnotherEditionIsReusedWithNoDownloadButStillPutOnTheLedger() {
        DueCheck due = due(Set.of(), 5011);
        OpenSubtitlesReference shared = stored(8, 5011, SERVED);
        storedIs(shared);
        savingEverything();

        run(due);

        verify(api, never()).download(5011);
        verify(service, times(1)).markTried(EDITION, IMDB, 5011, NOW);
        assertThat(savedResult(due, shared).verdict()).isEqualTo(Verdict.AGREES);
    }
}
