package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Which editions are due for a check against OpenSubtitles, and saving a result as the edition's one current row,
 * against the real repositories on H2.
 */
class SidecarCheckServiceTest extends SubtitleTestBase {

    private static final Instant LATER = NOW.plus(Duration.ofHours(1));

    @Autowired
    private SidecarCheckService service;

    @Autowired
    private ForcedSubtitleCheckRepository checks;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @Autowired
    private ForcedCheckCandidateRepository candidates;

    private static OpenSubtitlesResult result(long fileId, String release, Integer fileCount) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, release, 23.976, 10, true, false, null, null,
            fileCount);
    }

    private Film withCandidates(Film film, OpenSubtitlesResult... results) {
        film.setOsBest(results[0].toJson());
        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(results)));
        return films.saveAndFlush(film);
    }

    /** A film with one edition of {@code parts} files, an IMDb id and a best OpenSubtitles result for that id. */
    private Film searchedFilm(String title, int parts, String imdbId, long fileId) {
        Film film = filmWithParts(title, parts, 60);
        film.setImdbId(imdbId);
        film.setOsSearchedImdbId(imdbId);
        film.setOsForcedCount(3);
        film.setOsSearchedAt(NOW);
        return withCandidates(film, result(fileId, title + ".2008.BluRay", 1));
    }

    private OpenSubtitlesReference reference(Film film, long fileId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId(film.getImdbId());
        reference.setOsFileId(fileId);
        reference.setRelease("Taken.2008.BluRay");
        reference.setCuesText(SubtitleTestBase.CUES);
        reference.setCueCount(2);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    private static final Result AGREES = new Result(2, 2, 1.0, 0, Verdict.AGREES, List.of(), 2, false);

    private DueCheck onlyDue() {
        List<DueCheck> due = service.due();
        assertThat(due).hasSize(1);
        return due.get(0);
    }

    @Test
    void anEditionWithAServedSubtitleAndCandidatesIsDueWithEverythingTheCheckNeeds() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        DueCheck due = onlyDue();

        assertThat(due).isEqualTo(new DueCheck(film.getId(), film.getEditions().get(0).getId(), "tt0936501",
            "Original", List.of(result(5011, "Taken.2008.BluRay", 1)), Set.of(), "p" + fileId(film, 1) + "|"
            + row.getId() + "@0/0/" + ForcedSubtitleServedTest.fingerprint(CUES), false,
            List.of(new SubtitleCue(10_000, 12_000, "Hello"), new SubtitleCue(20_000, 22_500, "World"))));
    }

    @Test
    void aSplitEditionIsDueOnceWithTheStitchedCues() {
        Film film = searchedFilm("Towers", 2, "tt0167261", 7001);
        sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false).setOffsetMs(500);

        DueCheck due = onlyDue();

        assertThat(due.cues()).extracting(SubtitleCue::startMs).containsExactly(10_000L, 20_000L, 3_610_500L,
            3_620_500L);
    }

    @Test
    void aFilmWithNoBestResultIsNotDue() {
        Film unsearched = filmWithParts("Unsearched", 1, 60);
        unsearched.setImdbId("tt1");
        sidecar("Unsearched.en.forced.srt", fileId(unsearched, 1), false);
        Film nothingFound = searchedFilm("Nothing", 1, "tt2", 5002);
        nothingFound.setOsForcedCount(0);
        sidecar("Nothing.en.forced.srt", fileId(nothingFound, 1), false);
        Film noBest = searchedFilm("NoBest", 1, "tt3", 5003);
        noBest.setOsBest(null);
        sidecar("NoBest.en.forced.srt", fileId(noBest, 1), false);
        Film noImdb = filmWithParts("NoImdb", 1, 60);
        sidecar("NoImdb.en.forced.srt", fileId(noImdb, 1), false);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aBestResultFromASearchOfAnotherImdbIdDoesNotCount() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        film.setOsSearchedImdbId("tt9999999");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void candidatesThatAreNotUsableJsonLeaveNoCandidatesButAreNotAnError() {
        Film bad = searchedFilm("Bad", 1, "tt1", 5001);
        bad.setOsCandidates("{not json");
        sidecar("Bad.en.forced.srt", fileId(bad, 1), false);
        Film good = searchedFilm("Good", 1, "tt2", 5002);
        sidecar("Good.en.forced.srt", fileId(good, 1), false);

        assertThat(service.due()).extracting(DueCheck::filmId).containsExactlyInAnyOrder(bad.getId(), good.getId());
        assertThat(service.due().stream().filter(d -> d.filmId().equals(bad.getId())).findFirst().orElseThrow()
            .candidates()).isEmpty();
    }

    @Test
    void aFilmThatPlaybackServesNothingForIsNotDue() {
        Film noSidecar = searchedFilm("NoSidecar", 1, "tt1", 5001);
        Film missing = searchedFilm("Missing", 1, "tt2", 5002);
        sidecar("Missing.en.forced.srt", fileId(missing, 1), false).setPresence(Presence.MISSING);
        Film conflict = searchedFilm("Conflict", 1, "tt3", 5003);
        sidecar("Conflict.en.forced.srt", fileId(conflict, 1), false);
        sidecar("Conflict.forced.en.srt", fileId(conflict, 1), false);
        Film unmatched = searchedFilm("Unmatched", 1, "tt4", 5004);
        sidecar("Unmatched.en.forced.srt", null, false);

        assertThat(noSidecar.getId()).isNotNull();
        assertThat(unmatched.getId()).isNotNull();
        assertThat(service.due()).isEmpty();
    }

    @Test
    void anEditionWithACheckForTheVersionServedNowAndTheFilmsImdbIdIsNotDue() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference reference = reference(film, 5011);
        service.save(onlyDue(), reference, AGREES, LATER);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aNudgeAnAttachOrAChangedFileMakesTheCheckStaleAndTheEditionDueAgain() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        service.save(onlyDue(), reference(film, 5011), AGREES, LATER);

        row.setOffsetMs(250);
        row.setUpdatedAt(LATER);

        assertThat(service.due()).hasSize(1);
    }

    @Test
    void aCheckAgainstAReferenceFetchedForAnotherImdbIdIsStaleEvenWithTheSameVersion() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        service.save(onlyDue(), reference(film, 5011), AGREES, LATER);
        film.setImdbId("tt0000001");
        film.setOsSearchedImdbId("tt0000001");
        film.setOsForcedCount(1);
        withCandidates(film, new OpenSubtitlesResult(1, 6011, "Other", null, 0, false, false, null, null));

        DueCheck due = onlyDue();

        assertThat(due.imdbId()).isEqualTo("tt0000001");
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(6011L);
    }

    @Test
    void theOwnersUnverifiedFilesComeFirstThenTheVerifiedOnesThenByFilmAndEdition() {
        Film verified = searchedFilm("Alpha", 1, "tt1", 5001);
        SubtitleSidecar checked = sidecar("Alpha.en.forced.srt", fileId(verified, 1), false);
        checked.setOwnerVerifiedAt(NOW);
        checked.setVerifiedParts(String.valueOf(fileId(verified, 1)));
        Film second = searchedFilm("Beta", 1, "tt2", 5002);
        sidecar("Beta.en.forced.srt", fileId(second, 1), false);
        Film first = searchedFilm("Gamma", 1, "tt3", 5003);
        sidecar("Gamma.en.forced.srt", fileId(first, 1), false);

        List<DueCheck> due = service.due();

        assertThat(due).extracting(DueCheck::filmId).containsExactly(second.getId(), first.getId(),
            verified.getId());
        assertThat(due).extracting(DueCheck::ownerVerified).containsExactly(false, false, true);
    }

    @Test
    void savingCreatesTheEditionsOneRowWithEveryField() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);
        Result result = new Result(12, 41, 0.75, 1200, Verdict.CHECK,
            List.of("OpenSubtitles has 41 lines, this file has 12", "OpenSubtitles' file is 1.2 s later"), 9, false);

        boolean saved = service.save(due, reference, result, LATER);

        assertThat(saved).isTrue();
        List<ForcedSubtitleCheck> rows = checks.findAll();
        assertThat(rows).hasSize(1);
        ForcedSubtitleCheck row = rows.get(0);
        assertThat(row.getEditionId()).isEqualTo(due.editionId());
        assertThat(row.getServedVersion()).isEqualTo(due.servedVersion());
        assertThat(row.getReferenceId()).isEqualTo(reference.getId());
        assertThat(row.getVerdict()).isEqualTo("CHECK");
        assertThat(row.getMatchedShare()).isEqualTo(0.75);
        assertThat(row.getServedCount()).isEqualTo(12);
        assertThat(row.getReferenceCount()).isEqualTo(41);
        assertThat(row.getBestOffsetMs()).isEqualTo(1200);
        assertThat(row.getReasons()).isEqualTo("OpenSubtitles has 41 lines, this file has 12\n"
            + "OpenSubtitles' file is 1.2 s later");
        assertThat(row.reasonList()).containsExactly("OpenSubtitles has 41 lines, this file has 12",
            "OpenSubtitles' file is 1.2 s later");
        assertThat(row.getCheckedAt()).isEqualTo(LATER);
        assertThat(row.getRulesVersion()).isEqualTo(ForcedSubtitleCheck.RULES_VERSION);
    }

    @Test
    void savingAgainReplacesTheRowSoThereIsOneCurrentRowPerEdition() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        SubtitleSidecar sidecar = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        OpenSubtitlesReference reference = reference(film, 5011);
        service.save(onlyDue(), reference, AGREES, NOW);
        sidecar.setOffsetMs(300);
        DueCheck again = onlyDue();

        boolean saved = service.save(again, reference, new Result(2, 9, 0.5, 0, Verdict.CHECK,
            List.of("x"), 1, false), LATER);

        assertThat(saved).isTrue();
        List<ForcedSubtitleCheck> rows = checks.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getServedVersion()).isEqualTo(again.servedVersion());
        assertThat(rows.get(0).getVerdict()).isEqualTo("CHECK");
        assertThat(rows.get(0).getCheckedAt()).isEqualTo(LATER);
    }

    @Test
    void aResultWithNoReasonsIsStoredAndReadBackAsNone() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        service.save(onlyDue(), reference(film, 5011), AGREES, LATER);

        assertThat(checks.findAll().get(0).getReasons()).isEmpty();
        assertThat(checks.findAll().get(0).reasonList()).isEmpty();
    }

    @Test
    void aResultIsDroppedWhenTheEditionServesAnotherVersionByNowAndNothingIsStored() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        row.setOffsetMs(300);

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aResultIsDroppedWhenTheSidecarWasMovedToAnotherPartMeanwhile() {
        Film film = searchedFilm("Towers", 2, "tt1", 5001);
        SubtitleSidecar row = sidecar("Towers.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5001);

        row.setMediaFileId(fileId(film, 2));

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aResultIsDroppedWhenTheSidecarIsNoLongerServed() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        row.setPresence(Presence.MISSING);

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aResultIsDroppedWhenTheFilmsImdbIdChangedMeanwhile() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        film.setImdbId("tt0000002");
        films.saveAndFlush(film);

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aResultForAFilmOrEditionThatIsGoneIsDropped() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);
        DueCheck noFilm = new DueCheck(film.getId() + 999, due.editionId(), due.imdbId(), "Original",
            due.candidates(), Set.of(), due.servedVersion(), false, due.cues());
        DueCheck noEdition = new DueCheck(film.getId(), due.editionId() + 999, due.imdbId(), "Original",
            due.candidates(), Set.of(), due.servedVersion(), false, due.cues());

        assertThat(service.save(noFilm, reference, AGREES, LATER)).isFalse();
        assertThat(service.save(noEdition, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aFilmWhoseOnlyResultsAreSplitOverSeveralFilesIsDueWithNoCandidatesSoTheRefresherCanSkipIt() {
        Film film = searchedFilm("Towers", 1, "tt1", 5001);
        withCandidates(film, result(5001, "Extended", 2));
        sidecar("Towers.en.forced.srt", fileId(film, 1), false);

        assertThat(onlyDue().candidates()).isEmpty();
    }

    @Test
    void aFilmsOtherEditionWithNoServedSubtitleIsNotDue() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        gregochr.cinefilm.film.Edition extended = edition(film, null, "Extended", 120 * MIN, null);
        file(extended, "Taken - Extended.m4v").setDurationMs(120 * MIN);
        films.saveAndFlush(film);

        assertThat(onlyDue().editionId()).isEqualTo(film.getEditions().get(0).getId());
    }

    @Test
    void aCheckWhoseReferenceIsNoLongerOneOfTheFilmsCandidatesIsStaleAndTheEditionIsDueAgain() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        service.save(onlyDue(), reference(film, 5011), AGREES, LATER);
        assertThat(service.due()).isEmpty();

        withCandidates(film, result(6022, "Better", 1));

        assertThat(onlyDue().candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(6022L);
    }

    @Test
    void aCheckWhoseReferenceIsAnyOfTheFilmsCandidatesNotOnlyTheBestStaysCurrent() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        withCandidates(film, result(5011, "Taken", 1), result(5012, "Taken.Extended", 1));
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        service.save(onlyDue(), reference(film, 5012), AGREES, LATER);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aResultIsDroppedWhenItsReferenceIsNoLongerOneOfTheFilmsCandidatesButTheReferenceIsKept() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        withCandidates(film, result(6022, "Better", 1));

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
        assertThat(references.findById(reference.getId())).isPresent();
    }

    @Test
    void aResultIsDroppedWhenTheFilmHasNoCandidatesAnyMore() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        film.setOsCandidates(null);
        films.saveAndFlush(film);

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aResultIsDroppedWhenTheFilmHasNoBestResultAnyMoreEvenIfCandidatesRemain() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        film.setOsBest(null);
        films.saveAndFlush(film);

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
    }

    @Test
    void anUnreadableBestResultStillAllowsACheckAndRecordsNoBestFile() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        film.setOsBest("{not json");
        films.saveAndFlush(film);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        assertThat(service.save(onlyDue(), reference(film, 5011), AGREES, LATER)).isTrue();

        assertThat(checks.findAll().get(0).getBestFileId()).isZero();
    }

    @Test
    void aResultIsNotSavedAgainstAReferenceThatIsNowOnlyAMultiFileCandidate() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        DueCheck due = onlyDue();
        OpenSubtitlesReference reference = reference(film, 5011);

        withCandidates(film, result(5011, "Taken", 2));

        assertThat(service.save(due, reference, AGREES, LATER)).isFalse();
        assertThat(checks.findAll()).isEmpty();
    }

    @Test
    void aFilmSearchedBeforeItsCandidatesWereKeptIsNotDueUntilTheSearchWritesThemAgain() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        film.setOsCandidates(null);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        films.saveAndFlush(film);

        assertThat(service.due()).isEmpty();
    }

    @Test
    void renamingTheEditionSoItsCutTagsChangeMakesTheCheckStaleButKeepingTheTagDoesNot() {
        Film film = searchedFilm("Rings", 1, "tt0167260", 5011);
        film.getEditions().get(0).setName("Theatrical Cut");
        films.saveAndFlush(film);
        sidecar("Rings.en.forced.srt", fileId(film, 1), false);
        service.save(onlyDue(), reference(film, 5011), AGREES, LATER);
        assertThat(checks.findAll().get(0).getCutTags()).isEqualTo("THEATRICAL");
        assertThat(service.due()).isEmpty();

        film.getEditions().get(0).setName("Theatrical Cut (restored)");
        films.saveAndFlush(film);
        assertThat(service.due()).isEmpty();
        film.getEditions().get(0).setName("The Final Cut");
        films.saveAndFlush(film);
        assertThat(service.due()).hasSize(1);

        film.getEditions().get(0).setName("Theatrical Cut");
        films.saveAndFlush(film);
        assertThat(service.due()).isEmpty();
        film.getEditions().get(0).setName("Extended Edition");
        films.saveAndFlush(film);
        assertThat(onlyDue().editionName()).isEqualTo("Extended Edition");
    }

    @Test
    void aCheckMadeByOlderRulesIsStaleAndTheEditionIsDueAgain() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        service.save(onlyDue(), reference(film, 5011), AGREES, LATER);
        assertThat(service.due()).isEmpty();

        ForcedSubtitleCheck row = checks.findAll().get(0);
        row.setRulesVersion(ForcedSubtitleCheck.RULES_VERSION - 1);
        checks.saveAndFlush(row);

        assertThat(service.due()).hasSize(1);
    }

    @Test
    void theCandidatesAreOrderedForTheEditionsCutAndCarryWhatWasAlreadyTried() {
        Film film = searchedFilm("Rings", 1, "tt0167260", 5011);
        withCandidates(film, result(5011, "Rings.Theatrical.2003", 1), result(5012, "Rings.Extended.Edition.2003", 1),
            result(5013, "Rings.2003", 1));
        film.getEditions().get(0).setName("Extended Edition");
        films.saveAndFlush(film);
        Long editionId = film.getEditions().get(0).getId();
        sidecar("Rings.en.forced.srt", fileId(film, 1), false);
        service.markTried(editionId, "tt0167260", 5012, NOW);
        service.markTried(editionId, "tt0000001", 5011, NOW);

        DueCheck due = onlyDue();

        assertThat(due.editionName()).isEqualTo("Extended Edition");
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L, 5011L);
        assertThat(due.triedFileIds()).containsExactly(5012L);
    }

    @Test
    void markingACandidateTriedTwiceKeepsOneRow() {
        Film film = searchedFilm("Taken", 1, "tt0936501", 5011);
        Long editionId = film.getEditions().get(0).getId();

        service.markTried(editionId, "tt0936501", 5011, NOW);
        service.markTried(editionId, "tt0936501", 5011, LATER);

        assertThat(candidates.findByEditionIdIn(List.of(editionId))).singleElement().satisfies(row -> {
            assertThat(row.getOsFileId()).isEqualTo(5011L);
            assertThat(row.getImdbId()).isEqualTo("tt0936501");
            assertThat(row.getTriedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void reasonsAreJoinedWholeAndNeverPastTheColumn() {
        String long900 = "x".repeat(900);

        assertThat(SidecarCheckService.joined(List.of("a", "b"))).isEqualTo("a\nb");
        assertThat(SidecarCheckService.joined(List.of())).isEmpty();
        assertThat(SidecarCheckService.joined(List.of(long900, "y".repeat(99)))).hasSize(1000);
        assertThat(SidecarCheckService.joined(List.of(long900, "y".repeat(100)))).isEqualTo(long900);
        assertThat(SidecarCheckService.joined(List.of("z".repeat(1001), "short"))).isEmpty();
    }
}
