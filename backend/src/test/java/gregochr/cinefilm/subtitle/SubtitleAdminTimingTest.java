package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Who set a file's offset and the timing check's fit, as Admin lists them: the owner's offset (a reset included) is
 * theirs for good, an automatic one goes when the file changes place, and the fit shows only while it is current.
 */
class SubtitleAdminTimingTest extends SubtitleTestBase {

    private static final Instant LATER = NOW.plus(Duration.ofHours(1));
    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();

    @Autowired
    private SidecarTimingService timing;

    @Autowired
    private EmbeddedCueStartsRepository cache;

    private Film stampedFilm(String title, int parts) {
        Film film = filmWithParts(title, parts, 60);
        for (MediaFile file : film.getEditions().get(0).originalParts()) {
            file.setSizeBytes(1_000L);
            file.setModifiedAt(NOW);
            mediaFiles.save(file);
        }
        return film;
    }

    private SubtitleSidecar timed(String path, Film film, int part) {
        SubtitleSidecar sidecar = sidecar(path, fileId(film, part), false);
        List<SubtitleCue> cues = STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList();
        sidecar.setCuesText(SrtParser.format(cues));
        sidecar.setCueCount(cues.size());
        return sidecars.saveAndFlush(sidecar);
    }

    private SidecarDto listed(SubtitleSidecar row) {
        return admin.list().stream().filter(d -> d.id().equals(row.getId())).findFirst().orElseThrow();
    }

    private FitJob readyJob(Film film) {
        MediaFile file = film.getEditions().get(0).originalParts().get(0);
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(file.getId());
        read.setFingerprint(TimingKeys.fingerprint(file));
        read.setStatus(EmbeddedCueStarts.Status.USABLE);
        read.setSubtitleStreamCount(2);
        read.setStartsMs(List.of(1_000L));
        read.setReadAt(NOW);
        cache.saveAndFlush(read);
        return timing.fitJobs(film.getId()).get(0);
    }

    @Test
    void anOwnersOffsetIsRecordedAsTheOwnersAndAResetToZeroToo() {
        Film film = stampedFilm("Taken", 1);
        SubtitleSidecar row = timed("Taken.en.forced.srt", film, 1);
        assertThat(listed(row).offsetSource()).isNull();

        SidecarDto nudged = admin.offset(row.getId(), new OffsetRequest(-250));

        assertThat(nudged.offsetMs()).isEqualTo(-250);
        assertThat(nudged.offsetSource()).isEqualTo("OWNER");
        assertThat(sidecars.findById(row.getId()).orElseThrow().getOffsetSource())
            .isEqualTo(SubtitleSidecar.OffsetSource.OWNER);

        SidecarDto reset = admin.offset(row.getId(), new OffsetRequest(0));

        assertThat(reset.offsetMs()).isZero();
        assertThat(reset.offsetSource()).isEqualTo("OWNER");
    }

    @Test
    void anOwnersResetStopsTheTimingCheckSettingTheOffsetAgain() {
        Film film = stampedFilm("Taken", 1);
        SubtitleSidecar row = timed("Taken.en.forced.srt", film, 1);
        timing.save(readyJob(film), new Result(Verdict.ALIGNED, 900, 1.0, 12, 12, 10, List.of()), LATER);
        assertThat(listed(row).offsetSource()).isEqualTo("AUTO");
        admin.offset(row.getId(), new OffsetRequest(0));
        assertThat(listed(row).alignment().applied()).isFalse();
        MediaFile changed = film.getEditions().get(0).originalParts().get(0);
        changed.setModifiedAt(NOW.plusSeconds(1));
        mediaFiles.saveAndFlush(changed);

        FitJob job = readyJob(film);
        assertThat(timing.save(job, new Result(Verdict.ALIGNED, 900, 1.0, 12, 12, 10, List.of()), LATER))
            .isEqualTo(SidecarTimingService.SaveOutcome.SAVED);

        assertThat(listed(row).offsetMs()).isZero();
        assertThat(listed(row).offsetSource()).isEqualTo("OWNER");
    }

