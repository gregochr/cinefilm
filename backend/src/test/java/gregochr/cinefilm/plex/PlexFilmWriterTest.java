package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.plex.PlexFilmWriter.Applied;
import gregochr.cinefilm.plex.PlexFilmWriter.FilmWrite;
import gregochr.cinefilm.plex.PlexFilmWriter.Preflight;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.tmdb.TmdbModels;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One Plex film's transaction, on H2 through the real repositories. */
class PlexFilmWriterTest extends ApiTestBase {

    private static final Instant ADDED = NOW.minus(Duration.ofDays(100));
    private static final Instant PLEX_ADDED = NOW.minus(Duration.ofDays(400));
    private static final Instant VIEWED = NOW.minus(Duration.ofDays(10));
    private static final String POSTER = "/api/art/" + "a".repeat(64) + ".jpg";
    private static final String BACKDROP = "/api/art/" + "b".repeat(64) + ".png";

    @Autowired
    private PlexFilmWriter writer;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private ArtworkStore artwork;

    @Autowired
    private EntityManager em;

    private Film saved(String title, Long tmdbId, String path, String... morePaths) {
        Film film = TestData.film(null, title, 1990, ADDED, "Old genre");
        film.setTmdbId(tmdbId);
        film.setPosterPath("/old.jpg");
        film.setMatchStatus(MatchStatus.NEEDS_MATCH);
        film.setMatchCandidates("[{\"tmdbId\":1}]");
        Edition edition = TestData.edition(film, null, "Original", 100 * MIN, null);
        TestData.file(edition, path);
        for (String more : morePaths) {
            TestData.file(TestData.edition(film, null, "Edition " + more, 90 * MIN, null), more);
        }
        return films.saveAndFlush(film);
    }

    private static Long fileId(Film film, int edition) {
        return film.getEditions().get(edition).getMediaFiles().get(0).getId();
    }

    private static PlexFilm plex(Long tmdbId, String title) {
        return new PlexFilm("9", title, "Sort " + title, 1995, "Plex overview", null, PLEX_ADDED, PLEX_ADDED, null,
            null, tmdbId, 0, 0, null, List.of("/m/x.m4v"));
    }

    private static PlexFilm watched(PlexFilm base, int views, long offset) {
        return new PlexFilm(base.ratingKey(), base.title(), base.titleSort(), base.year(), base.summary(),
            base.editionTitle(), base.addedAt(), base.updatedAt(), base.thumb(), base.art(), base.tmdbId(), views,
            offset, VIEWED, base.files());
    }

    private static MovieDetails tmdb(long id, String title) {
        return new MovieDetails(id, title, title, 1996, "TMDB overview", 120, List.of("Crime", "Drama"), "/tp.jpg",
            "/tb.jpg", 5, List.of(new TmdbModels.Credit(100, "Michael Mann", null, null)),
            List.of(new TmdbModels.Credit(200, "Al Pacino", null, "Vincent")), null);
    }

    private FilmWrite write(PlexFilm plex, List<Long> fileIds, MovieDetails movie) {
        return new FilmWrite(plex, fileIds, movie, null, null, NOW);
    }

    private Film reload(Long id) {
        em.flush();
        em.clear();
        return films.findById(id).orElseThrow();
    }

