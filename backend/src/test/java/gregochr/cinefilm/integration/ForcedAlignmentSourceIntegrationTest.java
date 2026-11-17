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
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadedFit;
import gregochr.cinefilm.subtitle.EmbeddedCueStarts;
import gregochr.cinefilm.subtitle.EmbeddedCueStartsRepository;
import gregochr.cinefilm.subtitle.ForcedSubtitleAlignment;
import gregochr.cinefilm.subtitle.ForcedSubtitleAlignmentRepository;
import gregochr.cinefilm.subtitle.ReferenceSource;
import gregochr.cinefilm.subtitle.SidecarTimingService;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.subtitle.SrtParser;
import gregochr.cinefilm.subtitle.SubtitleAdminService;
import gregochr.cinefilm.subtitle.SubtitleCue;
import gregochr.cinefilm.subtitle.SubtitleSidecar;
import gregochr.cinefilm.subtitle.SubtitleSidecarRepository;
import gregochr.cinefilm.subtitle.TimingFit;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * V32 on PostgreSQL: {@code reference_source} defaults to {@code EMBEDDED} and refuses any other value, and a fit to a
 * stored OpenSubtitles file goes through the real service: it is chosen from the stored files, saved with the file's
 * id, never sets an offset, and the Admin list shows its source and release as JSON.
 */
@Transactional
class ForcedAlignmentSourceIntegrationTest extends IntegrationTestBase {

    private static final String IMDB = "tt0000001";
    private static final List<Long> STARTS = List.of(100_000L, 400_000L, 700_000L, 1_000_000L, 1_300_000L, 1_600_000L,
        1_900_000L, 2_200_000L, 2_500_000L, 2_800_000L);

    @Autowired
    private FilmRepository films;

    @Autowired
    private SubtitleSidecarRepository sidecars;

    @Autowired
    private EmbeddedCueStartsRepository reads;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

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

    /** A film searched for full subtitles, its forced file, a read with no English track and a stored full file. */
    private SubtitleSidecar fixture() {
        Film film = film(null, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(new OpenSubtitlesResult(4012, 5012,
            "Taken.2008.BluRay", 23.976, 10, true, false, null, null))));
        Edition edition = edition(film, null, "Original", 90 * MIN, null);
        MediaFile part = file(edition, "Taken.m4v");
        part.setSizeBytes(4_000_000_000L);
        part.setModifiedAt(NOW);
        Film saved = films.saveAndFlush(film);
        MediaFile savedPart = saved.getEditions().get(0).originalParts().get(0);
        EmbeddedCueStarts read = new EmbeddedCueStarts();
        read.setMediaFileId(savedPart.getId());
        read.setFingerprint("4000000000:" + NOW.toEpochMilli());
        read.setStatus(EmbeddedCueStarts.Status.NO_REFERENCE);
        read.setReason("The film has no English subtitle track");
        read.setReadAt(NOW);
        reads.saveAndFlush(read);
        List<SubtitleCue> full = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            full.add(new SubtitleCue(i * 7_000L, i * 7_000L + 1_000, "line " + i));
        }
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(saved.getId());
        reference.setImdbId(IMDB);
        reference.setOsFileId(5012);
        reference.setRelease("Taken.2008.BluRay");
        reference.setCuesText(SrtParser.format(full));
        reference.setCueCount(400);
        reference.setLastCueEndMs(2_801_000L);
        reference.setFetchedAt(NOW);
        references.saveAndFlush(reference);
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setPath("Taken.en.forced.srt");
        sidecar.setSizeBytes(100);
        sidecar.setModifiedAt(NOW);
        sidecar.setMediaFileId(savedPart.getId());
        sidecar.setCuesText(SrtParser.format(STARTS.stream().map(s -> new SubtitleCue(s, s + 1_500, "line")).toList()));
        sidecar.setParseReport("No problems found");
        sidecar.setCueCount(STARTS.size());
        sidecar.setLastEndMs(2_801_500);
        sidecar.setUpdatedAt(NOW);
        return sidecars.saveAndFlush(sidecar);
    }

    @Test
    void aFitToAStoredOpenSubtitlesFileIsSavedWithItsIdNeverSetsAnOffsetAndAdminShowsItAsJson() throws Exception {
        SubtitleSidecar sidecar = fixture();
        Long filmId = films.findAll().get(0).getId();
        FitJob job = timing.fitJobs(filmId).get(0);
        assertThat(job.osReferences()).hasSize(1);
        assertThat(job.referenceKey()).endsWith("|os:" + IMDB + ":5012");
        DownloadedFit.Choice best = DownloadedFit.choose(job.servedStarts(), job.osReferences());

        SaveOutcome outcome = timing.save(job, best.result(), best.reference().osFileId(), NOW.plusSeconds(60));
        entityManager.flush();
        entityManager.clear();

        assertThat(outcome).isEqualTo(SaveOutcome.SAVED);
        assertThat(timing.fitJobs(filmId)).isEmpty();
        ForcedSubtitleAlignment row = alignments.findBySidecarId(sidecar.getId()).orElseThrow();
        assertThat(row.getReferenceSource()).isEqualTo(ReferenceSource.OPENSUBTITLES);
        assertThat(row.getReferenceOsFileId()).isEqualTo(5012L);
        assertThat(row.isApplied()).isFalse();
        assertThat(sidecars.findById(sidecar.getId()).orElseThrow().getOffsetMs()).isZero();
        JsonNode alignment = mapper.readTree(mapper.writeValueAsString(admin.list())).get(0).get("alignment");
        assertThat(alignment.get("source").asString()).isEqualTo("OPENSUBTITLES");
        assertThat(alignment.get("referenceRelease").asString()).isEqualTo("Taken.2008.BluRay");
        assertThat(alignment.get("applied").asBoolean()).isFalse();
    }

    @Test
    void theReferenceSourceDefaultsToEmbeddedAndAnyOtherValueIsRefused() {
        SubtitleSidecar sidecar = fixture();
        ForcedSubtitleAlignment row = new ForcedSubtitleAlignment();
        row.setSidecarId(sidecar.getId());
        row.setSidecarIdentity("i");
        row.setReferenceKey("k");
        row.setVerdict(TimingFit.Verdict.CHECK);
        row.setCheckedAt(Instant.parse("2024-03-31T23:30:00Z"));
        alignments.saveAndFlush(row);
        entityManager.clear();

        ForcedSubtitleAlignment loaded = alignments.findBySidecarId(sidecar.getId()).orElseThrow();

        assertThat(loaded.getReferenceSource()).isEqualTo(ReferenceSource.EMBEDDED);
        assertThat(loaded.getReferenceOsFileId()).isNull();
        assertThatThrownBy(() -> jdbc.update("update forced_subtitle_alignment set reference_source = 'ELSEWHERE' "
            + "where id = ?", loaded.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