    @Test
    void anAutomaticOffsetIsListedAsAutomaticWithItsFit() {
        Film film = stampedFilm("Taken", 1);
        SubtitleSidecar row = timed("Taken.en.forced.srt", film, 1);
        assertThat(listed(row).alignment()).isNull();
        timing.save(readyJob(film), new Result(Verdict.ALIGNED, 900, 0.92, 11, 12, 40, List.of()), LATER);

        SidecarDto dto = listed(row);

        assertThat(dto.offsetMs()).isEqualTo(900);
        assertThat(dto.offsetSource()).isEqualTo("AUTO");
        assertThat(dto.version()).isEqualTo(LATER.toEpochMilli());
        assertThat(dto.alignment()).isEqualTo(new AlignmentDto("ALIGNED", List.of(), 900, 0.92, 11, 12, true, LATER));
    }

    @Test
    void theFitIsHiddenForFilesTheEditionStopsServingBecauseTwoCompeteForOnePart() {
        Film film = stampedFilm("Taken", 1);
        SubtitleSidecar row = timed("Taken.en.forced.srt", film, 1);
        timing.save(readyJob(film), new Result(Verdict.ALIGNED, 900, 1.0, 12, 12, 10, List.of()), LATER);
        assertThat(listed(row).alignment()).isNotNull();

        SubtitleSidecar rival = timed("Taken.forced.en.srt", film, 1);

        assertThat(listed(row).served()).isFalse();
        assertThat(listed(row).alignment()).isNull();
        assertThat(listed(rival).alignment()).isNull();
    }

    @Test
    void theFitIsHiddenOnceTheFilesCuesOrTheFilmFileChanged() {
        Film film = stampedFilm("Taken", 1);
        SubtitleSidecar row = timed("Taken.en.forced.srt", film, 1);
        timing.save(readyJob(film), new Result(Verdict.ALIGNED, 900, 1.0, 12, 12, 10, List.of()), LATER);
        MediaFile file = film.getEditions().get(0).originalParts().get(0);
        file.setSizeBytes(2_000L);
        mediaFiles.saveAndFlush(file);

        assertThat(listed(row).alignment()).isNull();

        file.setSizeBytes(1_000L);
        mediaFiles.saveAndFlush(file);
        assertThat(listed(row).alignment()).isNotNull();
        row.setCuesText(SrtParser.format(List.of(new SubtitleCue(1, 2, "different"))));
        sidecars.saveAndFlush(row);

        assertThat(listed(row).alignment()).isNull();
    }

    @Test
    void attachingOrDetachingAFileTakesAnAutomaticOffsetAwayButNotTheOwnersOwn() {
        Film film = stampedFilm("Taken", 1);
        SubtitleSidecar automatic = timed("Taken.en.forced.srt", film, 1);
        automatic.setOffsetMs(900);
        automatic.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        SubtitleSidecar owners = timed("Taken.forced.en.srt", film, 1);
        owners.setOffsetMs(-250);
        owners.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        Long editionId = film.getEditions().get(0).getId();

        SidecarDto attached = admin.attach(automatic.getId(), new AttachRequest(editionId, null));
        SidecarDto ownersAttached = admin.attach(owners.getId(), new AttachRequest(editionId, null));

        assertThat(attached.offsetMs()).isZero();
        assertThat(attached.offsetSource()).isNull();
        assertThat(ownersAttached.offsetMs()).isEqualTo(-250);
        assertThat(ownersAttached.offsetSource()).isEqualTo("OWNER");

        automatic.setOffsetMs(900);
        automatic.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        SidecarDto detached = admin.detach(automatic.getId());

        assertThat(detached.offsetMs()).isZero();
        assertThat(detached.offsetSource()).isNull();
    }
}
