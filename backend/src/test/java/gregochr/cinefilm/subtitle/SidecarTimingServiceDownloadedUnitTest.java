package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The timing check for a film with no English track of its own (phase 3c-2a), repositories faked: which stored
 * OpenSubtitles files a served sidecar is fitted to, the key that goes stale with them, what a save records and that
 * it never sets an offset, and what Admin is told. (The fit to the film's own track is in
 * {@code SidecarTimingServiceUnitTest}.)
 */
@ExtendWith(MockitoExtension.class)
class SidecarTimingServiceDownloadedUnitTest {

    private static final Instant LATER = NOW.plusSeconds(3_600);
    private static final String IMDB = "tt0000001";
    private static final String BASE_KEY = "10:1000:" + NOW.toEpochMilli();
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();

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

    private SidecarTimingService service;
    private Edition edition;
    private MediaFile part;
    private SubtitleSidecar sidecar;

    @BeforeEach
    void setUp() {
        service = new SidecarTimingService(sidecars, editions, files, cache, alignments, references, credits,
            new LibraryLock(), transactions);
        Film film = film(1L, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(candidate(5012), candidate(5013))));
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setSizeBytes(1_000L);
        part.setModifiedAt(NOW);
        sidecar = new SubtitleSidecar();
        sidecar.setId(5L);
        sidecar.setPath("Taken.en.forced.srt");
        sidecar.setMediaFileId(10L);
        sidecar.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList()));
        sidecar.setCueCount(STARTS.size());
        sidecar.setUpdatedAt(NOW);
    }

    /** The film's served sidecar: the owner's file (or the download the test made of it). */
    private void served() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED)).thenReturn(List.of(sidecar));
    }

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null);
    }

    private static OpenSubtitlesReference reference(long fileId, int cues, String imdbId) {
        List<SubtitleCue> list = new ArrayList<>();
        for (int i = 1; i <= cues; i++) {
            list.add(new SubtitleCue(i * 10_000L, i * 10_000L + 1_000, "line " + i));
        }
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(1L);
        reference.setImdbId(imdbId);
        reference.setOsFileId(fileId);
        reference.setRelease("Release " + fileId);
        reference.setCuesText(SrtParser.format(list));
        reference.setCueCount(cues);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private void read(EmbeddedCueStarts.Status status) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(10L);
        read.setFingerprint(TimingKeys.fingerprint(part));
        read.setStatus(status);
        read.setReason("The film has no English subtitle track");
        when(cache.findAllById(Set.of(10L))).thenReturn(List.of(read));
    }

    private void stored(OpenSubtitlesReference... stored) {
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored));
    }

    private FitJob onlyJob() {
        served();
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of());
        List<FitJob> jobs = service.fitJobs(1L);
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    private ForcedSubtitleAlignment row(String key) {
        ForcedSubtitleAlignment row = new ForcedSubtitleAlignment();
        row.setSidecarId(5L);
        row.setSidecarIdentity(TimingKeys.identity(sidecar));
        row.setReferenceKey(key);
        row.setVerdict(Verdict.CHECK);
        row.setReferenceSource(ReferenceSource.OPENSUBTITLES);
        row.setReferenceOsFileId(5012L);
        row.setCheckedAt(NOW);
        return row;
    }

    private static Result aligned(int offset) {
        return new Result(Verdict.ALIGNED, offset, 1.0, 12, 12, 10, List.of());
    }

    @Test
    void aFilmWithNoTrackOfItsOwnIsFittedToTheUsableStoredFilesWithAKeyNamingThemAll() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored(reference(5013, 300, IMDB), reference(5012, 300, IMDB));

        FitJob job = onlyJob();

        assertThat(job.noReference()).isNull();
        assertThat(job.referenceStarts()).isEmpty();
        assertThat(job.osReferences()).extracting(DownloadedFit.Reference::osFileId).containsExactly(5012L, 5013L);
        assertThat(job.osReferences().get(0).starts()).hasSize(300).startsWith(10_000L, 20_000L);
        assertThat(job.osReferences().get(0).release()).isEqualTo("Release 5012");
        assertThat(job.referenceKey()).isEqualTo(BASE_KEY + "|os:" + IMDB + ":5012,5013");
    }

    @Test
    void aFileThatIsNotUsableOrWasFetchedForAnotherImdbIdOrIsNotACandidateIsNotFittedTo() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored(reference(5012, 299, IMDB), reference(5013, 300, "tt0000002"), reference(9999, 300, IMDB));

        FitJob job = onlyJob();

        assertThat(job.osReferences()).isEmpty();
        assertThat(job.noReference()).isEqualTo("The film has no English subtitle track");
        assertThat(job.referenceKey()).isEqualTo(BASE_KEY);
    }

    @Test
    void aDownloadIsNeverFittedToItsOwnOpenSubtitlesFile() {
        sidecar.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        sidecar.setOpenSubtitlesFileId(5012L);
        sidecar.setPath("opensubtitles:40:5012");
        sidecar.setImdbId(IMDB);
        sidecar.setWholeEdition(true);
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored(reference(5012, 300, IMDB), reference(5013, 300, IMDB));

        FitJob job = onlyJob();

        assertThat(job.osReferences()).extracting(DownloadedFit.Reference::osFileId).containsExactly(5013L);
        assertThat(job.referenceKey()).endsWith("|os:" + IMDB + ":5013");
    }

    @Test
    void aFilmWithAnEmbeddedTrackOrASearchForAnotherImdbIdKeepsTheFitToItsOwnTrack() {
        read(EmbeddedCueStarts.Status.USABLE);
        FitJob usable = onlyJob();
        assertThat(usable.osReferences()).isEmpty();
        assertThat(usable.referenceKey()).isEqualTo(BASE_KEY);

        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        edition.getFilm().setOsFullSearchedImdbId("tt0000002");
        assertThat(onlyJob().osReferences()).isEmpty();
    }

    @Test
    void anOpenSubtitlesFitIsCurrentOnlyWhileTheSameFilesAreUsableForTheSameImdbId() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        served();
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L));
        String key = BASE_KEY + "|os:" + IMDB + ":5012";
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of(row(key)));

        stored(reference(5012, 300, IMDB));
        assertThat(service.dueFilmIds()).isEmpty();

        stored(reference(5012, 300, IMDB), reference(5013, 300, IMDB));
        assertThat(service.dueFilmIds()).containsExactly(1L);

        stored(reference(5012, 300, "tt0000002"));
        assertThat(service.dueFilmIds()).containsExactly(1L);

        stored(reference(5012, 299, IMDB));
        assertThat(service.dueFilmIds()).containsExactly(1L);
    }

    @Test
    void aSaveRecordsTheFileFittedToAndNeverSetsAnOffsetOrMarksItApplied() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored(reference(5012, 300, IMDB));
        served();
        when(alignments.findBySidecarId(5L)).thenReturn(Optional.empty());
        FitJob job = new FitJob(1L, 5L, TimingKeys.identity(sidecar), BASE_KEY + "|os:" + IMDB + ":5012", STARTS,
            List.of(), null);

        SaveOutcome outcome = service.save(job, aligned(900), 5012L, LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.getOffsetSource()).isNull();
        ArgumentCaptor<ForcedSubtitleAlignment> captor = ArgumentCaptor.forClass(ForcedSubtitleAlignment.class);
        verify(alignments).save(captor.capture());
        assertThat(captor.getValue().getReferenceSource()).isEqualTo(ReferenceSource.OPENSUBTITLES);
        assertThat(captor.getValue().getReferenceOsFileId()).isEqualTo(5012L);
        assertThat(captor.getValue().isApplied()).isFalse();
        assertThat(captor.getValue().getVerdict()).isEqualTo(Verdict.ALIGNED);
    }

    @Test
    void aSaveIsDroppedWhenAnotherFileWasStoredSinceTheFitWasMade() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored(reference(5012, 300, IMDB), reference(5013, 300, IMDB));
        served();
        FitJob job = new FitJob(1L, 5L, TimingKeys.identity(sidecar), BASE_KEY + "|os:" + IMDB + ":5012", STARTS,
            List.of(), null);

        assertThat(service.save(job, aligned(0), 5012L, LATER)).isEqualTo(SaveOutcome.DROPPED);
    }

    @Test
    void adminIsToldTheSourceAndTheReleaseAndAnOpenSubtitlesFitIsNeverApplied() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored(reference(5012, 300, IMDB));
        ForcedSubtitleAlignment row = row(BASE_KEY + "|os:" + IMDB + ":5012");
        row.setFittedOffsetMs(900);
        row.setVerdict(Verdict.ALIGNED);
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of(row));
        sidecar.setOffsetMs(900);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);

        AlignmentDto dto = service.current(Map.of(5L, edition), List.of(sidecar)).get(5L);

        assertThat(dto.source()).isEqualTo("OPENSUBTITLES");
        assertThat(dto.referenceRelease()).isEqualTo("Release 5012");
        assertThat(dto.applied()).isFalse();
    }

    @Test
    void aFitToTheFilmsOwnTrackKeepsItsSourceAndHasNoRelease() {
        read(EmbeddedCueStarts.Status.NO_REFERENCE);
        stored();
        ForcedSubtitleAlignment row = row(BASE_KEY);
        row.setReferenceSource(ReferenceSource.EMBEDDED);
        row.setReferenceOsFileId(null);
        when(alignments.findBySidecarIdIn(List.of(5L))).thenReturn(List.of(row));

        AlignmentDto dto = service.current(Map.of(5L, edition), List.of(sidecar)).get(5L);

        assertThat(dto.source()).isEqualTo("EMBEDDED");
        assertThat(dto.referenceRelease()).isNull();
    }

    @Test
    void anOffsetIsNeverSetFromAFitToAnOpenSubtitlesFile() {
        assertThat(SidecarTimingService.shouldApply(aligned(900), sidecar, List.of(10L),
            ReferenceSource.EMBEDDED)).isTrue();
        assertThat(SidecarTimingService.shouldApply(aligned(900), sidecar, List.of(10L),
            ReferenceSource.OPENSUBTITLES)).isFalse();
    }
}
