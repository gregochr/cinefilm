package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.EmbeddedCueStarts;
import gregochr.cinefilm.subtitle.EmbeddedCueStartsRepository;
import gregochr.cinefilm.subtitle.ForcedSubtitleAlignment;
import gregochr.cinefilm.subtitle.ForcedSubtitleAlignmentRepository;
import gregochr.cinefilm.subtitle.SidecarTimingService;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.subtitle.SrtParser;
import gregochr.cinefilm.subtitle.SubtitleAdminService;
import gregochr.cinefilm.subtitle.SubtitleCue;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import gregochr.cinefilm.subtitle.SubtitleSidecar;
import gregochr.cinefilm.subtitle.SubtitleSidecarRepository;
import gregochr.cinefilm.subtitle.TimingFit;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V17 on PostgreSQL: the new columns and tables, their constraints and cascades, a targeted save through the real
 * service, and the Admin list's JSON. (The migration's backfill of {@code offset_source} for an offset that is not 0 is
 * plain SQL run once on real data; it needs a database that holds old rows, which a fresh container does not.)
 */
@Transactional
class SidecarTimingIntegrationTest extends IntegrationTestBase {

    private static final List<Long> STARTS = IntStream.range(0, 12).mapToObj(i -> 10_000L + i * 7_000L).toList();

    @Autowired
    private FilmRepository films;

    @Autowired
    private SubtitleSidecarRepository sidecars;

    @Autowired
    private EmbeddedCueStartsRepository reads;

    @Autowired
    private ForcedSubtitleAlignmentRepository alignments;

    @Autowired
    private SidecarTimingService timing;

    @Autowired
    private SubtitleAdminService admin;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper mapper;

