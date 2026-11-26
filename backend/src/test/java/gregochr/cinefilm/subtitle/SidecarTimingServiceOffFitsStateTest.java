package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository.ReferenceFacts;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.OffFitsState.Kind;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the timing check says about a download for the retime job, from the check's own rules: will it fit it, and has
 * it, on the film file as it is now. Repositories faked, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class SidecarTimingServiceOffFitsStateTest {

    private static final String IMDB = "tt0000001";
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();

    @Mock
    private FilmRates filmRates;
    @Mock
    private SubtitleSidecarRepository sidecars;
    @Mock
    private EditionRepository editions;
    @Mock
    private MediaFileRepository files;
    @Mock
    private EmbeddedCueStartsRepository cache;
    @Mock
    private ForcedSubtitleAlignmentRepository alignments;
    @Mock
    private OpenSubtitlesReferenceRepository references;
    @Mock
    private CreditsDetectionRepository credits;
    @Mock
    private PlatformTransactionManager transactions;

    private final ReadFailures failures = new ReadFailures();
    private SidecarTimingService service;
    private Edition edition;
    private MediaFile part;
    private SubtitleSidecar download;

    @BeforeEach
    void setUp() {
        service = new SidecarTimingService(sidecars, editions, files, cache, alignments, references, credits,
            new LibraryLock(), transactions, filmRates, failures, true);
        var film = film(1L, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setSizeBytes(1_000L);
        part.setModifiedAt(NOW);
        download = new SubtitleSidecar();
        download.setId(5L);
        download.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        download.setOpenSubtitlesFileId(777L);
        download.setPath("opensubtitles:40:777");
        download.setImdbId(IMDB);
        download.setMediaFileId(10L);
        download.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "x")).toList()));
        download.setCueCount(STARTS.size());
        download.setUpdatedAt(NOW);
    }

    /** The film's served sidecars: the download. */
    private void served() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED)).thenReturn(List.of(download));
    }

    private EmbeddedCueStarts read(String fingerprint) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(10L);
        read.setFingerprint(fingerprint);
        read.setStatus(EmbeddedCueStarts.Status.USABLE);
        read.setStartsMs(STARTS);
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of(read));
        return read;
    }

    private ForcedSubtitleAlignment fit(String referenceKey, java.time.Instant staleSince) {
        ForcedSubtitleAlignment fit = new ForcedSubtitleAlignment();
        fit.setSidecarId(5L);
        fit.setSidecarIdentity(TimingKeys.identity(download));
        fit.setReferenceKey(referenceKey);
        fit.setVerdict(TimingFit.Verdict.CHECK);
        fit.setStaleSince(staleSince);
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of(fit));
        return fit;
    }

    private String key() {
        return "10:" + TimingKeys.fingerprint(part);
    }

    private OffFitsState state() {
        return service.offFitsState(1L, 5L);
    }

    @Test
    void aCurrentReadAndACurrentFitAreReadyWithWhatTheCheckRead() {
        served();
        read(TimingKeys.fingerprint(part));
        fit(key(), null);

        OffFitsState state = state();

        assertThat(state.kind()).isEqualTo(Kind.READY);
        assertThat(state.starts()).isEqualTo(STARTS);
        assertThat(state.noReference()).isNull();
    }

    @Test
    void aFilmFileWithNoReadYetIsPending() {
        served();
        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aReadWithNoFitYetIsPending() {
        served();
        read(TimingKeys.fingerprint(part));

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aReadOfTheFilmFileBeforeItChangedIsPendingNotSettled() {
        served();
        read(TimingKeys.fingerprint(part));
        fit(key(), null);
        part.setSizeBytes(2_000L);

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aFitMadeOnAnOlderReferenceKeyIsPending() {
        served();
        read(TimingKeys.fingerprint(part));
        fit("10:1000:old", null);

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aFitMarkedStaleByARewriteIsPending() {
        served();
        read(TimingKeys.fingerprint(part));
        fit(key(), NOW);

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void withTheTimingCheckSwitchedOffTheCheckWillNeverFitIt() {
        SidecarTimingService off = new SidecarTimingService(sidecars, editions, files, cache, alignments, references,
            credits, new LibraryLock(), transactions, filmRates, failures, false);

        assertThat(off.offFitsState(1L, 5L)).isEqualTo(OffFitsState.NEVER);
        verifyNoInteractions(editions, sidecars);
    }

    @Test
    void aReadThatFailedOnTheFilmFileAsItIsNowSeveralTimesInARowIsNever() {
        served();
        for (int i = 0; i < ReadFailures.GIVE_UP; i++) {
            failures.record(10L, TimingKeys.fingerprint(part), NOW);
        }

        assertThat(state()).isEqualTo(OffFitsState.NEVER);
    }

    @Test
    void aSingleFailedReadStillWaitsAndTheNextSuccessfulReadIsReady() {
        served();
        failures.record(10L, TimingKeys.fingerprint(part), NOW);
        assertThat(state()).isEqualTo(OffFitsState.PENDING);

        // The check read the file the next time round: the failure is cleared and the fit is made.
        failures.clear(10L);
        read(TimingKeys.fingerprint(part));
        fit(key(), null);

        assertThat(state().kind()).isEqualTo(Kind.READY);
    }

    @Test
    void aReadThatFoundNoEnglishTrackIsReadyWithItsReason() {
        served();
        EmbeddedCueStarts none = read(TimingKeys.fingerprint(part));
        none.setStatus(EmbeddedCueStarts.Status.NO_REFERENCE);
        none.setReason("No English track");
        none.setStartsMs(List.of());
        fit(key(), null);

        OffFitsState state = state();

        assertThat(state.kind()).isEqualTo(Kind.READY);
        assertThat(state.noReference()).isEqualTo("No English track");
    }

    @Test
    void aFilmWithNoEnglishTrackIsReadyAtOnceWhateverTheOpenSubtitlesFitIsDoing() {
        served();
        EmbeddedCueStarts none = read(TimingKeys.fingerprint(part));
        none.setStatus(EmbeddedCueStarts.Status.NO_REFERENCE);
        none.setReason("No English track");
        none.setStartsMs(List.of());
        // The fit was made on an older set of stored OpenSubtitles files: outdated, and it will be made again.
        fit(key() + "|os" + DownloadedFit.RULES_VERSION + ":" + IMDB + ":4999", null);

        OffFitsState state = state();

        assertThat(state.kind()).isEqualTo(Kind.READY);
        assertThat(state.noReference()).isEqualTo("No English track");
    }

    @Test
    void aFilmFittedToOpenSubtitlesFilesIsReadyAndTheRatesDecide() {
        served();
        edition.getFilm().setOsFullSearchedImdbId(IMDB);
        edition.getFilm().setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(new OpenSubtitlesResult(4012,
            5012, "Release 5012", 23.976, 10, true, false, null, null))));
        EmbeddedCueStarts none = read(TimingKeys.fingerprint(part));
        none.setStatus(EmbeddedCueStarts.Status.NO_REFERENCE);
        none.setReason("No English track");
        none.setStartsMs(List.of());
        when(references.findFactsByFilmIdIn(Set.of(1L))).thenReturn(List.of(new ReferenceFacts(5012L, 1L, IMDB, 5012L,
            "Release 5012", 300, 3_001_000L, null)));
        fit(key() + "|os" + DownloadedFit.RULES_VERSION + ":" + IMDB + ":5012", null);

        assertThat(state().kind()).isEqualTo(Kind.READY);
    }

    @Test
    void aSidecarTimedForTheWholeEditionIsReadyOnTheEditionsKey() {
        served();
        download.setWholeEdition(true);
        read(TimingKeys.fingerprint(part));
        fit(key() + "@0", null);

        assertThat(state().kind()).isEqualTo(Kind.READY);
    }

    @Test
    void aReadThatFailedOnAnOlderVersionOfTheFilmFileDoesNotCount() {
        served();
        for (int i = 0; i < ReadFailures.GIVE_UP; i++) {
            failures.record(10L, "999:1", NOW);
        }

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aDownloadOfAnEarlierImdbMatchIsNotOneTheCheckFitsSoItIsNever() {
        served();
        download.setImdbId("tt9999999");
        when(sidecars.findById(5L)).thenReturn(java.util.Optional.of(download));

        assertThat(state()).isEqualTo(OffFitsState.NEVER);
    }

    @Test
    void aSidecarThatIsNotAForcedDownloadAndIsNotServedIsNever() {
        served();
        download.setKind(SubtitleKind.FULL);
        when(sidecars.findById(5L)).thenReturn(java.util.Optional.of(download));
        assertThat(state()).isEqualTo(OffFitsState.NEVER);

        SubtitleSidecar owners = new SubtitleSidecar();
        owners.setOrigin(SubtitleSidecar.Origin.FILE);
        when(sidecars.findById(5L)).thenReturn(java.util.Optional.of(owners));
        assertThat(state()).isEqualTo(OffFitsState.NEVER);
    }

    @Test
    void aDownloadWithNoCuesIsNever() {
        served();
        download.setCueCount(0);
        when(sidecars.findById(5L)).thenReturn(java.util.Optional.of(download));

        assertThat(state()).isEqualTo(OffFitsState.NEVER);
    }

    @Test
    void aFilmFileWithNoFingerprintYetIsPendingBecauseThatClearsOnItsOwn() {
        served();
        part.setSizeBytes(null);

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aFilmFileThatIsMissingForNowIsPending() {
        served();
        part.setPresence(Presence.MISSING);

        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aWholeEditionWhosePartLengthIsUnknownIsNeverBecauseItIsNotProbedAgain() {
        served();
        download.setWholeEdition(true);
        part.setDurationMs(null);

        assertThat(state()).isEqualTo(OffFitsState.NEVER);
    }

    @Test
    void aDownloadNotServedOnlyBecauseTheOwnerMarkedForcedSubtitlesNotNeededIsPending() {
        edition.getFilm().setForcedNeedOwner(ForcedNeedChoice.NOT_NEEDED);
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED)).thenReturn(List.of(download));
        when(sidecars.findById(5L)).thenReturn(java.util.Optional.of(download));

        // The owner may mark it needed again: a file that fit as it was must not have been fixed at AUTO meanwhile.
        assertThat(state()).isEqualTo(OffFitsState.PENDING);
    }

    @Test
    void aSidecarThatDoesNotExistIsNever() {
        served();

        assertThat(service.offFitsState(1L, 99L)).isEqualTo(OffFitsState.NEVER);
    }

    @Test
    void aSidecarTheCheckDoesNotServeIsNever() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED)).thenReturn(List.of());
        when(sidecars.findById(5L)).thenReturn(java.util.Optional.of(download));

        assertThat(service.offFitsState(1L, 5L)).isEqualTo(OffFitsState.NEVER);
    }
}
