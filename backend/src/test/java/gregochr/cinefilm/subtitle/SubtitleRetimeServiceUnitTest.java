package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** The retime service's rules, with the repositories faked. */
@ExtendWith(MockitoExtension.class)
class SubtitleRetimeServiceUnitTest {

    private static final double K = 23.976 / 25;
    private static final long EDITION_ID = 40L;
    private static final String ORIGINAL = SrtParser.format(List.of(new SubtitleCue(100_000, 102_000, "Hello"),
        new SubtitleCue(6_000_000, 6_002_000, "Later")));
    /** The original's two cues times {@link #K}, rounded to the millisecond. */
    private static final String SCALED = SrtParser.format(List.of(new SubtitleCue(95_904, 97_822, "Hello"),
        new SubtitleCue(5_754_240, 5_756_158, "Later")));

    @Mock
    private SubtitleSidecarRepository sidecars;
    @Mock
    private EditionRepository editions;
    @Mock
    private FilmRates rates;
    @Mock
    private ForcedDownloadCheckRepository downloadChecks;
    @Mock
    private ForcedSubtitleAlignmentRepository alignments;
    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private SubtitleRetimeService service;
    private Edition edition;
    private SubtitleSidecar row;

    @BeforeEach
    void setUp() {
        service = new SubtitleRetimeService(sidecars, editions, rates, downloadChecks, alignments, lock, CLOCK,
            transactions);
        edition = TestData.edition(TestData.film(1L, "Taken", 2008, NOW), EDITION_ID, "Original", 100 * MIN, null);
        var part = TestData.file(edition, "Taken.m4v");
        part.setId(10L);
        part.getSubtitleStreams().clear();
        row = new SubtitleSidecar();
        row.setId(5L);
        row.setPath("opensubtitles:" + EDITION_ID + ":777");
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setMediaFileId(10L);
        row.setWholeEdition(true);
        row.setCuesText(ORIGINAL);
        row.setCueCount(2);
        row.setLastEndMs(6_002_000);
        row.setTimedFps(23.976);
        row.setUpdatedAt(NOW.minusSeconds(60));
    }

