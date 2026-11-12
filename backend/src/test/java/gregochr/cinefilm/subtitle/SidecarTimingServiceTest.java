package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.ReferenceRead;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The database side of the timing check against the real repositories on H2: which films are due and in what order,
 * which files must be read, what is fitted to what, and the saves, which revalidate the file and the reference, set
 * the offset only when the rules say so, and never make a check stale by setting it.
 */
class SidecarTimingServiceTest extends SubtitleTestBase {

    private static final Instant LATER = NOW.plus(Duration.ofHours(1));
    private static final long HOUR = 3_600_000L;
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();

    @Autowired
    private SidecarTimingService service;

    @Autowired
    private EmbeddedCueStartsRepository cache;

    @Autowired
    private ForcedSubtitleAlignmentRepository alignments;

    @Autowired
    private LibraryLock lock;

    /** A film of {@code parts} files, each with a size and a modification time (so it has a fingerprint). */
    private Film film(String title, int parts) {
        Film film = filmWithParts(title, parts, 60);
        int n = 0;
        for (MediaFile file : film.getEditions().get(0).originalParts()) {
            file.setSizeBytes(1_000L + n++);
            file.setModifiedAt(NOW);
            mediaFiles.save(file);
        }
        return film;
    }

    private static MediaFile file(Film film, int part) {
        return film.getEditions().get(0).originalParts().get(part - 1);
    }

    /** A present sidecar whose cues start at {@code starts}. */
    private SubtitleSidecar timed(String path, Film film, int part, boolean whole, List<Long> starts) {
        SubtitleSidecar sidecar = sidecar(path, fileId(film, part), whole);
        List<SubtitleCue> cues = starts.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList();
        sidecar.setCuesText(SrtParser.format(cues));
        sidecar.setCueCount(cues.size());
        return sidecars.saveAndFlush(sidecar);
    }

    private SubtitleSidecar timed(String path, Film film) {
        return timed(path, film, 1, false, STARTS);
    }

