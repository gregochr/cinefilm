package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.FilmRates.FilmRate;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
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

/** The reference retime rules, with the repositories faked. */
@ExtendWith(MockitoExtension.class)
class ReferenceRetimeServiceTest {

    private static final double K = 23.976 / 25;
    private static final long REFERENCE_ID = 7L;
    private static final long FILM_ID = 1L;
    private static final long OS_FILE_ID = 5011L;
    private static final String ORIGINAL = SrtParser.format(List.of(new SubtitleCue(100_000, 102_000, "Hello"),
        new SubtitleCue(6_000_000, 6_002_000, "Later")));
    /** The original's two cues times {@link #K}, rounded to the millisecond. */
    private static final String SCALED = SrtParser.format(List.of(new SubtitleCue(95_904, 97_822, "Hello"),
        new SubtitleCue(5_754_240, 5_756_158, "Later")));

    @Mock
    private OpenSubtitlesReferenceRepository references;
    @Mock
    private EditionRepository editions;
    @Mock
    private SubtitleSidecarRepository sidecars;
    @Mock
    private FilmRates rates;
    @Mock
    private ForcedSubtitleCheckRepository checks;
    @Mock
    private ForcedDownloadCheckRepository downloadChecks;
    @Mock
    private ForcedSubtitleAlignmentRepository alignments;
    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private ReferenceRetimeService service;
    private OpenSubtitlesReference reference;

    @BeforeEach
    void setUp() {
        service = new ReferenceRetimeService(references, editions, sidecars, rates, checks, downloadChecks,
            alignments, lock, CLOCK, transactions);
        reference = new OpenSubtitlesReference();
        reference.setId(REFERENCE_ID);
        reference.setFilmId(FILM_ID);
        reference.setImdbId("tt1");
        reference.setOsFileId(OS_FILE_ID);
        reference.setCuesText(ORIGINAL);
        reference.setCueCount(2);
        reference.setLastCueEndMs(6_002_000L);
        reference.setTimedFps(23.976);
        reference.setCuesVersion(3);
    }

    private void referenceIs() {
        when(references.findById(REFERENCE_ID)).thenReturn(Optional.of(reference));
    }

    private void filmRateIs(Double rate, boolean disagree) {
        when(rates.forFilm(FILM_ID)).thenReturn(new FilmRate(rate, disagree));
    }

    private void retimedAlready() {
        reference.setCuesText(SCALED);
        reference.setOriginalCuesText(ORIGINAL);
        reference.setRetimedBy(K);
        reference.setRetimedForFps(25.0);
        reference.setLastCueEndMs(5_756_158L);
        reference.setCuesVersion(4);
    }

