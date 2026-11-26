package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedResolver.DownloadState;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What is due for a check and how a result is saved, with the repositories faked: a row made outdated by changed cues
 * ({@code staleSince}) is due again whatever it says, and a new result replaces it in place and clears the mark.
 */
@ExtendWith(MockitoExtension.class)
class SidecarCheckServiceUnitTest {

    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";
    private static final Instant STALE = NOW.minusSeconds(60);
    private static final Instant LATER = NOW.plusSeconds(3_600);
    private static final Result AGREES = new Result(1, 2, 1.0, 0, Verdict.AGREES, List.of(), 1, false);

    @Mock
    private FilmRepository films;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private EditionRepository editions;

    @Mock
    private ForcedResolver resolver;

    @Mock
    private ForcedSubtitleCheckRepository checks;

    @Mock
    private ForcedDownloadCheckRepository downloadChecks;

    @Mock
    private ForcedCheckCandidateRepository candidates;

    @Mock
    private SidecarTimingService timing;

    private SidecarCheckService service;
    private Film film;
    private Edition edition;
    private SubtitleSidecar owner;
    private SubtitleSidecar download;

    @BeforeEach
    void setUp() {
        service = new SidecarCheckService(films, sidecars, editions, resolver, checks, downloadChecks, candidates,
            true, timing);
        film = film(1L, "Taken", 2008, NOW);
        film.setImdbId("tt1");
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        MediaFile part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setPartNumber(1);
        part.setDurationMs(90 * MIN);
        owner = sidecar(5L, false);
        download = sidecar(7L, true);
        OpenSubtitlesResult best = result(5001);
        film.setOsBest(best.toJson());
        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(best, result(5007), result(5002))));
    }

    private static SubtitleSidecar sidecar(long id, boolean downloaded) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(id);
        row.setMediaFileId(10L);
        row.setPath(downloaded ? "opensubtitles:40:5007" : "Taken.en.forced.srt");
        row.setCuesText(CUES);
        row.setCueCount(1);
        row.setWholeEdition(downloaded);
        row.setUpdatedAt(NOW);
        if (downloaded) {
            row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
            row.setOpenSubtitlesFileId(5007L);
            row.setImdbId("tt1");
        }
        return row;
    }

    private static OpenSubtitlesResult result(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Taken.2008." + fileId, 23.976, 10, true, false, null,
            null, 1);
    }

    private static CheckDto check(String verdict) {
        return new CheckDto(verdict, List.of(), 0, 1.0, 1, 2, "Taken.2008", NOW);
    }

    private static AlignmentDto fit() {
        return new AlignmentDto("CHECK", List.of(), 0, 0.1, 1, 12, false, NOW, "EMBEDDED", null);
    }

    /** The edition as playback resolves it: the owner's file only when it has a check, and the given downloads. */
    private Resolution resolution(CheckDto fileCheck, List<DownloadState> downloads) {
        Pick pick = fileCheck == null ? new Pick(List.of(), Set.of())
            : new Pick(List.of(new Slot(owner, 0)), Set.of());
        return new Resolution(edition, List.of(10L), pick, "version", null, fileCheck, null, Map.of(), downloads,
            false, null, Map.of());
    }

    /** Playback has resolved the one edition to {@code resolution}; nothing was compared before. */
    private void resolved(Resolution resolution) {
        when(films.findOpenSubtitlesBest()).thenReturn(List.of(new OsBestRef(1L, "tt1", film.getOsBest(),
            film.getOsCandidates())));
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution));
        when(candidates.findByEditionIdInAndKind(Set.of(40L), SubtitleKind.FORCED)).thenReturn(List.of());
    }

    private DownloadState downloadState(Trust trust, Proof proof, CheckDto check, AlignmentDto alignment) {
        return new DownloadState(download, new Assessment(trust, proof), check, alignment,
            ServedSubtitles.version(edition, Downloads.pick(download)));
    }

    private static ForcedSubtitleCheck fileRow(Instant staleSince) {
        ForcedSubtitleCheck row = new ForcedSubtitleCheck();
        row.setId(100L);
        row.setEditionId(40L);
        row.setStaleSince(staleSince);
        return row;
    }

    private static ForcedDownloadCheck downloadRow(Instant staleSince) {
        ForcedDownloadCheck row = new ForcedDownloadCheck();
        row.setId(200L);
        row.setSidecarId(7L);
        row.setStaleSince(staleSince);
        return row;
    }

    @Test
    void aCurrentFileCheckWithNoStaleRowIsNotDue() {
        resolved(resolution(check("AGREES"), List.of()));

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aStaleFileCheckIsDueEvenWhenItAgreesAndStillCountsAsCurrent() {
        Resolution resolution = resolution(check("AGREES"), List.of());
        resolved(resolution);
        when(checks.findByEditionIdInAndStaleSinceIsNotNull(Set.of(40L))).thenReturn(List.of(fileRow(STALE)));

        assertThat(service.due()).singleElement().satisfies(due -> {
            assertThat(due.isDownload()).isFalse();
            assertThat(due.editionId()).isEqualTo(40L);
            assertThat(due.servedVersion()).isEqualTo(ServedSubtitles.version(edition, resolution.filePick()));
        });
    }

    @Test
    void aStaleFileCheckThatFailedIsDueToo() {
        resolved(resolution(check("CHECK"), List.of()));
        when(checks.findByEditionIdInAndStaleSinceIsNotNull(Set.of(40L))).thenReturn(List.of(fileRow(STALE)));

        assertThat(service.due()).hasSize(1);
    }

    @Test
    void aProvenDownloadWithNoStaleRowIsNotDue() {
        resolved(resolution(null, List.of(downloadState(Trust.PROVEN, Proof.AGREES, check("AGREES"), fit()))));

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aStaleAgreesOnADownloadMakesItDueAgain() {
        resolved(resolution(null, List.of(downloadState(Trust.PROVEN, Proof.AGREES, check("AGREES"), fit()))));
        when(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(7L)))
            .thenReturn(List.of(downloadRow(STALE)));
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.ready(List.of(), null));

        assertThat(service.due()).singleElement().satisfies(due -> {
            assertThat(due.downloadSidecarId()).isEqualTo(7L);
            assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5001L, 5002L);
        });
    }

    @Test
    void aStaleFailedCheckOnADownloadMakesItDueAgain() {
        resolved(resolution(null, List.of(downloadState(Trust.FAILED, Proof.NONE, check("CHECK"), fit()))));
        when(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(7L)))
            .thenReturn(List.of(downloadRow(STALE)));
        // The timing check will never fit it (switched off, cannot fit, read failed): nothing waits for good.
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.NEVER);

        assertThat(service.due()).extracting(DueCheck::downloadSidecarId).containsExactly(7L);
    }

    @Test
    void aStaleDownloadWhoseFitTheTimingCheckWillStillMakeWaitsForIt() {
        resolved(resolution(null, List.of(downloadState(Trust.FAILED, Proof.NONE, check("CHECK"), fit()))));
        when(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(7L)))
            .thenReturn(List.of(downloadRow(STALE)));
        // A stale fit would count as "the fit exists": the check waits for the fit the timing check will make.
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.PENDING);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aStaleDownloadCheckStillWaitsForTheDownloadsTimingFit() {
        resolved(resolution(null, List.of(downloadState(Trust.UNCHECKED, Proof.NONE, null, null))));
        when(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(7L)))
            .thenReturn(List.of(downloadRow(STALE)));

        assertThat(service.due()).isEmpty();
    }

    private void readyToSave(Resolution resolution) {
        when(films.findById(1L)).thenReturn(Optional.of(film));
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution));
    }

    private static OpenSubtitlesReference reference() {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(900L);
        reference.setImdbId("tt1");
        reference.setOsFileId(5001L);
        return reference;
    }

    @Test
    void aNewResultReplacesTheStaleFileRowInPlaceAndClearsTheMark() {
        Resolution resolution = resolution(check("CHECK"), List.of());
        readyToSave(resolution);
        ForcedSubtitleCheck stale = fileRow(STALE);
        when(checks.findByEditionId(40L)).thenReturn(Optional.of(stale));
        DueCheck due = new DueCheck(1L, 40L, "tt1", "Original", List.of(), Set.of(), resolution.fileVersion(), false,
            List.of());

        assertThat(service.save(due, reference(), AGREES, LATER)).isTrue();

        ArgumentCaptor<ForcedSubtitleCheck> saved = ArgumentCaptor.forClass(ForcedSubtitleCheck.class);
        verify(checks).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(stale);
        assertThat(stale.getId()).isEqualTo(100L);
        assertThat(stale.getStaleSince()).isNull();
        assertThat(stale.getVerdict()).isEqualTo("AGREES");
        assertThat(stale.getReferenceId()).isEqualTo(900L);
        assertThat(stale.getCheckedAt()).isEqualTo(LATER);
    }

    @Test
    void aNewResultReplacesTheStaleDownloadRowInPlaceAndClearsTheMark() {
        DownloadState state = downloadState(Trust.FAILED, Proof.NONE, check("CHECK"), fit());
        readyToSave(resolution(null, List.of(state)));
        ForcedDownloadCheck stale = downloadRow(STALE);
        when(downloadChecks.findBySidecarId(7L)).thenReturn(Optional.of(stale));
        DueCheck due = new DueCheck(1L, 40L, "tt1", "Original", List.of(), Set.of(), state.version(), false,
            List.of(), 7L, new RateComparison.Side(RateComparison.Kind.DOWNLOAD, null, null, null, null));

        assertThat(service.save(due, reference(), AGREES, LATER)).isTrue();

        ArgumentCaptor<ForcedDownloadCheck> saved = ArgumentCaptor.forClass(ForcedDownloadCheck.class);
        verify(downloadChecks).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(stale);
        assertThat(stale.getId()).isEqualTo(200L);
        assertThat(stale.getStaleSince()).isNull();
        assertThat(stale.getVerdict()).isEqualTo("AGREES");
    }

    @Test
    void aResultDroppedBecauseTheCuesChangedAgainWritesNothingAndTheStaleRowStaysDue() {
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.NEVER);
        DownloadState state = downloadState(Trust.FAILED, Proof.NONE, check("CHECK"), fit());
        readyToSave(resolution(null, List.of(state)));
        ForcedDownloadCheck stale = downloadRow(STALE);
        stale.setVerdict("CHECK");
        DueCheck due = new DueCheck(1L, 40L, "tt1", "Original", List.of(), Set.of(), "an older version", false,
            List.of(), 7L, new RateComparison.Side(RateComparison.Kind.DOWNLOAD, null, null, null, null));

        assertThat(service.save(due, reference(), AGREES, LATER)).isFalse();

        verify(films).findById(1L);
        verify(editions).findWithFilesByFilmIdIn(List.of(1L));
        verify(resolver).resolve(List.of(edition));
        verifyNoMoreInteractions(checks, downloadChecks);
        assertThat(stale.getStaleSince()).isEqualTo(STALE);
        assertThat(stale.getVerdict()).isEqualTo("CHECK");

        resolved(resolution(null, List.of(state)));
        when(downloadChecks.findBySidecarIdInAndStaleSinceIsNotNull(List.of(7L))).thenReturn(List.of(stale));

        assertThat(service.due()).extracting(DueCheck::downloadSidecarId).containsExactly(7L);
    }

    @Test
    void aDownloadsDueCheckCarriesItsRateSideAndItsOriginalCuesOnTheEditionsTimeline() {
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.NEVER);
        String original = "1\n00:01:40,000 --> 00:01:42,000\nHello\n\n";
        download.setTimedFps(23.976);
        download.setRetime(RetimeChoice.AUTO);
        download.setRetimedBy(0.959);
        download.setOriginalCuesText(original);
        download.setOffsetMs(500);
        resolved(resolution(null, List.of(downloadState(Trust.UNCHECKED, Proof.NONE, null, fit()))));

        DueCheck due = service.due().get(0);

        assertThat(due.side()).isEqualTo(RateComparison.Side.of(download));
        assertThat(due.side()).isEqualTo(new RateComparison.Side(RateComparison.Kind.DOWNLOAD, 23.976,
            RetimeChoice.AUTO, 0.959, original));
        assertThat(due.cues()).containsExactly(new SubtitleCue(10_500, 12_500, "Hello"));
        assertThat(due.originalCues()).containsExactly(new SubtitleCue(100_500, 102_500, "Hello"));
    }

    @Test
    void aDownloadThatIsNotRetimedCarriesItsSideAndNoOriginalCues() {
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.NEVER);
        download.setTimedFps(25.0);
        resolved(resolution(null, List.of(downloadState(Trust.UNCHECKED, Proof.NONE, null, fit()))));

        DueCheck due = service.due().get(0);

        assertThat(due.side()).isEqualTo(RateComparison.Side.of(download));
        assertThat(due.side().kind()).isEqualTo(RateComparison.Kind.DOWNLOAD);
        assertThat(due.originalCues()).isNull();
    }

    @Test
    void aRetimedDownloadWithNoOriginalCuesCarriesNoOriginalCues() {
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.NEVER);
        download.setTimedFps(23.976);
        download.setRetime(RetimeChoice.AUTO);
        download.setRetimedBy(0.959);
        resolved(resolution(null, List.of(downloadState(Trust.UNCHECKED, Proof.NONE, null, fit()))));

        DueCheck due = service.due().get(0);

        assertThat(due.side().retimed()).isTrue();
        assertThat(due.originalCues()).isNull();
    }

    @Test
    void aRetimedDownloadWaitsForItsTimingFitUntilAFitRowExistsAndThenIsDueEvenWhenTheRowSaysNoFile() {
        when(timing.offFitsState(1L, 7L)).thenReturn(OffFitsState.NEVER);
        download.setTimedFps(23.976);
        download.setRetime(RetimeChoice.AUTO);
        download.setRetimedBy(0.959);
        download.setOriginalCuesText(CUES);
        AlignmentDto noFile = new AlignmentDto("NO_REFERENCE", List.of("The frame rates of the files are not known "
            + "well enough to compare them"), 0, 0.0, 0, 1, false, NOW, "OPENSUBTITLES", null);

        resolved(resolution(null, List.of(downloadState(Trust.UNCHECKED, Proof.NONE, null, null))));
        assertThat(service.due()).isEmpty();

        resolved(resolution(null, List.of(downloadState(Trust.UNCHECKED, Proof.NONE, null, noFile))));
        assertThat(service.due()).extracting(DueCheck::downloadSidecarId).containsExactly(7L);
    }
}