    /** A film with one edition and one film file that has a size and a time. */
    private Film saved() {
        Film film = film(null, "Taken", 2008, NOW);
        Edition edition = edition(film, null, "Original", 90 * MIN, null);
        MediaFile file = file(edition, "Taken.m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        return films.saveAndFlush(film);
    }

    private static MediaFile fileOf(Film film) {
        return film.getEditions().get(0).originalParts().get(0);
    }

    private SubtitleSidecar sidecar(Film film) {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setPath("Taken.en.forced.srt");
        sidecar.setSizeBytes(100);
        sidecar.setModifiedAt(NOW);
        sidecar.setMediaFileId(fileOf(film).getId());
        sidecar.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList()));
        sidecar.setParseReport("No problems found");
        sidecar.setCueCount(STARTS.size());
        sidecar.setLastEndMs(95_000);
        sidecar.setUpdatedAt(NOW);
        return sidecars.saveAndFlush(sidecar);
    }

    private EmbeddedCueStarts read(MediaFile file, List<Long> starts) {
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(file.getId());
        read.setFingerprint("4000000000:" + NOW.toEpochMilli());
        read.setStatus(EmbeddedCueStarts.Status.USABLE);
        read.setStreamIndex(2);
        read.setCodec("hdmv_pgs_subtitle");
        read.setSubtitleStreamCount(3);
        read.setStartsMs(starts);
        read.setReadAt(NOW);
        return reads.saveAndFlush(read);
    }

    private ForcedSubtitleAlignment alignment(SubtitleSidecar sidecar) {
        ForcedSubtitleAlignment row = new ForcedSubtitleAlignment();
        row.setSidecarId(sidecar.getId());
        row.setSidecarIdentity("i".repeat(100));
        row.setReferenceKey("k".repeat(1000));
        row.setVerdict(Verdict.CHECK);
        row.setFittedOffsetMs(-1_200);
        row.setMatchedShare(0.33);
        row.setMatchedCount(4);
        row.setServedCount(12);
        row.setMedianResidualMs(90);
        row.setReasons("r".repeat(1000));
        row.setApplied(false);
        row.setCheckedAt(NOW);
        return alignments.saveAndFlush(row);
    }

    @Test
    void aSidecarsOffsetSourceAndAnAlignmentRoundTripWithFullLengthText() {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        sidecars.saveAndFlush(sidecar);
        ForcedSubtitleAlignment row = alignment(sidecar);
        entityManager.clear();

        SubtitleSidecar loaded = sidecars.findById(sidecar.getId()).orElseThrow();
        ForcedSubtitleAlignment fit = alignments.findBySidecarId(row.getSidecarId()).orElseThrow();

        assertThat(loaded.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.AUTO);
        assertThat(fit.getVerdict()).isEqualTo(Verdict.CHECK);
        assertThat(fit.getFittedOffsetMs()).isEqualTo(-1_200);
        assertThat(fit.getMatchedShare()).isEqualTo(0.33);
        assertThat(fit.getMatchedCount()).isEqualTo(4);
        assertThat(fit.getServedCount()).isEqualTo(12);
        assertThat(fit.getMedianResidualMs()).isEqualTo(90);
        assertThat(fit.getReasons()).hasSize(1000);
        assertThat(fit.getSidecarIdentity()).hasSize(100);
        assertThat(fit.getReferenceKey()).hasSize(1000);
        assertThat(fit.isApplied()).isFalse();
        assertThat(fit.getCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void aSidecarHasOneAlignmentAndDeletingItDeletesTheAlignment() {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);
        ForcedSubtitleAlignment row = alignment(sidecar);
        entityManager.clear();

        sidecars.deleteById(sidecar.getId());
        sidecars.flush();
        entityManager.clear();

        assertThat(alignments.findById(row.getId())).isEmpty();
    }

    @Test
    void aSecondAlignmentForOneSidecarIsRefused() {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);
        alignment(sidecar);

        assertThatThrownBy(() -> alignment(sidecar)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aReadOfAMediaFilesTrackRoundTripsAThousandsOfStartsAndGoesWithTheFile() {
        Film film = saved();
        MediaFile file = fileOf(film);
        List<Long> starts = LongStream.range(0, 5_000).map(i -> 1_000 + i * 1_437).boxed().toList();
        read(file, starts);
        entityManager.clear();

        EmbeddedCueStarts loaded = reads.findById(file.getId()).orElseThrow();

        assertThat(loaded.getStartsMs()).containsExactlyElementsOf(starts);
        assertThat(loaded.getCueCount()).isEqualTo(5_000);
        assertThat(loaded.getCodec()).isEqualTo("hdmv_pgs_subtitle");
        assertThat(loaded.getStreamIndex()).isEqualTo(2);
        assertThat(loaded.getSubtitleStreamCount()).isEqualTo(3);
        assertThat(loaded.getFingerprint()).isEqualTo("4000000000:" + NOW.toEpochMilli());

        jdbc.update("delete from audio_stream where media_file_id = ?", file.getId());
        jdbc.update("delete from subtitle_stream where media_file_id = ?", file.getId());
        jdbc.update("delete from media_file where id = ?", file.getId());
        entityManager.clear();

        assertThat(reads.findById(file.getId())).isEmpty();
    }

    @Test
    void aNoReferenceReadKeepsItsReasonAndNoStarts() {
        Film film = saved();
        EmbeddedCueStarts read = read(fileOf(film), List.of());
        read.setStatus(EmbeddedCueStarts.Status.NO_REFERENCE);
        read.setStreamIndex(null);
        read.setCodec(null);
        read.setReason("The file has no full English subtitle track (2 subtitle tracks, none a full English one)");
        reads.saveAndFlush(read);
        entityManager.clear();

        EmbeddedCueStarts loaded = reads.findById(read.getMediaFileId()).orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(EmbeddedCueStarts.Status.NO_REFERENCE);
        assertThat(loaded.getStartsMs()).isEmpty();
        assertThat(loaded.getStreamIndex()).isNull();
        assertThat(loaded.getReason()).startsWith("The file has no full English subtitle track");
    }

    @Test
    void anOffsetSourceThatIsNotOwnerOrAutoIsRefused() {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);

        assertThatThrownBy(() -> jdbc.update("update subtitle_sidecar set offset_source = 'ROBOT' where id = ?",
            sidecar.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAlignmentVerdictThatIsNotOneOfTheFourIsRefused() {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);
        ForcedSubtitleAlignment row = alignment(sidecar);

        assertThatThrownBy(() -> jdbc.update("update forced_subtitle_alignment set verdict = 'MAYBE' where id = ?",
            row.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aReadStatusThatIsNotOneOfTheTwoIsRefused() {
        Film film = saved();
        read(fileOf(film), STARTS);

        assertThatThrownBy(() -> jdbc.update("update embedded_cue_starts set status = 'HALF' where media_file_id = ?",
            fileOf(film).getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTargetedSaveSetsTheOffsetAndTheAdminListShowsItAsJson() throws Exception {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);
        read(fileOf(film), STARTS);
        FitJob job = timing.fitJobs(film.getId()).get(0);
        Instant later = NOW.plusSeconds(3_600);

        SaveOutcome outcome = timing.save(job, TimingFit.fit(STARTS, STARTS.stream().map(s -> s + 900).toList()),
            later);
        entityManager.flush();
        entityManager.clear();

        assertThat(outcome).isEqualTo(SaveOutcome.APPLIED);
        assertThat(timing.fitJobs(film.getId())).isEmpty();
        assertThat(timing.dueFilmIds()).isEmpty();
        List<SidecarDto> listed = admin.list();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(listed)).get(0);
        assertThat(json.get("id").asLong()).isEqualTo(sidecar.getId());
        assertThat(json.get("offsetMs").asInt()).isEqualTo(900);
        assertThat(json.get("offsetSource").asString()).isEqualTo("AUTO");
        JsonNode alignment = json.get("alignment");
        assertThat(alignment.get("verdict").asString()).isEqualTo("ALIGNED");
        assertThat(alignment.get("fittedOffsetMs").asInt()).isEqualTo(900);
        assertThat(alignment.get("matchedShare").asDouble()).isEqualTo(1.0);
        assertThat(alignment.get("matchedCount").asInt()).isEqualTo(12);
        assertThat(alignment.get("servedCount").asInt()).isEqualTo(12);
        assertThat(alignment.get("applied").asBoolean()).isTrue();
        assertThat(alignment.get("reasons")).isEmpty();
        assertThat(Instant.parse(alignment.get("checkedAt").asString())).isEqualTo(later);
    }

    @Test
    void aTargetedSaveIsDroppedWhenTheFileChangedWhileItWasFitted() {
        Film film = saved();
        SubtitleSidecar sidecar = sidecar(film);
        read(fileOf(film), STARTS);
        FitJob job = timing.fitJobs(film.getId()).get(0);
        sidecar.setCuesText(SrtParser.format(List.of(new SubtitleCue(1, 2, "different"))));
        sidecars.saveAndFlush(sidecar);

        SaveOutcome outcome = timing.save(job, TimingFit.fit(STARTS, STARTS), NOW);

        assertThat(outcome).isEqualTo(SaveOutcome.DROPPED);
        assertThat(alignments.count()).isZero();
        assertThat(sidecars.findById(sidecar.getId()).orElseThrow().getOffsetMs()).isZero();
    }
}