    private void rowIs() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID))).thenReturn(List.of(edition));
    }

    private void filmRateIs(Double fps) {
        when(rates.forEdition(EDITION_ID)).thenReturn(fps);
    }

    private void english() {
        var stream = new gregochr.cinefilm.film.SubtitleStream();
        stream.setLanguage("English");
        edition.originalParts().get(0).getSubtitleStreams().add(stream);
    }

    private SubtitleSidecar saved() {
        ArgumentCaptor<SubtitleSidecar> captor = ArgumentCaptor.forClass(SubtitleSidecar.class);
        verify(sidecars).save(captor.capture());
        return captor.getValue();
    }

    private ForcedDownloadCheck downloadCheck(String verdict) {
        ForcedDownloadCheck check = new ForcedDownloadCheck();
        check.setSidecarId(5L);
        check.setServedVersion(ServedSubtitles.version(edition, Downloads.pick(row)));
        check.setVerdict(verdict);
        when(downloadChecks.findBySidecarId(5L)).thenReturn(Optional.of(check));
        return check;
    }

    private ForcedSubtitleAlignment alignment(TimingFit.Verdict verdict) {
        ForcedSubtitleAlignment fit = new ForcedSubtitleAlignment();
        fit.setSidecarId(5L);
        fit.setSidecarIdentity(TimingKeys.identity(row));
        fit.setReferenceKey("10:key");
        fit.setVerdict(verdict);
        when(alignments.findBySidecarId(5L)).thenReturn(Optional.of(fit));
        return fit;
    }

    private void retimedAlready() {
        row.setCuesText(SCALED);
        row.setOriginalCuesText(ORIGINAL);
        row.setRetimedBy(K);
        row.setRetimedForFps(25.0);
        row.setRetime(RetimeChoice.AUTO);
    }

    @Test
    void theCandidatesAreTheRepositorysRetimeCandidates() {
        when(sidecars.findRetimeCandidateIds()).thenReturn(List.of(5L, 6L));

        assertThat(service.candidateIds()).containsExactly(5L, 6L);
    }

    @Test
    void withTheSwitchOffNothingIsReadOrWritten() {
        retimedAlready();
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);

        Outcome outcome = service.process(5L, RetimeMode.OFF);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getCuesText()).isEqualTo(SCALED);
        assertThat(row.getOffsetMs()).isEqualTo(900);
        assertThat(lock.holder()).isNull();
        verifyNoInteractions(sidecars, editions, rates, downloadChecks, alignments);
    }

    @Test
    void aDownloadStatedFor23976OnA25FpsFilmIsRewrittenFromTheOriginalByK() {
        rowIs();
        filmRateIs(25.0);
        row.setOffsetMs(1_500);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.REWRITTEN);
        SubtitleSidecar written = saved();
        assertThat(written).isSameAs(row);
        assertThat(SrtParser.cues(written.getCuesText())).containsExactly(new SubtitleCue(95_904, 97_822, "Hello"),
            new SubtitleCue(5_754_240, 5_756_158, "Later"));
        assertThat(written.getOriginalCuesText()).isEqualTo(ORIGINAL);
        assertThat(written.getRetimedBy()).isEqualTo(K);
        assertThat(written.getRetimedForFps()).isEqualTo(25.0);
        assertThat(written.getLastEndMs()).isEqualTo(5_756_158);
        assertThat(written.getOffsetMs()).isZero();
        assertThat(written.getOffsetSource()).isNull();
        assertThat(written.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(written.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void theLockIsTakenBeforeTheRowIsReadAndGivenBackAfterTheWrite() {
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID))).thenReturn(List.of(edition));
        filmRateIs(25.0);
        List<String> heldWhenRead = new ArrayList<>();
        when(sidecars.findById(5L)).thenAnswer(call -> {
            heldWhenRead.add(lock.holder());
            return Optional.of(row);
        });

        service.process(5L, RetimeMode.ON);

        assertThat(heldWhenRead).containsExactly(LibraryLock.SUBTITLE);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aBusyLockLeavesTheRowForTheNextRun() {
        lock.tryAcquire(LibraryLock.SCAN);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.BUSY);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        verifyNoInteractions(sidecars, editions, rates, downloadChecks, alignments);
    }

    @Test
    void aRetimedRowIsNeverScaledTwice() {
        retimedAlready();
        rowIs();
        filmRateIs(25.0);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getCuesText()).isEqualTo(SCALED);
        assertThat(row.getOriginalCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getRetimedBy()).isEqualTo(K);
    }

    @Test
    void aFilmFileReplacedAtAnotherRateRewritesFromTheOriginal() {
        // Stated 25: retimed on a 23.976 film by 25/23.976; the file is replaced by a 24.0 fps one, so k becomes 25/24.
        row.setTimedFps(25.0);
        row.setOriginalCuesText(ORIGINAL);
        row.setCuesText(CueScaler.scale(ORIGINAL, 25 / 23.976));
        row.setRetimedBy(25 / 23.976);
        row.setRetimedForFps(23.976);
        row.setRetime(RetimeChoice.AUTO);
        rowIs();
        filmRateIs(24.0);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.REWRITTEN);
        assertThat(row.getRetimedBy()).isEqualTo(25 / 24.0);
        assertThat(row.getRetimedForFps()).isEqualTo(24.0);
        assertThat(row.getOriginalCuesText()).isEqualTo(ORIGINAL);
        assertThat(SrtParser.cues(row.getCuesText())).containsExactly(new SubtitleCue(104_167, 106_250, "Hello"),
            new SubtitleCue(6_250_000, 6_252_083, "Later"));
        assertThat(row.getLastEndMs()).isEqualTo(6_252_083);
    }

    @Test
    void aFilmFileReplacedAtAMatchingRateRestoresTheRow() {
        retimedAlready();
        rowIs();
        filmRateIs(23.976);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.RESTORED);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getOriginalCuesText()).isNull();
        assertThat(row.getRetimedBy()).isNull();
        assertThat(row.getRetimedForFps()).isNull();
        assertThat(row.getLastEndMs()).isEqualTo(6_002_000);
    }

    @Test
    void aRowDecidedOnButNeverRetimedWhoseRateNowMatchesIsLeftAlone() {
        row.setRetime(RetimeChoice.AUTO);
        rowIs();
        filmRateIs(23.976);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void anUnknownFilmRateLeavesARetimedRowAsItIs() {
        retimedAlready();
        rowIs();
        filmRateIs(null);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getCuesText()).isEqualTo(SCALED);
        assertThat(row.getRetimedBy()).isEqualTo(K);
    }

    @Test
    void anUnknownStatedRateLeavesARetimedRowAsItIs() {
        retimedAlready();
        row.setTimedFps(null);
        rowIs();
        filmRateIs(25.0);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetimedBy()).isEqualTo(K);
    }

    @Test
    void aDownloadWhoseEditionWasDeletedHasNoRateAndIsLeftAlone() {
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID))).thenReturn(List.of());

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void aMissingRowAndAFileBesideTheFilmAreLeftAlone() {
        when(sidecars.findById(5L)).thenReturn(Optional.empty());
        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);

        row.setOrigin(SubtitleSidecar.Origin.FILE);
        when(sidecars.findById(6L)).thenReturn(Optional.of(row));
        assertThat(service.process(6L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void retimeOffOnARetimedRowRestoresItEvenWhenTheFilmRateIsUnknown() {
        retimedAlready();
        row.setRetime(RetimeChoice.OFF);
        rowIs();

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.RESTORED);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.OFF);
    }

    @Test
    void retimeOffOnARowThatWasNeverRetimedWritesNothing() {
        row.setRetime(RetimeChoice.OFF);
        rowIs();

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void aRowThatFitsAsItIsIsRestoredLikeOneSetOff() {
        retimedAlready();
        row.setRetime(RetimeChoice.OFF_FITS);
        rowIs();

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.RESTORED);
    }

    @Test
    void anOwnerOffsetDecidesOffAndTheCuesAreNotTouched() {
        row.setOffsetMs(-800);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        rowIs();
        filmRateIs(25.0);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.DECIDED_OFF);
        SubtitleSidecar written = saved();
        assertThat(written.getRetime()).isEqualTo(RetimeChoice.OFF);
        assertThat(written.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(written.getOffsetMs()).isEqualTo(-800);
        assertThat(written.getRetimedBy()).isNull();
    }

    @Test
    void anOwnerVerificationDecidesOff() {
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        row.setVerifiedParts("10");
        rowIs();
        filmRateIs(25.0);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.DECIDED_OFF);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.OFF);
        assertThat(row.getOwnerVerifiedAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void noOwnerTimingDecidesAuto() {
        rowIs();
        filmRateIs(25.0);

        service.process(5L, RetimeMode.ON);

        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(row.getRetimedBy()).isEqualTo(K);
    }

    @Test
    void aRowWhoseRateNeedsNoRetimeStaysUndecided() {
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        rowIs();
        filmRateIs(23.976);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isNull();
    }

    @Test
    void aFilmWithAnEnglishTrackLeavesTheDecisionOpenAndTheCuesAsTheyAre() {
        english();
        rowIs();
        filmRateIs(25.0);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isNull();
        assertThat(row.getRetimedBy()).isNull();
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void aForcedTrackOrAnotherLanguageIsNotAnEnglishTrack() {
        var forced = new gregochr.cinefilm.film.SubtitleStream();
        forced.setLanguage("English");
        forced.setForced(true);
        var french = new gregochr.cinefilm.film.SubtitleStream();
        french.setLanguage("French");
        var unlabelled = new gregochr.cinefilm.film.SubtitleStream();
        edition.originalParts().get(0).getSubtitleStreams().addAll(List.of(forced, french, unlabelled));
        rowIs();
        filmRateIs(25.0);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.REWRITTEN);
    }

    @Test
    void anOwnerEditWinsOverAnEnglishTrackAndAnAlreadyDecidedRowIsNeverDecidedAgain() {
        english();
        retimedAlready();
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        row.setVerifiedParts("10");
        rowIs();
        filmRateIs(25.0);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        // A later nudge and verify on a retimed row leave it retimed: nothing changed, so nothing is reset.
        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(row.getRetimedBy()).isEqualTo(K);
        assertThat(row.getOffsetMs()).isEqualTo(900);
        assertThat(row.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.OWNER);
        assertThat(row.isVerifiedFor(List.of(10L))).isTrue();
    }

    @Test
    void restoreAfterAVerificationTakesTheVerificationAndTheOwnersOffsetAway() {
        retimedAlready();
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        row.setVerifiedParts("10");
        rowIs();

        Outcome outcome = service.process(5L, RetimeMode.RESTORE);

        assertThat(outcome).isEqualTo(Outcome.RESTORED);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.isVerifiedFor(List.of(10L))).isFalse();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
        assertThat(row.getOffsetMs()).isZero();
        assertThat(row.getOffsetSource()).isNull();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void aRewriteTakesTheOwnersVerificationAndOffsetAwayLikeARestore() {
        // A new k on a row the owner verified and nudged after its last rewrite: those were set on the old cues.
        retimedAlready();
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        row.setVerifiedParts("10");
        row.setTimedFps(24.0);
        rowIs();
        filmRateIs(25.0);

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.REWRITTEN);
        assertThat(row.getRetimedBy()).isEqualTo(24.0 / 25);
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getOffsetMs()).isZero();
        assertThat(row.getOffsetSource()).isNull();
    }

    @Test
    void restoreDecidesNothingAndLeavesARowThatWasNeverRetimedAlone() {
        rowIs();

        assertThat(service.process(5L, RetimeMode.RESTORE)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isNull();
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void restoreOfADownloadWhoseEditionWasDeletedStillPutsTheCuesBackAndDropsItsCheck() {
        retimedAlready();
        when(sidecars.findById(5L)).thenReturn(Optional.of(row));
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID))).thenReturn(List.of());
        ForcedDownloadCheck check = downloadCheck("CHECK");

        assertThat(service.process(5L, RetimeMode.RESTORE)).isEqualTo(Outcome.RESTORED);

        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        verify(downloadChecks).delete(check);
    }

    @Test
    void aFailingCheckIsCarriedOverToTheNewServedVersionAndMarkedOutdated() {
        rowIs();
        filmRateIs(25.0);
        ForcedDownloadCheck check = downloadCheck("CHECK");
        String before = ServedSubtitles.version(edition, Downloads.pick(row));

        service.process(5L, RetimeMode.ON);

        String after = ServedSubtitles.version(edition, Downloads.pick(row));
        assertThat(after).isNotEqualTo(before);
        assertThat(check.getServedVersion()).isEqualTo(after);
        assertThat(check.getStaleSince()).isEqualTo(NOW);
        verify(downloadChecks).save(check);
    }

    @Test
    void anAgreeingCheckIsRemovedSoItProvesNothingAboutTheNewCues() {
        rowIs();
        filmRateIs(25.0);
        ForcedDownloadCheck check = downloadCheck("AGREES");

        service.process(5L, RetimeMode.ON);

        verify(downloadChecks).delete(check);
    }

    @Test
    void aFailingFitIsReKeyedToTheNewIdentityAndMarkedOutdated() {
        rowIs();
        filmRateIs(25.0);
        ForcedSubtitleAlignment fit = alignment(TimingFit.Verdict.CHECK);
        String before = TimingKeys.identity(row);

        service.process(5L, RetimeMode.ON);

        String after = TimingKeys.identity(row);
        assertThat(after).isNotEqualTo(before);
        assertThat(fit.getSidecarIdentity()).isEqualTo(after);
        assertThat(fit.getReferenceKey()).isEqualTo("10:key");
        assertThat(fit.getStaleSince()).isEqualTo(NOW);
        assertThat(fit.isCurrent(after, "10:key")).isTrue();
        verify(alignments).save(fit);
    }

    @Test
    void anAlignedFitIsRemovedSoItProvesNothingAboutTheNewCues() {
        rowIs();
        filmRateIs(25.0);
        ForcedSubtitleAlignment fit = alignment(TimingFit.Verdict.ALIGNED);

        service.process(5L, RetimeMode.ON);

        verify(alignments).delete(fit);
    }

    @Test
    void aRestoredRowsFailingFitIsReKeyedBackToTheOriginalIdentity() {
        String originalIdentity = TimingKeys.identity(row);
        retimedAlready();
        rowIs();
        ForcedSubtitleAlignment fit = alignment(TimingFit.Verdict.TOO_FEW);

        service.process(5L, RetimeMode.RESTORE);

        assertThat(fit.getSidecarIdentity()).isEqualTo(originalIdentity);
        assertThat(fit.getStaleSince()).isEqualTo(NOW);
    }

    @Test
    void whatPlaysAfterARewriteIsTheFilmTimelineCuesWithThePartStartAndOffsetApplied() {
        rowIs();
        filmRateIs(25.0);

        service.process(5L, RetimeMode.ON);
        row.setOffsetMs(1_000);

        ServedSubtitle served = ServedSubtitles.of(edition, Downloads.pick(row));
        assertThat(served.cues()).containsExactly(new SubtitleCue(96_904, 98_822, "Hello"),
            new SubtitleCue(5_755_240, 5_757_158, "Later"));
        assertThat(ServedSubtitles.originalCues(edition, Downloads.pick(row))).containsExactly(
            new SubtitleCue(101_000, 103_000, "Hello"), new SubtitleCue(6_001_000, 6_003_000, "Later"));
    }

    @Test
    void theCommitHappensWhileTheLockIsStillHeld() {
        rowIs();
        filmRateIs(25.0);
        List<String> heldAtCommit = new ArrayList<>();
        doAnswer(call -> {
            heldAtCommit.add(lock.holder());
            return null;
        }).when(transactions).commit(any());

        service.process(5L, RetimeMode.ON);

        assertThat(heldAtCommit).containsExactly(LibraryLock.SUBTITLE);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFailureRollsBackReleasesTheLockAndReachesTheCaller() {
        when(sidecars.findById(5L)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.process(5L, RetimeMode.ON)).isInstanceOf(IllegalStateException.class)
            .hasMessage("db down");

        verify(transactions).rollback(null);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aCheckThatWasAlreadyNotCurrentIsLeftAsItIsWhenTheOwnerHadNudgedTheOffset() {
        retimedAlready();
        row.setTimedFps(24.0);
        rowIs();
        filmRateIs(25.0);
        ForcedDownloadCheck check = downloadCheck("CHECK");
        String madeFor = check.getServedVersion();
        row.setOffsetMs(700);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);

        service.process(5L, RetimeMode.ON);

        assertThat(check.getServedVersion()).isEqualTo(madeFor);
        assertThat(check.getStaleSince()).isNull();
    }

    @Test
    void aFitThatWasAlreadyNotCurrentIsLeftAsItIs() {
        rowIs();
        filmRateIs(25.0);
        ForcedSubtitleAlignment fit = alignment(TimingFit.Verdict.CHECK);
        fit.setSidecarIdentity("two versions old");

        service.process(5L, RetimeMode.ON);

        assertThat(fit.getSidecarIdentity()).isEqualTo("two versions old");
        assertThat(fit.getStaleSince()).isNull();
    }

    @Test
    void theOwnersOffOnARetimedRowRestoresItAtOnceWhateverTheGlobalSwitchSays() {
        retimedAlready();
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        rowIs();
        lock.tryAcquire(LibraryLock.SUBTITLE);

        Outcome outcome = service.applyChoice(row, false);

        assertThat(outcome).isEqualTo(Outcome.RESTORED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.OFF);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getOriginalCuesText()).isNull();
        assertThat(row.getRetimedBy()).isNull();
        assertThat(row.getOffsetMs()).isZero();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        // The caller's lock is the one held: the call neither takes nor gives it back.
        assertThat(lock.holder()).isEqualTo(LibraryLock.SUBTITLE);
    }

    @Test
    void theOwnersOffOnAFitsAsItIsRowChangesNothing() {
        row.setRetime(RetimeChoice.OFF_FITS);

        Outcome outcome = service.applyChoice(row, false);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.OFF_FITS);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void theOwnersOffOnARowThatWasNeverRetimedOnlySetsOff() {
        rowIs();

        Outcome outcome = service.applyChoice(row, false);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.OFF);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void theOwnersOnRetimesAtOnceResetsTheOffsetAndClearsTheVerification() {
        row.setRetime(RetimeChoice.OFF);
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        row.setVerifiedParts("10");
        rowIs();
        filmRateIs(25.0);

        Outcome outcome = service.applyChoice(row, true);

        assertThat(outcome).isEqualTo(Outcome.REWRITTEN);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(row.getCuesText()).isEqualTo(SCALED);
        assertThat(row.getOriginalCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getRetimedBy()).isEqualTo(K);
        assertThat(row.getOffsetMs()).isZero();
        assertThat(row.getOffsetSource()).isNull();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
    }

    @Test
    void theOwnersOnTakesAFitsAsItIsRowBackToAuto() {
        row.setRetime(RetimeChoice.OFF_FITS);
        rowIs();
        filmRateIs(25.0);

        assertThat(service.applyChoice(row, true)).isEqualTo(Outcome.REWRITTEN);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
    }

    @Test
    void theOwnersOnWhenTheRatesMatchSetsAutoAndWritesNothingElse() {
        row.setRetime(RetimeChoice.OFF);
        row.setTimedFps(25.0);
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        rowIs();
        filmRateIs(25.0);

        Outcome outcome = service.applyChoice(row, true);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getOffsetMs()).isEqualTo(900);
        assertThat(row.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.OWNER);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void theOwnersOnWithAnUnknownRateSetsAutoAndLeavesTheCuesAsTheyAre() {
        row.setRetime(RetimeChoice.OFF);
        rowIs();
        filmRateIs(null);

        assertThat(service.applyChoice(row, true)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void aVerifyOrNudgeAfterTheOwnersOnLeavesTheRowRetimedAndTheNextRunChangesNothing() {
        // Turn on, then the next run: the owner turned it on, it was retimed, then verified and nudged it.
        retimedAlready();
        row.setOffsetMs(900);
        row.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        row.setOwnerVerifiedAt(NOW.minusSeconds(5));
        row.setVerifiedParts("10");
        rowIs();
        filmRateIs(25.0);

        Outcome outcome = service.process(5L, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(row.getCuesText()).isEqualTo(SCALED);
        assertThat(row.getOriginalCuesText()).isEqualTo(ORIGINAL);
        assertThat(row.getRetimedBy()).isEqualTo(K);
        assertThat(row.getOffsetMs()).isEqualTo(900);
        assertThat(row.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.OWNER);
        assertThat(row.isVerifiedFor(List.of(10L))).isTrue();
        assertThat(row.getUpdatedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void aJobRunAfterTheOwnersOffFindsTheChoiceAndNeverRetimesTheRowAgain() {
        // The job reads the row again under the lock: the owner's OFF is there to be seen, so it rewrites nothing,
        // even with the rates saying k is not 1.
        row.setRetime(RetimeChoice.OFF);
        rowIs();

        assertThat(service.process(5L, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(row.getRetime()).isEqualTo(RetimeChoice.OFF);
        assertThat(row.getCuesText()).isEqualTo(ORIGINAL);
    }
}