    @Test
    void plexsMetadataOverlaysTmdbsAndTheMatchIsSettled() {
        Film film = saved("heat file", null, "Heat.m4v");
        Long id = film.getId();
        PlexFilm plex = new PlexFilm("9", "Heat", "Heat, sorted", 1995, "Plex overview", "Director's Cut", PLEX_ADDED,
            PLEX_ADDED, null, null, 949L, 0, 0, null, List.of("/m/Heat.m4v"));

        Applied applied = writer.apply(new FilmWrite(plex, List.of(fileId(film, 0)), tmdb(949, "Heat (TMDB)"),
            POSTER, BACKDROP, NOW));

        Film result = reload(id);
        assertThat(applied).isEqualTo(new Applied(0, 0, 0, 0, 0));
        assertThat(result.getTmdbId()).isEqualTo(949L);
        assertThat(result.getTitle()).isEqualTo("Heat");
        assertThat(result.getSortTitle()).isEqualTo("heat, sorted");
        assertThat(result.getYear()).isEqualTo(1995);
        assertThat(result.getOverview()).isEqualTo("Plex overview");
        assertThat(result.getAddedAt()).isEqualTo(PLEX_ADDED);
        assertThat(result.getPosterPath()).isEqualTo(POSTER);
        assertThat(result.getBackdropPath()).isEqualTo(BACKDROP);
        assertThat(result.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(result.getMatchCandidates()).isNull();
        assertThat(result.getGenres()).containsExactlyInAnyOrder("Crime", "Drama");
        assertThat(result.getCredits()).extracting(c -> c.getPerson().getName()).containsExactly("Michael Mann",
            "Al Pacino");
        assertThat(result.getEditions().get(0).getName()).isEqualTo("Director's Cut");
    }

    @Test
    void withoutPlexArtworkTmdbsPosterStaysAsTheStartingPoint() {
        Film film = saved("heat file", null, "Heat.m4v");

        writer.apply(write(plex(949L, "Heat"), List.of(fileId(film, 0)), tmdb(949, "Heat")));

        Film result = reload(film.getId());
        assertThat(result.getPosterPath()).isEqualTo("/tp.jpg");
        assertThat(result.getBackdropPath()).isEqualTo("/tb.jpg");
    }

    @Test
    void aPlexFilmWithNoTitleOrYearOrSummaryKeepsWhatTheFilmHad() {
        Film film = saved("Kept title", 5L, "A.m4v");
        film.setOverview("Kept overview");
        PlexFilm bare = new PlexFilm(null, null, null, null, null, null, null, null, null, null, null, 0, 0, null,
            List.of("/m/A.m4v"));

        writer.apply(write(bare, List.of(fileId(film, 0)), null));

        Film result = reload(film.getId());
        assertThat(result.getTitle()).isEqualTo("Kept title");
        assertThat(result.getYear()).isEqualTo(1990);
        assertThat(result.getOverview()).isEqualTo("Kept overview");
        assertThat(result.getAddedAt()).isEqualTo(ADDED);
        assertThat(result.getPosterPath()).isEqualTo("/old.jpg");
        assertThat(result.getEditions().get(0).getName()).isEqualTo("Original");
        assertThat(result.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
    }

    @Test
    void aFilmWithNoPlexTmdbGuidKeepsItsOwnMatchAndCredits() {
        Film film = saved("Dune", 841L, "Dune.m4v");
        Credit credit = TestData.credit(film, people.save(TestData.person(null, "Someone")),
            gregochr.cinefilm.film.CreditRole.DIRECTOR, null, 0);
        films.saveAndFlush(film);

        writer.apply(write(plex(null, "Dune (Plex)"), List.of(fileId(film, 0)), null));

        Film result = reload(film.getId());
        assertThat(credit).isNotNull();
        assertThat(result.getTmdbId()).isEqualTo(841L);
        assertThat(result.getCredits()).hasSize(1);
        assertThat(result.getTitle()).isEqualTo("Dune (Plex)");
        assertThat(result.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(result.getMatchCandidates()).isNull();
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void aTmdbOutageStillGivesTheFilmItsTmdbIdAndPlexMetadataAndIsFetchedAgainNextTime() {
        Film film = saved("heat file", null, "Heat.m4v");
        List<Long> ids = List.of(fileId(film, 0));

        writer.apply(write(plex(949L, "Heat"), ids, null));

        Film result = reload(film.getId());
        assertThat(result.getTmdbId()).isEqualTo(949L);
        assertThat(result.getTitle()).isEqualTo("Heat");
        assertThat(result.getCredits()).isEmpty();
        assertThat(writer.preflight(ids, 949L).needsTmdb()).isTrue();
    }

    @Test
    void tmdbIsNotNeededOnceAFilmWithThatIdHasCredits() {
        Film film = saved("heat file", null, "Heat.m4v");
        List<Long> ids = List.of(fileId(film, 0));
        writer.apply(write(plex(949L, "Heat"), ids, tmdb(949, "Heat")));
        em.flush();
        em.clear();

        assertThat(writer.preflight(ids, 949L).needsTmdb()).isFalse();
        assertThat(writer.preflight(ids, null).needsTmdb()).isFalse();
        assertThat(writer.preflight(ids, 950L).needsTmdb()).isTrue();
    }

    @Test
    void preflightSeesStoredPlexArtworkThatIsReallyOnDiskAndOnlyThat() {
        Film film = saved("heat file", null, "Heat.m4v");
        List<Long> ids = List.of(fileId(film, 0));
        String poster = artwork.store(new byte[] {1, 2, 3}, "image/jpeg");
        film.setPosterPath(poster);
        film.setBackdropPath(BACKDROP);
        films.saveAndFlush(film);
        em.flush();
        em.clear();

        Preflight pre = writer.preflight(ids, null);

        assertThat(pre.hasPoster()).isTrue();
        assertThat(pre.hasBackdrop()).isFalse();
    }

    @Test
    void twoFilmsWithThePlexTmdbIdAreMergedIntoOneWithPreferencesAndPlaybackKept() {
        Film first = saved("Heat", 949L, "Heat.m4v");
        Film second = saved("Heat again", 949L, "Heat (Director's Cut).m4v");
        Edition secondEdition = second.getEditions().get(0);
        saveState(TestData.state(secondEdition, 33 * MIN, false, VIEWED, null));
        FilmPreference preference = new FilmPreference(second.getId());
        preference.setLastEditionId(secondEdition.getId());
        preference.setQuality("original");
        preferences.save(preference);
        Long firstFileId = fileId(first, 0);
        Long secondFileId = fileId(second, 0);
        Long secondEditionId = secondEdition.getId();

        Applied applied = writer.apply(write(plex(949L, "Heat (Plex)"), List.of(firstFileId, secondFileId),
            tmdb(949, "Heat")));

        em.flush();
        em.clear();
        assertThat(applied.merged()).isEqualTo(1);
        assertThat(films.findAll()).singleElement().satisfies(film -> {
            assertThat(film.getId()).isEqualTo(first.getId());
            assertThat(film.getTitle()).isEqualTo("Heat (Plex)");
            assertThat(film.getEditions()).hasSize(2);
        });
        assertThat(states.findById(secondEditionId).orElseThrow().getPositionMs()).isEqualTo(33 * MIN);
        assertThat(preferences.findById(first.getId()).orElseThrow().getQuality()).isEqualTo("original");
        assertThat(preferences.findById(second.getId())).isEmpty();
    }

    @Test
    void anEditionKeepsItsOwnFilmWhenPlexAgreesItBelongsThere() {
        Film film = saved("Heat", 949L, "Heat.m4v");

        Applied applied = writer.apply(write(plex(949L, "Heat"), List.of(fileId(film, 0)), null));

        assertThat(applied.merged()).isZero();
        assertThat(applied.editionsMoved()).isZero();
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void anEditionInAFilmWithADifferentIdentityMovesToAnExistingFilmWithThePlexId() {
        Film heat = saved("Heat", 949L, "Heat.m4v");
        Film wrong = saved("Wrong", 111L, "Other.m4v", "Heat extra.m4v");
        Edition extra = wrong.getEditions().get(1);
        Long extraFileId = extra.getMediaFiles().get(0).getId();
        Long extraEditionId = extra.getId();

        Applied applied = writer.apply(write(plex(949L, "Heat"), List.of(extraFileId), null));

        em.flush();
        em.clear();
        assertThat(applied.editionsMoved()).isEqualTo(1);
        assertThat(applied.merged()).isZero();
        assertThat(editions.findById(extraEditionId).orElseThrow().getFilm().getId()).isEqualTo(heat.getId());
        assertThat(films.findById(wrong.getId()).orElseThrow().getEditions()).hasSize(1);
        assertThat(films.count()).isEqualTo(2);
    }

    @Test
    void anEditionInAFilmWithADifferentIdentityMovesToANewFilmWhenNoFilmHasThePlexId() {
        Film wrong = saved("Wrong", 111L, "Other.m4v", "Heat extra.m4v");
        Edition extra = wrong.getEditions().get(1);
        Long extraFileId = extra.getMediaFiles().get(0).getId();
        Long extraEditionId = extra.getId();

        Applied applied = writer.apply(write(plex(949L, "Heat"), List.of(extraFileId), tmdb(949, "Heat")));

        em.flush();
        em.clear();
        assertThat(applied.editionsMoved()).isEqualTo(1);
        Film created = editions.findById(extraEditionId).orElseThrow().getFilm();
        assertThat(created.getId()).isNotEqualTo(wrong.getId());
        assertThat(created.getTmdbId()).isEqualTo(949L);
        assertThat(created.getTitle()).isEqualTo("Heat");
        assertThat(created.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(created.getCredits()).hasSize(2);
        assertThat(films.findById(wrong.getId()).orElseThrow().getTmdbId()).isEqualTo(111L);
        assertThat(films.count()).isEqualTo(2);
    }

    @Test
    void aNewFilmForAPlexFilmWithoutATitleOrAddedDateIsStillValid() {
        Film wrong = saved("Wrong", 111L, "Other.m4v");
        PlexFilm bare = new PlexFilm(null, null, null, null, null, null, null, null, null, null, 949L, 0, 0, null,
            List.of("/m/Other.m4v"));

        writer.apply(write(bare, List.of(fileId(wrong, 0)), null));

        em.flush();
        em.clear();
        assertThat(films.findAll()).extracting(Film::getTitle).containsExactlyInAnyOrder("Untitled");
        assertThat(films.findAll()).singleElement().satisfies(f -> {
            assertThat(f.getAddedAt()).isEqualTo(NOW);
            assertThat(f.getTmdbId()).isEqualTo(949L);
        });
    }

    @Test
    void aFilmLeftWithNoEditionsIsDeletedAndItsPreferencesMoveOn() {
        Film wrong = saved("Wrong", 111L, "Heat.m4v");
        FilmPreference preference = new FilmPreference(wrong.getId());
        preference.setSubtitle("English");
        preferences.save(preference);

        writer.apply(write(plex(949L, "Heat"), List.of(fileId(wrong, 0)), null));

        em.flush();
        em.clear();
        assertThat(films.findById(wrong.getId())).isEmpty();
        Film created = films.findAll().get(0);
        assertThat(films.count()).isEqualTo(1);
        assertThat(preferences.findById(created.getId()).orElseThrow().getSubtitle()).isEqualTo("English");
    }

    @Test
    void aNoGuidFilmPairingEditionsFromTwoFilmsGathersThemUnderTheFirst() {
        Film first = saved("One", 1L, "One.m4v");
        Film second = saved("Two", 2L, "Two.m4v");

        Applied applied = writer.apply(write(plex(null, "Both"), List.of(fileId(first, 0), fileId(second, 0)),
            null));

        em.flush();
        em.clear();
        assertThat(applied.editionsMoved()).isEqualTo(1);
        assertThat(films.findAll()).singleElement().satisfies(f -> assertThat(f.getEditions()).hasSize(2));
    }

    @Test
    void anAnchorFilmWithNoTmdbIdTakesThePlexIdWhenNoFilmHasIt() {
        Film pending = saved("pending file", null, "P.m4v");

        writer.apply(write(plex(77L, "Plex Title"), List.of(fileId(pending, 0)), tmdb(77, "T")));

        Film result = reload(pending.getId());
        assertThat(result.getTmdbId()).isEqualTo(77L);
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void watchedAndResumeAndKeptNewerAreAppliedPerEditionAndCounted() {
        Film film = saved("Heat", 949L, "Heat.m4v", "Heat cut.m4v", "Heat old.m4v");
        Edition third = film.getEditions().get(2);
        saveState(TestData.state(third, 5 * MIN, false, VIEWED.plusSeconds(1), null));
        List<Long> ids = List.of(film.getEditions().get(0).getMediaFiles().get(0).getId(),
            film.getEditions().get(1).getMediaFiles().get(0).getId(),
            film.getEditions().get(2).getMediaFiles().get(0).getId());
        Long firstEdition = film.getEditions().get(0).getId();
        Long secondEdition = film.getEditions().get(1).getId();
        Long thirdEdition = film.getEditions().get(2).getId();

        Applied applied = writer.apply(write(watched(plex(949L, "Heat"), 1, 40 * MIN), ids, null));

        em.flush();
        em.clear();
        assertThat(applied).isEqualTo(new Applied(0, 0, 2, 1, 1));
        PlaybackState one = states.findById(firstEdition).orElseThrow();
        assertThat(one.isWatched()).isTrue();
        assertThat(one.getPositionMs()).isEqualTo(40 * MIN);
        assertThat(one.getWatchedAt()).isEqualTo(VIEWED);
        assertThat(one.getLastPlayedAt()).isEqualTo(VIEWED);
        PlaybackState two = states.findById(secondEdition).orElseThrow();
        assertThat(two.isWatched()).isTrue();
        assertThat(two.getPositionMs()).isZero();
        PlaybackState three = states.findById(thirdEdition).orElseThrow();
        assertThat(three.isWatched()).isFalse();
        assertThat(three.getPositionMs()).isEqualTo(5 * MIN);
    }

    @Test
    void aFilmPlexNeverPlayedGetsNoPlaybackRows() {
        Film film = saved("Heat", 949L, "Heat.m4v");

        writer.apply(write(plex(949L, "Heat"), List.of(fileId(film, 0)), null));

        assertThat(states.count()).isZero();
    }

    @Test
    void runningTheSameFilmAgainMakesNoNewRowsAndChangesNothing() {
        Film film = saved("Heat", 949L, "Heat.m4v");
        List<Long> ids = List.of(fileId(film, 0));
        FilmWrite write = new FilmWrite(watched(plex(949L, "Heat"), 1, 40 * MIN), ids, tmdb(949, "Heat"), POSTER,
            BACKDROP, NOW);
        writer.apply(write);
        em.flush();
        em.clear();
        long filmRows = films.count();
        long stateRows = states.count();
        Film before = films.findById(film.getId()).orElseThrow();
        String snapshot = before.getTitle() + before.getSortTitle() + before.getOverview() + before.getAddedAt()
            + before.getPosterPath() + before.getYear();
        PlaybackState stateBefore = states.findById(film.getEditions().get(0).getId()).orElseThrow();
        Instant lastPlayed = stateBefore.getLastPlayedAt();
        em.clear();

        Applied second = writer.apply(new FilmWrite(write.plex(), ids, null, null, null, NOW.plusSeconds(60)));

        em.flush();
        em.clear();
        assertThat(second).isEqualTo(new Applied(0, 0, 0, 0, 0));
        assertThat(films.count()).isEqualTo(filmRows);
        assertThat(states.count()).isEqualTo(stateRows);
        Film after = films.findById(film.getId()).orElseThrow();
        assertThat(after.getTitle() + after.getSortTitle() + after.getOverview() + after.getAddedAt()
            + after.getPosterPath() + after.getYear()).isEqualTo(snapshot);
        assertThat(states.findById(film.getEditions().get(0).getId()).orElseThrow().getLastPlayedAt())
            .isEqualTo(lastPlayed);
    }

    @Test
    void mediaFilesAreTheOnesGivenInPlexOrderSoTheFirstListedCarriesTheResumePoint() {
        Film film = saved("Heat", 949L, "Heat.m4v", "Heat cut.m4v");
        Edition second = film.getEditions().get(1);
        MediaFile cutFile = second.getMediaFiles().get(0);
        Long firstEdition = film.getEditions().get(0).getId();
        Long secondEdition = second.getId();

        writer.apply(write(watched(plex(949L, "Heat"), 0, 30 * MIN),
            List.of(cutFile.getId(), film.getEditions().get(0).getMediaFiles().get(0).getId()), null));

        em.flush();
        em.clear();
        assertThat(states.findById(secondEdition).orElseThrow().getPositionMs()).isEqualTo(30 * MIN);
        assertThat(states.findById(firstEdition)).isEmpty();
    }

    @Test
    void anExistingOlderStateIsUpdatedInPlaceNotDuplicated() {
        Film film = saved("Heat", 949L, "Heat.m4v");
        Edition edition = film.getEditions().get(0);
        saveState(TestData.state(edition, 3 * MIN, false, VIEWED.minusSeconds(60), null));
        Long editionId = edition.getId();
        long rows = states.count();

        Applied applied = writer.apply(write(watched(plex(949L, "Heat"), 1, 0), List.of(fileId(film, 0)), null));

        em.flush();
        em.clear();
        assertThat(applied).isEqualTo(new Applied(0, 0, 1, 0, 0));
        assertThat(states.count()).isEqualTo(rows);
        PlaybackState state = states.findById(editionId).orElseThrow();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getPositionMs()).isZero();
        assertThat(state.getLastPlayedAt()).isEqualTo(VIEWED);
    }

    @Test
    void anEditionPlexDoesNotPairIsLeftUntouchedByTheWatchedState() {
        Film film = saved("Heat", 949L, "Heat.m4v", "Heat other.m4v");
        Long otherEdition = film.getEditions().get(1).getId();
        Long pairedEdition = film.getEditions().get(0).getId();

        writer.apply(write(watched(plex(949L, "Heat"), 1, 0), List.of(fileId(film, 0)), null));

        em.flush();
        em.clear();
        assertThat(states.findById(pairedEdition)).isPresent();
        assertThat(states.findById(otherEdition)).isEmpty();
    }

    @Test
    void localArtworkSurvivesATmdbRetryThatFillsInTheCredits() {
        Film film = saved("heat file", null, "Heat.m4v");
        List<Long> ids = List.of(fileId(film, 0));
        writer.apply(new FilmWrite(plex(949L, "Heat"), ids, null, POSTER, BACKDROP, NOW));
        em.flush();
        em.clear();
        assertThat(writer.preflight(ids, 949L)).isEqualTo(new Preflight(true, false, false));
        Film stored = films.findById(film.getId()).orElseThrow();
        stored.setPosterPath(artwork.store(new byte[] {4, 4}, "image/jpeg"));
        stored.setBackdropPath(artwork.store(new byte[] {5, 5}, "image/jpeg"));
        String poster = stored.getPosterPath();
        String backdrop = stored.getBackdropPath();
        films.saveAndFlush(stored);
        em.clear();
        assertThat(writer.preflight(ids, 949L)).isEqualTo(new Preflight(true, true, true));

        writer.apply(write(plex(949L, "Heat"), ids, tmdb(949, "Heat")));

        Film result = reload(film.getId());
        assertThat(result.getCredits()).hasSize(2);
        assertThat(result.getPosterPath()).isEqualTo(poster);
        assertThat(result.getBackdropPath()).isEqualTo(backdrop);
    }

    @Test
    void tmdbArtworkStillFillsInWhenThereIsNoLocalArtwork() {
        Film film = saved("heat file", null, "Heat.m4v");

        writer.apply(write(plex(949L, "Heat"), List.of(fileId(film, 0)), tmdb(949, "Heat")));

        assertThat(reload(film.getId()).getPosterPath()).isEqualTo("/tp.jpg");
    }

    @Test
    void aPlexFilmMovedOffASharedAnchorIsJudgedOnItsOwnDestinationNotTheAnchorsArtwork() {
        Film anchor = saved("Heat", 949L, "Heat.m4v", "Heat extra.m4v");
        String poster = artwork.store(new byte[] {6, 6}, "image/jpeg");
        String backdrop = artwork.store(new byte[] {7, 7}, "image/jpeg");
        anchor.setPosterPath(poster);
        anchor.setBackdropPath(backdrop);
        films.saveAndFlush(anchor);
        List<Long> first = List.of(fileId(anchor, 0));
        List<Long> second = List.of(fileId(anchor, 1));
        em.flush();
        em.clear();

        assertThat(writer.preflight(first, 949L)).isEqualTo(new Preflight(true, true, true));
        // The second Plex film (another TMDB id) will move to a new film: it has no artwork of its own yet.
        assertThat(writer.preflight(second, 111L)).isEqualTo(new Preflight(true, false, false));
        // With no TMDB guid it stays in the anchor and shares its artwork.
        assertThat(writer.preflight(second, null)).isEqualTo(new Preflight(false, true, true));
    }

    @Test
    void aFilmWithAnIdAnotherFilmHoldsIsJudgedOnThatFilm() {
        Film holder = saved("Heat", 949L, "Heat.m4v");
        String poster = artwork.store(new byte[] {8, 8}, "image/jpeg");
        holder.setPosterPath(poster);
        films.saveAndFlush(holder);
        Film other = saved("other", null, "Other.m4v");
        em.flush();
        em.clear();

        Preflight pre = writer.preflight(List.of(fileId(other, 0)), 949L);

        assertThat(pre.hasPoster()).isTrue();
        assertThat(pre.hasBackdrop()).isFalse();
    }

    @Test
    void storedArtworkPathsListsOnlyPathsIntoTheStore() {
        Film one = saved("One", null, "One.m4v");
        one.setPosterPath("/api/art/" + "a".repeat(64) + ".jpg");
        one.setBackdropPath("/tb.jpg");
        Film two = saved("Two", null, "Two.m4v");
        two.setBackdropPath("/api/art/" + "b".repeat(64) + ".png");
        films.saveAndFlush(one);
        films.saveAndFlush(two);

        assertThat(writer.storedArtworkPaths()).containsExactlyInAnyOrder("/api/art/" + "a".repeat(64) + ".jpg",
            "/api/art/" + "b".repeat(64) + ".png");
    }

    @Test
    void theResumePositionLandsOnTheEditionThatSurvivesTheContinueWatchingTieBreak() {
        Film film = saved("Heat", 949L, "Heat.m4v", "Heat cut.m4v");
        Edition higher = film.getEditions().get(1);
        Long higherFile = higher.getMediaFiles().get(0).getId();
        Long lowerFile = film.getEditions().get(0).getMediaFiles().get(0).getId();

        writer.apply(write(watched(plex(949L, "Heat"), 1, 30 * MIN), List.of(higherFile, lowerFile), null));

        em.flush();
        em.clear();
        Film result = films.findById(film.getId()).orElseThrow();
        var view = TestData.view(result, states.findAll().toArray(new PlaybackState[0]));
        assertThat(view.lastPlayedEdition().orElseThrow().getId()).isEqualTo(higher.getId());
        assertThat(view.progress().orElseThrow().positionMs()).isEqualTo(30 * MIN);
    }
}
