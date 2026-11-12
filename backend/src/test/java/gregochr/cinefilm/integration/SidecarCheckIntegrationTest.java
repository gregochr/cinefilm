package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedCheckCandidate;
import gregochr.cinefilm.subtitle.ForcedCheckCandidateRepository;
import gregochr.cinefilm.subtitle.ForcedSubtitleCheck;
import gregochr.cinefilm.subtitle.ForcedSubtitleCheckRepository;
import gregochr.cinefilm.subtitle.ReferenceWalk;
import gregochr.cinefilm.subtitle.SidecarCheckService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** V16's and V18's tables on PostgreSQL: the unique keys, the cascades and the budget's atomic statements. */
@Transactional
class SidecarCheckIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @Autowired
    private ForcedSubtitleCheckRepository checks;

    @Autowired
    private OpenSubtitlesBudget budget;

    @Autowired
    private EntityManager entityManager;

    private Film saved() {
        return saved(true);
    }

    /** A film with one edition, with or without a film file (a file's child rows would block deleting it). */
    private Film saved(boolean withFile) {
        Film film = film(null, "Taken", 2008, NOW);
        Edition edition = edition(film, null, "Original", 90 * MIN, null);
        if (withFile) {
            file(edition, "Taken.m4v");
        }
        film.setImdbId("tt0936501");
        return films.saveAndFlush(film);
    }

    private OpenSubtitlesReference reference(Film film, long fileId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId("tt0936501");
        reference.setOsFileId(fileId);
        reference.setRelease("x".repeat(200));
        reference.setCuesText("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n");
        reference.setCueCount(1);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    private ForcedSubtitleCheck check(Film film, OpenSubtitlesReference reference) {
        ForcedSubtitleCheck row = new ForcedSubtitleCheck();
        row.setEditionId(film.getEditions().get(0).getId());
        row.setServedVersion("v1|1|2@0");
        row.setReferenceId(reference.getId());
        row.setVerdict("AGREES");
        row.setMatchedShare(0.95);
        row.setServedCount(12);
        row.setReferenceCount(13);
        row.setBestOffsetMs(-300);
        row.setReasons("r".repeat(1000));
        row.setCheckedAt(NOW);
        return checks.saveAndFlush(row);
    }

    @Test
    void aReferenceAndACheckRoundTripWithFullLengthText() {
        Film film = saved();
        ForcedSubtitleCheck row = check(film, reference(film, 5011));
        entityManager.clear();

        ForcedSubtitleCheck loaded = checks.findByEditionId(row.getEditionId()).orElseThrow();

        assertThat(loaded.getVerdict()).isEqualTo("AGREES");
        assertThat(loaded.getMatchedShare()).isEqualTo(0.95);
        assertThat(loaded.getBestOffsetMs()).isEqualTo(-300);
        assertThat(loaded.getReasons()).hasSize(1000);
        assertThat(loaded.getCheckedAt()).isEqualTo(NOW);
        assertThat(references.findByFilmIdAndImdbIdAndOsFileId(film.getId(), "tt0936501", 5011)
            .orElseThrow().getRelease()).hasSize(200);
    }

    @Test
    void aFilmHasOneReferencePerFileAndAnEditionOneCheck() {
        Film film = saved();
        OpenSubtitlesReference reference = reference(film, 5011);
        check(film, reference);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reference(film, 5011))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        entityManager.clear();
    }

    @Test
    void deletingAnEditionDeletesItsCheckAndDeletingAFilmItsReferences() {
        Film film = saved(false);
        OpenSubtitlesReference reference = reference(film, 5011);
        ForcedSubtitleCheck row = check(film, reference);
        entityManager.clear();

        editions.deleteByIds(java.util.List.of(row.getEditionId()));
        entityManager.clear();
        assertThat(checks.findByEditionId(row.getEditionId())).isEmpty();
        assertThat(references.findById(reference.getId())).isPresent();

        entityManager.createNativeQuery("delete from film where id = :id").setParameter("id", film.getId())
            .executeUpdate();
        entityManager.clear();
        assertThat(references.findById(reference.getId())).isEmpty();
    }

    @Test
    void theBudgetSpendsAtomicallyAndKeepsTheResetTime() {
        LocalDate day = LocalDate.of(2024, 3, 31);
        Instant reset = Instant.parse("2024-04-01T00:00:00Z");

        assertThat(budget.take(day, 2)).isTrue();
        assertThat(budget.take(day, 2)).isTrue();
        assertThat(budget.take(day, 2)).isFalse();
        budget.refund(day);
        assertThat(budget.take(day, 2)).isTrue();
        budget.exhaust(day, 2, reset);

        assertThat(budget.blockedUntil(reset.minusSeconds(1))).isEqualTo(reset);
        assertThat(budget.blockedUntil(reset)).isNull();
    }

    @Test
    void theBestResultQueryReturnsOnlyFilmsSearchedUnderTheirCurrentImdbId() {
        Film film = saved();
        film.setOsSearchedImdbId("tt0936501");
        film.setOsForcedCount(2);
        film.setOsBest("{\"id\":1,\"fileId\":2,\"fileCount\":1}");
        films.saveAndFlush(film);

        assertThat(films.findOpenSubtitlesBest()).hasSize(1);
        film.setOsSearchedImdbId("tt9999999");
        films.saveAndFlush(film);
        assertThat(films.findOpenSubtitlesBest()).isEmpty();
    }

    // ---- V18: candidates, rules version, best file id and the ledger of candidates ----

    @Autowired
    private ForcedCheckCandidateRepository candidates;

    @Autowired
    private SidecarCheckService checkService;

    @Test
    void theCandidatesJsonRoundTripsAtFullLengthAndAnImdbIdChangeForgetsIt() {
        Film film = saved();
        OpenSubtitlesResult first = new OpenSubtitlesResult(1, 11, "a".repeat(200), 23.976, 5, true, false, null, null,
            1, "b".repeat(200));
        OpenSubtitlesResult second = new OpenSubtitlesResult(2, 22, "Rings.Extended", null, 0, false, true, null, null);
        String json = OpenSubtitlesResult.listToJson(java.util.List.of(first, second));
        films.saveOpenSubtitles(film.getId(), "tt0936501", 2, first.toJson(), json, NOW);
        entityManager.clear();

        Film loaded = films.findById(film.getId()).orElseThrow();

        assertThat(loaded.getOsCandidates()).isEqualTo(json);
        assertThat(OpenSubtitlesResult.listFromJson(loaded.getOsCandidates())).extracting(OpenSubtitlesResult::fileId)
            .containsExactly(11L, 22L);
        loaded.setOsCandidates("x".repeat(9000));
        films.saveAndFlush(loaded);
        entityManager.clear();
        assertThat(films.findById(film.getId()).orElseThrow().getOsCandidates()).hasSize(9000);
        Film changed = films.findById(film.getId()).orElseThrow();
        changed.setImdbId("tt0000002");
        assertThat(changed.getOsCandidates()).isNull();
    }

    @Test
    void theDueQueryAndTheBestQueryUnderstandCandidates() {
        Film film = saved();
        films.saveOpenSubtitles(film.getId(), "tt0936501", 2, "{\"id\":1,\"fileId\":2,\"fileCount\":1}", "[]", NOW);
        entityManager.clear();
        assertThat(films.findOpenSubtitlesDue(NOW.minusSeconds(1))).isEmpty();
        assertThat(films.findOpenSubtitlesBest()).singleElement()
            .satisfies(ref -> assertThat(ref.osCandidates()).isEqualTo("[]"));

        Film legacy = films.findById(film.getId()).orElseThrow();
        legacy.setOsCandidates(null);
        films.saveAndFlush(legacy);
        entityManager.clear();

        assertThat(films.findOpenSubtitlesDue(NOW.minusSeconds(1))).extracting(FilmRepository.ImdbRef::id)
            .containsExactly(film.getId());
    }

    @Test
    void aCheckRowMadeWithNoRulesVersionOrBestFileReadsAsVersionOneWithNoBestFile() {
        Film film = saved();
        OpenSubtitlesReference reference = reference(film, 5011);
        Long editionId = film.getEditions().get(0).getId();
        entityManager.createNativeQuery("insert into forced_subtitle_check (edition_id, served_version, reference_id,"
            + " verdict, matched_share, served_count, reference_count, best_offset_ms, reasons, checked_at) values "
            + "(:edition, 'v', :reference, 'AGREES', 1.0, 1, 1, 0, '', :at)")
            .setParameter("edition", editionId).setParameter("reference", reference.getId()).setParameter("at", NOW)
            .executeUpdate();
        entityManager.clear();

        ForcedSubtitleCheck loaded = checks.findByEditionId(editionId).orElseThrow();

        assertThat(loaded.getRulesVersion()).isEqualTo(1);
        assertThat(loaded.getBestFileId()).isNull();
        assertThat(loaded.isCurrent("v", "tt0936501", reference, 5011)).isFalse();
    }

    @Test
    void aSavedCheckCarriesTheCurrentRulesVersionAndTheBestFileId() {
        Film film = saved();
        ForcedSubtitleCheck row = check(film, reference(film, 5011));
        row.setBestFileId(4000L);
        checks.saveAndFlush(row);
        entityManager.clear();

        ForcedSubtitleCheck loaded = checks.findByEditionId(row.getEditionId()).orElseThrow();

        assertThat(loaded.getRulesVersion()).isEqualTo(ForcedSubtitleCheck.RULES_VERSION);
        assertThat(loaded.getBestFileId()).isEqualTo(4000L);
    }

    @Test
    void theLedgerOfCandidatesIsPersistedOncePerEditionImdbIdAndFileAndCapsAtThree() {
        Film film = saved();
        Long editionId = film.getEditions().get(0).getId();

        checkService.markTried(editionId, "tt0936501", 5011, NOW);
        checkService.markTried(editionId, "tt0936501", 5011, NOW);
        checkService.markTried(editionId, "tt0936501", 5012, NOW);
        checkService.markTried(editionId, "tt0936501", 5013, NOW);
        checkService.markTried(editionId, "tt0000002", 5011, NOW);
        entityManager.flush();
        entityManager.clear();

        List<ForcedCheckCandidate> rows = candidates.findByEditionIdIn(List.of(editionId));
        assertThat(rows).hasSize(4);
        List<ForcedCheckCandidate> forFilm = rows.stream().filter(r -> r.getImdbId().equals("tt0936501")).toList();
        assertThat(forFilm).extracting(ForcedCheckCandidate::getOsFileId).containsExactlyInAnyOrder(5011L, 5012L,
            5013L);
        assertThat(forFilm).hasSize(ReferenceWalk.MAX_CANDIDATES);
        assertThat(candidates.existsByEditionIdAndImdbIdAndOsFileId(editionId, "tt0936501", 5011)).isTrue();
        assertThat(candidates.existsByEditionIdAndImdbIdAndOsFileId(editionId, "tt0936501", 5014)).isFalse();
    }

    @Test
    void theLedgerKeepsOneRowPerKeyAndGoesWithItsEdition() {
        Film film = saved(false);
        Long editionId = film.getEditions().get(0).getId();
        ForcedCheckCandidate row = new ForcedCheckCandidate();
        row.setEditionId(editionId);
        row.setImdbId("tt0936501");
        row.setOsFileId(5011);
        row.setTriedAt(NOW);
        candidates.saveAndFlush(row);
        ForcedCheckCandidate duplicate = new ForcedCheckCandidate();
        duplicate.setEditionId(editionId);
        duplicate.setImdbId("tt0936501");
        duplicate.setOsFileId(5011);
        duplicate.setTriedAt(NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> candidates.saveAndFlush(duplicate))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        entityManager.clear();
        editions.deleteByIds(List.of(editionId));
        entityManager.clear();

        assertThat(candidates.findByEditionIdIn(List.of(editionId))).isEmpty();
    }
}