    /**
     * The film has two editions: 40 with two parts (10, 11) and 41 with one (12), each with a forced sidecar (5 on
     * part 10, 6 on part 12). Reading only the first edition, or only each edition's first file, misses rows.
     */
    private void filmHasTwoEditionsWithSidecars() {
        Edition first = TestData.edition(TestData.film(FILM_ID, "Taken", 2008, NOW), 40L, "Original", 0, null);
        TestData.file(first, "Taken.part1.m4v").setId(10L);
        MediaFile second = TestData.file(first, "Taken.part2.m4v");
        second.setId(11L);
        second.setPartNumber(2);
        Edition extended = TestData.edition(TestData.film(FILM_ID, "Taken", 2008, NOW), 41L, "Extended", 0, null);
        TestData.file(extended, "Taken.extended.m4v").setId(12L);
        when(editions.findWithFilesByFilmIdIn(Set.of(FILM_ID))).thenReturn(List.of(first, extended));
        SubtitleSidecar onFirst = new SubtitleSidecar();
        onFirst.setId(5L);
        SubtitleSidecar onExtended = new SubtitleSidecar();
        onExtended.setId(6L);
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L, 11L, 12L), SubtitleKind.FORCED))
            .thenReturn(List.of(onFirst, onExtended));
    }

    /** One check per edition, each naming ANOTHER reference of the film. */
    private List<ForcedSubtitleCheck> fileChecks() {
        List<ForcedSubtitleCheck> rows = List.of(new ForcedSubtitleCheck(), new ForcedSubtitleCheck());
        rows.forEach(check -> check.setReferenceId(REFERENCE_ID + 1));
        when(checks.findByEditionIdIn(List.of(40L, 41L))).thenReturn(rows);
        return rows;
    }

    private List<ForcedDownloadCheck> downloadChecks() {
        List<ForcedDownloadCheck> rows = List.of(new ForcedDownloadCheck(), new ForcedDownloadCheck());
        rows.forEach(check -> check.setReferenceId(REFERENCE_ID + 1));
        when(downloadChecks.findBySidecarIdIn(List.of(5L, 6L))).thenReturn(rows);
        return rows;
    }

    /** Fits saved with no file at all (a rate conflict): they name no reference. */
    private List<ForcedSubtitleAlignment> fits() {
        List<ForcedSubtitleAlignment> rows = List.of(new ForcedSubtitleAlignment(), new ForcedSubtitleAlignment());
        rows.forEach(fit -> {
            fit.setReferenceSource(ReferenceSource.EMBEDDED);
            fit.setVerdict(TimingFit.Verdict.ALIGNED);
        });
        when(alignments.findBySidecarIdIn(List.of(5L, 6L))).thenReturn(rows);
        return rows;
    }

    private OpenSubtitlesReference saved() {
        ArgumentCaptor<OpenSubtitlesReference> captor = ArgumentCaptor.forClass(OpenSubtitlesReference.class);
        verify(references).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void theCandidatesAreTheRepositorysRetimeCandidates() {
        when(references.findRetimeCandidateIds()).thenReturn(List.of(7L, 8L));

        assertThat(service.candidateIds()).containsExactly(7L, 8L);
    }

    @Test
    void withTheSwitchOffNothingIsReadOrWritten() {
        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.OFF);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(lock.holder()).isNull();
        verifyNoInteractions(references, editions, rates, checks, downloadChecks, alignments);
    }

    @Test
    void aBusyLockLeavesTheReferenceForTheNextRun() {
        lock.tryAcquire(LibraryLock.SCAN);

        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.BUSY);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        verifyNoInteractions(references, editions, rates, checks, downloadChecks, alignments);
    }

    @Test
    void theLockIsHeldWhileTheRowIsReadAndGivenBackAfterwards() {
        List<String> heldWhenRead = new java.util.ArrayList<>();
        when(references.findById(REFERENCE_ID)).thenAnswer(call -> {
            heldWhenRead.add(lock.holder());
            return Optional.empty();
        });

        service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(heldWhenRead).containsExactly(LibraryLock.SUBTITLE);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aReferenceStatedFor23976OnAFilmAgreedAt25FpsIsRewrittenFromTheOriginalByK() {
        referenceIs();
        filmRateIs(25.0, false);

        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.REWRITTEN);
        OpenSubtitlesReference written = saved();
        assertThat(written).isSameAs(reference);
        assertThat(written.getCuesText()).isEqualTo(SCALED);
        assertThat(written.getOriginalCuesText()).isEqualTo(ORIGINAL);
        assertThat(written.getRetimedBy()).isEqualTo(K);
        assertThat(written.getRetimedForFps()).isEqualTo(25.0);
        assertThat(written.getLastCueEndMs()).isEqualTo(5_756_158L);
        assertThat(written.getCuesVersion()).isEqualTo(4);
    }

    @Test
    void everyRowOfTheFilmsEditionsIsMarkedOutdatedWhateverReferenceItNamesAndKeepsItsVerdict() {
        referenceIs();
        filmHasTwoEditionsWithSidecars();
        filmRateIs(25.0, false);
        List<ForcedSubtitleCheck> fileChecks = fileChecks();
        fileChecks.forEach(check -> check.setVerdict("AGREES"));
        List<ForcedDownloadCheck> downloadChecks = downloadChecks();
        downloadChecks.forEach(check -> check.setVerdict("CHECK"));
        List<ForcedSubtitleAlignment> fits = fits();

        service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(fileChecks).extracting(ForcedSubtitleCheck::getStaleSince).containsExactly(NOW, NOW);
        assertThat(fileChecks).extracting(ForcedSubtitleCheck::getVerdict).containsExactly("AGREES", "AGREES");
        assertThat(downloadChecks).extracting(ForcedDownloadCheck::getStaleSince).containsExactly(NOW, NOW);
        assertThat(downloadChecks).extracting(ForcedDownloadCheck::getVerdict).containsExactly("CHECK", "CHECK");
        assertThat(fits).extracting(ForcedSubtitleAlignment::getStaleSince).containsExactly(NOW, NOW);
        assertThat(fits).extracting(ForcedSubtitleAlignment::getVerdict)
            .containsExactly(TimingFit.Verdict.ALIGNED, TimingFit.Verdict.ALIGNED);
        verify(this.checks).saveAll(fileChecks);
        verify(this.downloadChecks).saveAll(downloadChecks);
        verify(alignments).saveAll(fits);
    }

    @Test
    void onlyThisFilmsEditionsAndSidecarsAreLookedUpSoRowsOfAnotherFilmAreNeverMarked() {
        referenceIs();
        filmHasTwoEditionsWithSidecars();
        filmRateIs(25.0, false);

        service.process(REFERENCE_ID, RetimeMode.ON);

        verify(editions).findWithFilesByFilmIdIn(Set.of(FILM_ID));
        verify(sidecars).findByMediaFileIdInAndKind(List.of(10L, 11L, 12L), SubtitleKind.FORCED);
        verify(checks).findByEditionIdIn(List.of(40L, 41L));
        verify(downloadChecks).findBySidecarIdIn(List.of(5L, 6L));
        verify(alignments).findBySidecarIdIn(List.of(5L, 6L));
    }

    @Test
    void aFilmWhoseEditionsDisagreeKeepsItsReferenceRaw() {
        referenceIs();
        filmRateIs(null, true);

        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(reference.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(reference.getRetimedBy()).isNull();
        assertThat(reference.getCuesVersion()).isEqualTo(3);
        verifyNoInteractions(checks, downloadChecks, alignments);
        verifyNoMoreInteractions(references);
    }

    @Test
    void aRetimedReferenceWhoseFilmsEditionsNowDisagreeIsPutBackRaw() {
        referenceIs();
        retimedAlready();
        filmRateIs(null, true);

        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.RESTORED);
        OpenSubtitlesReference written = saved();
        assertThat(written.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(written.getOriginalCuesText()).isNull();
        assertThat(written.getRetimedBy()).isNull();
        assertThat(written.getRetimedForFps()).isNull();
        assertThat(written.getLastCueEndMs()).isEqualTo(6_002_000L);
        assertThat(written.getCuesVersion()).isEqualTo(5);
    }

    @Test
    void aFilmRateNotKnownForNowLeavesARetimedReferenceAsItIs() {
        referenceIs();
        retimedAlready();
        filmRateIs(null, false);

        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.ON);

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(reference.getCuesText()).isEqualTo(SCALED);
        verifyNoMoreInteractions(references);
        verifyNoInteractions(checks, downloadChecks, alignments);
    }

    @Test
    void aReferenceWithNoStatedRateIsLeftAlone() {
        referenceIs();
        reference.setTimedFps(null);
        filmRateIs(25.0, false);

        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);

        assertThat(reference.getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void aReferenceThatNeedsNoChangeIsNotWritten() {
        referenceIs();
        filmRateIs(23.976, false);

        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);

        verifyNoMoreInteractions(references);
        verifyNoInteractions(checks, downloadChecks, alignments);
    }

    @Test
    void aRetimedReferenceWhoseNewKIsOneIsRestored() {
        referenceIs();
        retimedAlready();
        filmRateIs(23.976, false);

        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.RESTORED);

        assertThat(saved().getCuesText()).isEqualTo(ORIGINAL);
    }

    @Test
    void aReferenceAlreadyRetimedByTheSameKIsNeverScaledTwice() {
        referenceIs();
        retimedAlready();
        filmRateIs(25.0, false);

        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);

        assertThat(reference.getCuesText()).isEqualTo(SCALED);
        verifyNoMoreInteractions(references);
    }

    @Test
    void aReferenceRetimedByAnotherKIsRewrittenFromItsOriginalNotFromTheRewrite() {
        referenceIs();
        retimedAlready();
        reference.setRetimedBy(1.0 / K);
        reference.setCuesText("garbage");
        filmRateIs(25.0, false);

        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.REWRITTEN);

        assertThat(saved().getCuesText()).isEqualTo(SCALED);
        assertThat(reference.getRetimedBy()).isEqualTo(K);
    }

    @Test
    void restorePutsARetimedReferenceBackAndMarksItsRows() {
        referenceIs();
        retimedAlready();
        filmHasTwoEditionsWithSidecars();
        List<ForcedDownloadCheck> marked = downloadChecks();

        Outcome outcome = service.process(REFERENCE_ID, RetimeMode.RESTORE);

        assertThat(outcome).isEqualTo(Outcome.RESTORED);
        OpenSubtitlesReference written = saved();
        assertThat(written.getCuesText()).isEqualTo(ORIGINAL);
        assertThat(written.getRetimedBy()).isNull();
        assertThat(written.getCuesVersion()).isEqualTo(5);
        assertThat(marked).extracting(ForcedDownloadCheck::getStaleSince).containsExactly(NOW, NOW);
        verifyNoInteractions(rates);
    }

    @Test
    void restoreLeavesAReferenceThatWasNeverRetimed() {
        referenceIs();

        assertThat(service.process(REFERENCE_ID, RetimeMode.RESTORE)).isEqualTo(Outcome.UNCHANGED);

        verifyNoMoreInteractions(references);
        verifyNoInteractions(rates, checks, downloadChecks, alignments);
    }

    @Test
    void anUnusableOrMissingReferenceIsLeftAlone() {
        reference.setCueCount(0);
        when(references.findById(REFERENCE_ID)).thenReturn(Optional.of(reference), Optional.empty());

        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);
        assertThat(service.process(REFERENCE_ID, RetimeMode.ON)).isEqualTo(Outcome.UNCHANGED);

        verifyNoInteractions(rates, checks, downloadChecks, alignments);
    }

    @Test
    void theEditionsOfEveryChangedReferencesFilmAreTouchedInOrderEachOnce() {
        OpenSubtitlesReference second = new OpenSubtitlesReference();
        second.setFilmId(2L);
        when(references.findAllById(Set.of(7L, 8L))).thenReturn(List.of(reference, second));
        Edition a = TestData.edition(TestData.film(2L, "Heat", 1995, NOW), 60L, "Original", 0, null);
        Edition b = TestData.edition(TestData.film(1L, "Taken", 2008, NOW), 41L, "Extended", 0, null);
        Edition c = TestData.edition(TestData.film(1L, "Taken", 2008, NOW), 40L, "Original", 0, null);
        when(editions.findWithFilesByFilmIdIn(Set.of(1L, 2L))).thenReturn(List.of(a, b, c));

        assertThat(service.touchedEditions(Set.of(7L, 8L))).containsExactly(new TouchedEdition(40L, 1L),
            new TouchedEdition(41L, 1L), new TouchedEdition(60L, 2L));
    }
}