    private EmbeddedCueStarts read(MediaFile file, List<Long> starts) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(file.getId());
        read.setFingerprint(TimingKeys.fingerprint(file));
        read.setStatus(EmbeddedCueStarts.Status.USABLE);
        read.setStreamIndex(2);
        read.setCodec("subrip");
        read.setSubtitleStreamCount(3);
        read.setStartsMs(starts);
        read.setReadAt(NOW);
        return cache.saveAndFlush(read);
    }

    private EmbeddedCueStarts noReferenceRead(MediaFile file, String reason) {
        EmbeddedCueStarts read = read(file, List.of());
        read.setStatus(EmbeddedCueStarts.Status.NO_REFERENCE);
        read.setReason(reason);
        return cache.saveAndFlush(read);
    }

    private static Result aligned(int offset) {
        return new Result(Verdict.ALIGNED, offset, 1.0, 12, 12, 10, List.of());
    }

    private FitJob onlyJob(Film film) {
        List<FitJob> jobs = service.fitJobs(film.getId());
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    /** A film with one sidecar, its file read, and the job to fit it. */
    private FitJob readyJob(Film film, SubtitleSidecar sidecar) {
        read(file(film, 1), STARTS);
        return onlyJob(film);
    }

    // --- which films are due, and what must be read ---

    @Test
    void aFilmWithAServedSubtitleAndNoReadOfItsFileIsDueAndNeedsTheFileRead() {
        Film film = film("Taken", 1);
        timed("Taken.en.forced.srt", film);

        assertThat(service.dueFilmIds()).containsExactly(film.getId());
        assertThat(service.readsFor(film.getId())).containsExactly(new ReferenceRead(fileId(film, 1), "Taken.m4v",
            "1000:" + NOW.toEpochMilli()));
        assertThat(service.fitJobs(film.getId())).isEmpty();
    }

    @Test
    void aFilmWithoutASubtitleIsNotDue() {
        film("Plain", 1);

        assertThat(service.dueFilmIds()).isEmpty();
        assertThat(service.readsFor(film("Other", 1).getId())).isEmpty();
    }

    @Test
    void aFileWithNoFingerprintCannotBeCheckedSoItsFilmIsNotDue() {
        Film film = filmWithParts("Taken", 1, 60);
        timed("Taken.en.forced.srt", film);

        assertThat(service.dueFilmIds()).isEmpty();
        assertThat(service.readsFor(film.getId())).isEmpty();
    }

    @Test
    void aMissingSubtitleOrAConflictOfTwoForOnePartIsNotServedSoNotChecked() {
        Film missing = film("Missing", 1);
        timed("Missing.en.forced.srt", missing).setPresence(Presence.MISSING);
        Film conflict = film("Conflict", 1);
        timed("Conflict.en.forced.srt", conflict);
        timed("Conflict.forced.en.srt", conflict);

        assertThat(service.dueFilmIds()).isEmpty();
    }

    @Test
    void aReadOfTheFileAsItIsNowLeavesOnlyTheFitToDo() {
        Film film = film("Taken", 1);
        timed("Taken.en.forced.srt", film);
        read(file(film, 1), STARTS);

        assertThat(service.readsFor(film.getId())).isEmpty();
        assertThat(service.dueFilmIds()).containsExactly(film.getId());
        assertThat(service.fitJobs(film.getId())).hasSize(1);
    }

    @Test
    void aFileThatChangedSinceItWasReadMustBeReadAgain() {
        Film film = film("Taken", 1);
        timed("Taken.en.forced.srt", film);
        read(file(film, 1), STARTS);
        MediaFile changed = file(film, 1);
        changed.setModifiedAt(NOW.plusSeconds(60));
        mediaFiles.saveAndFlush(changed);

        assertThat(service.readsFor(film.getId())).extracting(ReferenceRead::fingerprint)
            .containsExactly("1000:" + NOW.plusSeconds(60).toEpochMilli());
        assertThat(service.fitJobs(film.getId())).isEmpty();
        assertThat(service.dueFilmIds()).containsExactly(film.getId());
    }

    @Test
    void aFileSharedByTwoSidecarsOfOneFilmIsReadOnce() {
        Film film = film("Towers", 2);
        timed("Towers - pt1.en.forced.srt", film, 1, false, STARTS);
        timed("Towers - pt2.en.forced.srt", film, 2, false, STARTS);

        assertThat(service.readsFor(film.getId())).extracting(ReferenceRead::mediaFileId)
            .containsExactly(fileId(film, 1), fileId(film, 2));
    }

    @Test
    void filmsWithAnUnverifiedFileComeFirstThenTheNeverCheckedThenByFilm() {
        Film verified = film("Verified", 1);
        SubtitleSidecar verifiedFile = timed("Verified.en.forced.srt", verified);
        verifiedFile.setOwnerVerifiedAt(NOW);
        verifiedFile.setVerifiedParts(String.valueOf(fileId(verified, 1)));
        Film staleCheck = film("StaleCheck", 1);
        SubtitleSidecar stale = timed("StaleCheck.en.forced.srt", staleCheck);
        Film never = film("Never", 1);
        timed("Never.en.forced.srt", never);
        for (Film film : List.of(verified, staleCheck, never)) {
            read(file(film, 1), STARTS);
        }
        ForcedSubtitleAlignment old = new ForcedSubtitleAlignment();
        old.setSidecarId(stale.getId());
        old.setSidecarIdentity("other");
        old.setReferenceKey("other");
        old.setVerdict(Verdict.CHECK);
        old.setCheckedAt(NOW);
        alignments.saveAndFlush(old);

        assertThat(service.dueFilmIds()).containsExactly(never.getId(), staleCheck.getId(), verified.getId());
    }

    // --- storing a read ---

    @Test
    void aReadIsStoredWithWhatWasFoundAndReplacedByTheNextOne() {
        Film film = film("Taken", 1);
        ReferenceRead ask = new ReferenceRead(fileId(film, 1), "Taken.m4v", "1000:" + NOW.toEpochMilli());

        assertThat(service.saveReference(ask, new EmbeddedCueReader.Reading(EmbeddedCueStarts.Status.USABLE, 3, 2,
            "hdmv_pgs_subtitle", List.of(1_000L, 3_500L), null), NOW)).isEqualTo(SaveOutcome.SAVED);
        EmbeddedCueStarts stored = cache.findById(ask.mediaFileId()).orElseThrow();

        assertThat(stored.getStatus()).isEqualTo(EmbeddedCueStarts.Status.USABLE);
        assertThat(stored.getFingerprint()).isEqualTo(ask.fingerprint());
        assertThat(stored.getStreamIndex()).isEqualTo(2);
        assertThat(stored.getCodec()).isEqualTo("hdmv_pgs_subtitle");
        assertThat(stored.getSubtitleStreamCount()).isEqualTo(3);
        assertThat(stored.getCueCount()).isEqualTo(2);
        assertThat(stored.getStartsMs()).containsExactly(1_000L, 3_500L);
        assertThat(stored.getReason()).isNull();
        assertThat(stored.getReadAt()).isEqualTo(NOW);

        assertThat(service.saveReference(ask, new EmbeddedCueReader.Reading(EmbeddedCueStarts.Status.NO_REFERENCE, 0,
            null, null, List.of(), "The file has no subtitle track"), LATER)).isEqualTo(SaveOutcome.SAVED);
        EmbeddedCueStarts replaced = cache.findById(ask.mediaFileId()).orElseThrow();

        assertThat(replaced.getStatus()).isEqualTo(EmbeddedCueStarts.Status.NO_REFERENCE);
        assertThat(replaced.getStartsMs()).isEmpty();
        assertThat(replaced.getCueCount()).isZero();
        assertThat(replaced.getReason()).isEqualTo("The file has no subtitle track");
        assertThat(replaced.getReadAt()).isEqualTo(LATER);
        assertThat(cache.count()).isEqualTo(1);
    }

    @Test
    void aReadOfAFileThatChangedOrWentMeanwhileIsNotStored() {
        Film film = film("Taken", 1);
        EmbeddedCueReader.Reading reading = new EmbeddedCueReader.Reading(EmbeddedCueStarts.Status.USABLE, 1, 2, "ass",
            List.of(1L), null);
        MediaFile file = file(film, 1);

        assertThat(service.saveReference(new ReferenceRead(file.getId(), "Taken.m4v", "1000:1"), reading, NOW))
            .isEqualTo(SaveOutcome.DROPPED);
        assertThat(service.saveReference(new ReferenceRead(999_999L, "x", "1000:" + NOW.toEpochMilli()), reading, NOW))
            .isEqualTo(SaveOutcome.DROPPED);
        file.setPresence(Presence.MISSING);
        mediaFiles.saveAndFlush(file);
        assertThat(service.saveReference(new ReferenceRead(file.getId(), "Taken.m4v",
            "1000:" + NOW.toEpochMilli()), reading, NOW)).isEqualTo(SaveOutcome.DROPPED);
        assertThat(cache.count()).isZero();
    }

    // --- what is fitted to what ---

    @Test
    void aJobCarriesTheSidecarsRawCueStartsAndTheFilesTrack() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOffsetMs(400);
        read(file(film, 1), List.of(1_000L, 2_000L));

        FitJob job = onlyJob(film);

        assertThat(job.filmId()).isEqualTo(film.getId());
        assertThat(job.sidecarId()).isEqualTo(sidecar.getId());
        assertThat(job.servedStarts()).isEqualTo(STARTS);
        assertThat(job.referenceStarts()).containsExactly(1_000L, 2_000L);
        assertThat(job.noReference()).isNull();
        assertThat(job.identity()).isEqualTo(TimingKeys.identity(sidecar));
        assertThat(job.referenceKey()).isEqualTo("%d:1000:%d".formatted(fileId(film, 1), NOW.toEpochMilli()));
    }

    @Test
    void aWholeEditionFileIsFittedToEveryPartsTrackJoinedOnTheEditionTimeline() {
        Film film = film("Towers", 2);
        timed("Towers.en.forced.srt", film, 1, true, STARTS);
        read(file(film, 1), List.of(1_000L, 2_000L));
        read(file(film, 2), List.of(500L, 900L));

        assertThat(service.readsFor(film.getId())).isEmpty();
        FitJob job = onlyJob(film);

        assertThat(job.referenceStarts()).containsExactly(1_000L, 2_000L, HOUR + 500, HOUR + 900);
        assertThat(job.referenceKey()).endsWith("@" + HOUR);
    }

    @Test
    void aWholeEditionFileWaitsForEveryPartToBeRead() {
        Film film = film("Towers", 2);
        timed("Towers.en.forced.srt", film, 1, true, STARTS);
        read(file(film, 1), List.of(1_000L));

        assertThat(service.readsFor(film.getId())).extracting(ReferenceRead::mediaFileId)
            .containsExactly(fileId(film, 2));
        assertThat(service.fitJobs(film.getId())).isEmpty();
    }

    @Test
    void aPerPartFileIsFittedToItsOwnPartsTrackOnThePartsOwnTimeline() {
        Film film = film("Towers", 2);
        timed("Towers - pt2.en.forced.srt", film, 2, false, STARTS);

        assertThat(service.readsFor(film.getId())).extracting(ReferenceRead::mediaFileId)
            .containsExactly(fileId(film, 2));
        read(file(film, 2), List.of(500L, 900L));

        assertThat(onlyJob(film).referenceStarts()).containsExactly(500L, 900L);
    }

    @Test
    void aFileWithNoUsableTrackGivesAJobWithTheReason() {
        Film film = film("Taken", 1);
        timed("Taken.en.forced.srt", film);
        noReferenceRead(file(film, 1), "The file has no subtitle track");

        FitJob job = onlyJob(film);

        assertThat(job.noReference()).isEqualTo("The file has no subtitle track");
        assertThat(job.referenceStarts()).isEmpty();
    }

    @Test
    void aWholeEditionFileNamesThePartThatHasNoUsableTrack() {
        Film film = film("Towers", 2);
        timed("Towers.en.forced.srt", film, 1, true, STARTS);
        read(file(film, 1), List.of(1_000L));
        noReferenceRead(file(film, 2), "The file has no subtitle track");

        assertThat(onlyJob(film).noReference()).isEqualTo("Part 2: The file has no subtitle track");
    }

    @Test
    void aWholeEditionFileNamesTheFirstPartWhenItIsThePartWithNoUsableTrack() {
        Film film = film("Towers", 2);
        timed("Towers.en.forced.srt", film, 1, true, STARTS);
        noReferenceRead(file(film, 1), "The file has no subtitle track");
        read(file(film, 2), List.of(1_000L));

        FitJob job = onlyJob(film);

        assertThat(job.noReference()).isEqualTo("Part 1: The file has no subtitle track");
        assertThat(job.referenceStarts()).isEmpty();
    }

    @Test
    void aFilmFileThatIsMissingOrAnEditionWithNoFilesGivesNothingToDo() {
        Film missing = film("Missing", 1);
        timed("Missing.en.forced.srt", missing);
        MediaFile gone = file(missing, 1);
        gone.setPresence(Presence.MISSING);
        mediaFiles.saveAndFlush(gone);
        Film empty = filmWithParts("Empty", 0, 60);

        assertThat(service.readsFor(missing.getId())).isEmpty();
        assertThat(service.fitJobs(missing.getId())).isEmpty();
        assertThat(service.dueFilmIds()).isEmpty();
        assertThat(service.readsFor(empty.getId())).isEmpty();
    }

    @Test
    void aWholeEditionFileOfAnEditionWhosePartLengthIsUnknownIsNotChecked() {
        Film film = film("Towers", 2);
        timed("Towers.en.forced.srt", film, 1, true, STARTS);
        MediaFile second = file(film, 2);
        second.setDurationMs(null);
        mediaFiles.saveAndFlush(second);

        assertThat(service.dueFilmIds()).isEmpty();
        assertThat(service.readsFor(film.getId())).isEmpty();
    }

    // --- saving a fit, and the offset ---

    @Test
    void anAlignedFitIsStoredAndSetsTheOffsetAsAutomatic() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);

        SaveOutcome outcome = service.save(job, new Result(Verdict.ALIGNED, 900, 0.92, 11, 12, 40, List.of()), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.APPLIED);
        assertThat(sidecar.getOffsetMs()).isEqualTo(900);
        assertThat(sidecar.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.AUTO);
        assertThat(sidecar.getUpdatedAt()).isEqualTo(LATER);
        ForcedSubtitleAlignment row = alignments.findBySidecarId(sidecar.getId()).orElseThrow();
        assertThat(row.getVerdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(row.getFittedOffsetMs()).isEqualTo(900);
        assertThat(row.getMatchedShare()).isEqualTo(0.92);
        assertThat(row.getMatchedCount()).isEqualTo(11);
        assertThat(row.getServedCount()).isEqualTo(12);
        assertThat(row.getMedianResidualMs()).isEqualTo(40);
        assertThat(row.getReasons()).isEmpty();
        assertThat(row.isApplied()).isTrue();
        assertThat(row.getCheckedAt()).isEqualTo(LATER);
        assertThat(row.getSidecarIdentity()).isEqualTo(job.identity());
        assertThat(row.getReferenceKey()).isEqualTo(job.referenceKey());
        assertThat(lock.holder()).isNull();
    }

    @Test
    void settingTheOffsetDoesNotMakeTheCheckStaleSoNothingIsCheckedAgain() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);

        service.save(job, aligned(900), LATER);

        assertThat(sidecar.getOffsetMs()).isEqualTo(900);
        assertThat(TimingKeys.identity(sidecar)).isEqualTo(job.identity());
        assertThat(service.fitJobs(film.getId())).isEmpty();
        assertThat(service.dueFilmIds()).isEmpty();
        assertThat(service.readsFor(film.getId())).isEmpty();
    }

    @Test
    void aFitCloserThanAHundredMillisecondsToTheCurrentOffsetLeavesItAlone() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOffsetMs(500);
        FitJob job = readyJob(film, sidecar);

        SaveOutcome outcome = service.save(job, aligned(590), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(sidecar.getOffsetMs()).isEqualTo(500);
        assertThat(sidecar.getOffsetSource()).isNull();
        assertThat(sidecar.getUpdatedAt()).isEqualTo(NOW);
        assertThat(alignments.findBySidecarId(sidecar.getId()).orElseThrow().isApplied()).isFalse();
    }

    @Test
    void aFitExactlyAHundredMillisecondsFromTheCurrentOffsetSetsIt() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOffsetMs(500);
        FitJob job = readyJob(film, sidecar);

        assertThat(service.save(job, aligned(600), LATER)).isEqualTo(SaveOutcome.APPLIED);
        assertThat(sidecar.getOffsetMs()).isEqualTo(600);
    }

    @Test
    void anOffsetTheOwnerSetIsNeverChangedAndAResetToZeroCounts() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOffsetMs(0);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        FitJob job = readyJob(film, sidecar);

        SaveOutcome outcome = service.save(job, aligned(2_400), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.OWNER);
        ForcedSubtitleAlignment row = alignments.findBySidecarId(sidecar.getId()).orElseThrow();
        assertThat(row.getVerdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(row.getFittedOffsetMs()).isEqualTo(2_400);
        assertThat(row.isApplied()).isFalse();
    }

    @Test
    void aFileTheOwnerVerifiedIsCheckedButItsOffsetIsNeverChanged() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOwnerVerifiedAt(NOW);
        sidecar.setVerifiedParts(String.valueOf(fileId(film, 1)));
        FitJob job = readyJob(film, sidecar);

        SaveOutcome outcome = service.save(job, aligned(2_400), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.getOffsetSource()).isNull();
        assertThat(alignments.findBySidecarId(sidecar.getId()).orElseThrow().getVerdict()).isEqualTo(Verdict.ALIGNED);
    }

    @Test
    void aVerificationFromBeforeThePartsChangedNoLongerProtectsTheOffset() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOwnerVerifiedAt(NOW);
        sidecar.setVerifiedParts("1,2");
        FitJob job = readyJob(film, sidecar);

        assertThat(service.save(job, aligned(2_400), LATER)).isEqualTo(SaveOutcome.APPLIED);
    }

    @Test
    void anAutomaticOffsetIsSetAgainByALaterFit() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOffsetMs(500);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        FitJob job = readyJob(film, sidecar);

        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.APPLIED);

        assertThat(sidecar.getOffsetMs()).isEqualTo(900);
        assertThat(sidecar.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.AUTO);
    }

    @Test
    void aLaterFitThatAgreesWithTheAutomaticOffsetStillSaysItWasApplied() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        service.save(job, aligned(900), NOW);
        MediaFile changed = file(film, 1);
        changed.setModifiedAt(NOW.plusSeconds(5));
        mediaFiles.saveAndFlush(changed);
        read(changed, STARTS);

        SaveOutcome outcome = service.save(onlyJob(film), aligned(900), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(alignments.findBySidecarId(sidecar.getId()).orElseThrow().isApplied()).isTrue();
        assertThat(alignments.count()).isEqualTo(1);
    }

    @Test
    void aFitThatIsNotAlignedIsStoredWithItsReasonsAndChangesNothing() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);

        SaveOutcome outcome = service.save(job, new Result(Verdict.CHECK, 600, 0.33, 4, 12, 90,
            List.of("Only 4 of 12 lines line up with the film's English track", "Another reason")), LATER);

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(sidecar.getOffsetMs()).isZero();
        ForcedSubtitleAlignment row = alignments.findBySidecarId(sidecar.getId()).orElseThrow();
        assertThat(row.getVerdict()).isEqualTo(Verdict.CHECK);
        assertThat(row.reasonList()).containsExactly("Only 4 of 12 lines line up with the film's English track",
            "Another reason");
        assertThat(row.isApplied()).isFalse();
    }

    @Test
    void noReferenceAndTooFewAreStoredSoTheyAreNotCheckedAgain() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        noReferenceRead(file(film, 1), "The file has no subtitle track");
        FitJob job = onlyJob(film);

        service.save(job, TimingFit.noReference(12, job.noReference()), LATER);

        assertThat(alignments.findBySidecarId(sidecar.getId()).orElseThrow().getVerdict())
            .isEqualTo(Verdict.NO_REFERENCE);
        assertThat(service.dueFilmIds()).isEmpty();
    }

    @Test
    void reasonsAreCutToTheColumnLength() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);

        service.save(job, new Result(Verdict.CHECK, 0, 0, 0, 12, 0, List.of("r".repeat(1_500))), LATER);

        assertThat(alignments.findBySidecarId(sidecar.getId()).orElseThrow().getReasons()).hasSize(1_000);
    }

    // --- revalidation ---

    @Test
    void aFitIsDroppedWhenTheFilesCuesChangedWhileItWasMade() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        sidecar.setCuesText(SrtParser.format(List.of(new SubtitleCue(1, 2, "different"))));
        sidecars.saveAndFlush(sidecar);

        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);

        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(alignments.count()).isZero();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aFitIsDroppedWhenTheFilmFileWasReadAgainWhileItWasMade() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        MediaFile changed = file(film, 1);
        changed.setSizeBytes(77L);
        mediaFiles.saveAndFlush(changed);
        read(changed, List.of(1_000L));

        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);

        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(alignments.count()).isZero();
    }

    @Test
    void aFitIsDroppedWhenTheFilmFileChangedAndHasNotBeenReadAgainYet() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        MediaFile changed = file(film, 1);
        changed.setSizeBytes(77L);
        mediaFiles.saveAndFlush(changed);

        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);
        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(alignments.count()).isZero();
    }

    @Test
    void anAutomaticOffsetCloseToTheFitIsLeftAndNotCalledApplied() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        sidecar.setOffsetMs(500);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        FitJob job = readyJob(film, sidecar);

        assertThat(service.save(job, aligned(540), LATER)).isEqualTo(SaveOutcome.SAVED);

        assertThat(sidecar.getOffsetMs()).isEqualTo(500);
        assertThat(alignments.findBySidecarId(sidecar.getId()).orElseThrow().isApplied()).isFalse();
    }

    @Test
    void aFitIsDroppedWhenTheFileIsNotServedAnyMore() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        sidecar.setPresence(Presence.MISSING);
        sidecars.saveAndFlush(sidecar);

        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);
        assertThat(alignments.count()).isZero();
    }

    @Test
    void aFitIsDroppedWhenTheFileMovedToAnotherPart() {
        Film film = film("Towers", 2);
        SubtitleSidecar sidecar = timed("Towers.en.forced.srt", film, 1, false, STARTS);
        read(file(film, 1), STARTS);
        FitJob job = onlyJob(film);
        sidecar.setMediaFileId(fileId(film, 2));
        sidecars.saveAndFlush(sidecar);

        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.DROPPED);
    }

    @Test
    void aFitIsNotSavedWhileAScanOrAnEditHoldsTheLibraryLock() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.BUSY);
        } finally {
            lock.release();
        }

        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(alignments.count()).isZero();
        assertThat(service.save(job, aligned(900), LATER)).isEqualTo(SaveOutcome.APPLIED);
    }

    // --- what Admin shows ---

    @Test
    void adminSeesTheFitOnlyWhileItIsCurrent() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        FitJob job = readyJob(film, sidecar);
        service.save(job, new Result(Verdict.ALIGNED, 900, 0.92, 11, 12, 40, List.of()), LATER);
        Map<Long, gregochr.cinefilm.film.Edition> editions = Map.of(sidecar.getId(), film.getEditions().get(0));

        Map<Long, AlignmentDto> current = service.current(editions, List.of(sidecar));

        assertThat(current).containsOnlyKeys(sidecar.getId());
        assertThat(current.get(sidecar.getId())).isEqualTo(new AlignmentDto("ALIGNED", List.of(), 900, 0.92, 11, 12,
            true, LATER));

        MediaFile changed = file(film, 1);
        changed.setModifiedAt(NOW.plusSeconds(5));
        mediaFiles.saveAndFlush(changed);
        assertThat(service.current(editions, List.of(sidecar))).isEmpty();

        read(changed, STARTS);
        assertThat(service.current(editions, List.of(sidecar))).isEmpty();
    }

    @Test
    void aFileWhoseCuesChangedHasNoCurrentFitAndNoFilesHaveNone() {
        Film film = film("Taken", 1);
        SubtitleSidecar sidecar = timed("Taken.en.forced.srt", film);
        service.save(readyJob(film, sidecar), aligned(900), LATER);
        sidecar.setCuesText(SrtParser.format(List.of(new SubtitleCue(1, 2, "different"))));
        sidecars.saveAndFlush(sidecar);

        assertThat(service.current(Map.of(sidecar.getId(), film.getEditions().get(0)), List.of(sidecar))).isEmpty();
        assertThat(service.current(Map.of(), new ArrayList<>())).isEmpty();
    }
}
