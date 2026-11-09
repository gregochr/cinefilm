package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.plex.PlexFilmWriter.Applied;
import gregochr.cinefilm.plex.PlexFilmWriter.FilmWrite;
import gregochr.cinefilm.plex.PlexFilmWriter.Preflight;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The writer's decisions with every collaborator faked: no Spring, no database. */
class PlexFilmWriterUnitTest {

    private static final Instant ADDED = NOW.minus(Duration.ofDays(100));
    private static final Instant VIEWED = NOW.minus(Duration.ofDays(10));
    private static final String LOCAL_POSTER = "/api/art/" + "a".repeat(64) + ".jpg";
    private static final String LOCAL_BACKDROP = "/api/art/" + "b".repeat(64) + ".jpg";
    private static final String NEW_POSTER = "/api/art/" + "c".repeat(64) + ".jpg";

    private final FilmRepository films = mock(FilmRepository.class);
    private final EditionRepository editions = mock(EditionRepository.class);
    private final MediaFileRepository files = mock(MediaFileRepository.class);
    private final PlaybackStateRepository states = mock(PlaybackStateRepository.class);
    private final LibraryIngest ingest = mock(LibraryIngest.class);
    private final ArtworkStore artwork = mock(ArtworkStore.class);
    private final PlexFilmWriter writer = new PlexFilmWriter(films, editions, files, states, ingest, artwork);

    private Film first;
    private Film second;
    private Edition firstEdition;
    private Edition secondEdition;
    private MediaFile firstFile;
    private MediaFile secondFile;

    @BeforeEach
    void setUp() {
        first = TestData.film(1L, "Heat", 1995, ADDED);
        first.setTmdbId(949L);
        firstEdition = TestData.edition(first, 10L, "Original", 100 * MIN, null);
        firstFile = TestData.file(firstEdition, "Heat.m4v");
        firstFile.setId(100L);
        second = TestData.film(2L, "Heat again", 1995, ADDED);
        second.setTmdbId(949L);
        secondEdition = TestData.edition(second, 20L, "Cut", 100 * MIN, null);
        secondFile = TestData.file(secondEdition, "Heat cut.m4v");
        secondFile.setId(200L);
        when(files.findById(100L)).thenReturn(Optional.of(firstFile));
        when(files.findById(200L)).thenReturn(Optional.of(secondFile));
        when(files.findAllById(List.of(100L))).thenReturn(List.of(firstFile));
        when(files.findAllById(List.of(200L))).thenReturn(List.of(secondFile));
        when(files.findAllById(List.of(100L, 200L))).thenReturn(List.of(firstFile, secondFile));
        when(files.findAllById(List.of(200L, 100L))).thenReturn(List.of(secondFile, firstFile));
        when(editions.findById(10L)).thenReturn(Optional.of(firstEdition));
        when(editions.findById(20L)).thenReturn(Optional.of(secondEdition));
        when(states.findById(10L)).thenReturn(Optional.empty());
        when(states.findById(20L)).thenReturn(Optional.empty());
    }

    private static PlexFilm plex(Long tmdbId) {
        return new PlexFilm("9", "Heat (Plex)", "Heat, plex", 1995, "Plex overview", null, ADDED, ADDED, null, null,
            tmdbId, 0, 0, null, List.of("/m/Heat.m4v"));
    }

    private static PlexFilm viewed(Long tmdbId, int views, long offset, String editionTitle) {
        return new PlexFilm("9", "Heat (Plex)", "Heat, plex", 1995, "Plex overview", editionTitle, ADDED, ADDED, null,
            null, tmdbId, views, offset, VIEWED, List.of("/m/Heat.m4v"));
    }

    private static MovieDetails movie() {
        return new MovieDetails(949, "Heat", "Heat", 1995, "TMDB", 170, List.of(), "/tmdb-poster.jpg", "/tmdb-bd.jpg",
            1, List.of(), List.of(), null);
    }

    private FilmWrite write(PlexFilm plex, List<Long> fileIds, MovieDetails movie, String poster, String backdrop) {
        return new FilmWrite(plex, fileIds, movie, poster, backdrop, NOW);
    }

    private void survivorAfterwards(Film film) {
        when(films.findById(film.getId())).thenReturn(Optional.of(film));
    }

    // ---- preflight: which downloads are skipped, which fetches are needed -----------------------------------------

    @Test
    void artworkAlreadyStoredOnTheDestinationIsSkippedAndMissingArtworkIsNeeded() {
        first.setPosterPath(LOCAL_POSTER);
        first.setBackdropPath("/tmdb-bd.jpg");
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        when(artwork.exists(LOCAL_POSTER)).thenReturn(true);
        when(artwork.exists("/tmdb-bd.jpg")).thenReturn(false);

        Preflight pre = writer.preflight(List.of(100L), 949L);

        assertThat(pre.hasPoster()).isTrue();
        assertThat(pre.hasBackdrop()).isFalse();
    }

    @Test
    void theDestinationIsTheLowestIdFilmWithThePlexIdNotTheAnchor() {
        second.setPosterPath(LOCAL_POSTER);
        first.setPosterPath(null);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first, second));
        when(artwork.exists(null)).thenReturn(false);
        when(artwork.exists(LOCAL_POSTER)).thenReturn(true);

        // the anchor is the second film's file, but the survivor is the first: the first's (missing) art counts
        Preflight pre = writer.preflight(List.of(200L), 949L);

        assertThat(pre.hasPoster()).isFalse();
        verify(artwork, never()).exists(LOCAL_POSTER);
    }

    @Test
    void aFilmWithNoTmdbGuidIsJudgedOnItsAnchorAndNeverNeedsTmdb() {
        first.setPosterPath(LOCAL_POSTER);
        when(artwork.exists(LOCAL_POSTER)).thenReturn(true);

        Preflight pre = writer.preflight(List.of(100L), null);

        assertThat(pre).isEqualTo(new Preflight(false, true, false));
        verify(films, never()).findByTmdbIdOrderByIdAsc(949L);
    }

    @Test
    void anAnchorWithADifferentIdentityMeansANewFilmWithNoArtworkYetAndTmdbNeeded() {
        first.setTmdbId(111L);
        first.setPosterPath(LOCAL_POSTER);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of());

        Preflight pre = writer.preflight(List.of(100L), 949L);

        assertThat(pre).isEqualTo(new Preflight(true, false, false));
        verify(artwork, never()).exists(LOCAL_POSTER);
    }

    @Test
    void anAnchorWithNoTmdbIdIsTheDestinationWhenNoFilmHasThePlexId() {
        first.setTmdbId(null);
        first.setBackdropPath(LOCAL_BACKDROP);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of());
        when(artwork.exists(LOCAL_BACKDROP)).thenReturn(true);

        assertThat(writer.preflight(List.of(100L), 949L)).isEqualTo(new Preflight(true, false, true));
    }

    @Test
    void tmdbIsSkippedOnlyWhenTheSurvivingFilmHasCredits() {
        TestData.credit(second, TestData.person(5L, "Someone"), CreditRole.DIRECTOR, null, 0);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first, second));

        // credits only on the later film, which merging deletes: still needed
        assertThat(writer.preflight(List.of(100L), 949L).needsTmdb()).isTrue();

        TestData.credit(first, TestData.person(6L, "Someone else"), CreditRole.DIRECTOR, null, 0);
        assertThat(writer.preflight(List.of(100L), 949L).needsTmdb()).isFalse();
    }

    @Test
    void aPreflightForAFileThatIsNotInTheDatabaseIsAnError() {
        when(files.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.preflight(List.of(999L), 949L)).isInstanceOf(NoSuchElementException.class);
    }

    // ---- apply: merge versus create, moves, TMDB, artwork, names, watching -----------------------------------------

    @Test
    void filmsSharingThePlexIdAreMergedIntoTheLowestIdAndNothingIsCreatedOrMovedTwice() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first, second));
        doAnswer(call -> {
            secondEdition.setFilm(first);
            first.getEditions().add(secondEdition);
            return null;
        }).when(ingest).merge(2L, 1L);
        survivorAfterwards(first);

        Applied applied = writer.apply(write(plex(949L), List.of(100L, 200L), null, null, null));

        assertThat(applied).isEqualTo(new Applied(1, 0, 0, 0, 0));
        verify(ingest).merge(2L, 1L);
        verify(films).findByTmdbIdOrderByIdAsc(949L);
        verify(films).findById(1L);
        verifyNoMoreInteractions(films);
        verify(editions).findById(10L);
        verify(editions).findById(20L);
        verifyNoMoreInteractions(editions);
    }

    @Test
    void anAnchorWithADifferentIdentityGetsANewFilmAndTheEditionMovesIntoItThenTheEmptyFilmGoes() {
        first.setTmdbId(111L);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of());
        Film created = TestData.film(3L, "Heat (Plex)", 1995, ADDED);
        when(films.saveAndFlush(any(Film.class))).thenAnswer(call -> {
            Film saved = call.getArgument(0);
            assertThat(saved.getTitle()).isEqualTo("Heat (Plex)");
            assertThat(saved.getSortTitle()).isEqualTo("heat, plex");
            assertThat(saved.getAddedAt()).isEqualTo(ADDED);
            assertThat(saved.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
            return created;
        });
        when(films.getReferenceById(3L)).thenReturn(created);
        when(editions.moveOne(10L, created)).thenAnswer(call -> {
            firstEdition.setFilm(created);
            created.getEditions().add(firstEdition);
            return 1;
        });
        when(editions.countByFilmId(1L)).thenReturn(0L);
        survivorAfterwards(created);

        Applied applied = writer.apply(write(plex(949L), List.of(100L), null, null, null));

        assertThat(applied).isEqualTo(new Applied(0, 1, 0, 0, 0));
        verify(editions).moveOne(10L, created);
        verify(ingest).merge(1L, 3L);
        assertThat(created.getTmdbId()).isEqualTo(949L);
    }

    @Test
    void aNewFilmForAPlexFilmWithNoTitleOrAddedDateFallsBackToUntitledAndTheImportTime() {
        first.setTmdbId(111L);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of());
        Film created = TestData.film(3L, "x", 1995, ADDED);
        ArgumentCaptor<Film> saved = ArgumentCaptor.forClass(Film.class);
        when(films.saveAndFlush(saved.capture())).thenReturn(created);
        when(films.getReferenceById(3L)).thenReturn(created);
        when(editions.countByFilmId(1L)).thenReturn(1L);
        survivorAfterwards(created);
        PlexFilm bare = new PlexFilm(null, null, null, null, null, null, null, null, null, null, 949L, 0, 0, null,
            List.of());

        writer.apply(write(bare, List.of(100L), null, null, null));

        assertThat(saved.getValue().getTitle()).isEqualTo("Untitled");
        assertThat(saved.getValue().getAddedAt()).isEqualTo(NOW);
        verify(ingest, never()).merge(1L, 3L);
    }

    @Test
    void anEditionAlreadyInTheSurvivorIsNotMoved() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);

        Applied applied = writer.apply(write(plex(949L), List.of(100L), null, null, null));

        assertThat(applied.editionsMoved()).isZero();
        verify(editions).findById(10L);
        verifyNoMoreInteractions(editions);
    }

    @Test
    void aFilmWithNoTmdbGuidStaysInItsAnchorAndGathersTheOtherPairedEditionsIntoIt() {
        when(films.getReferenceById(1L)).thenReturn(first);
        when(editions.moveOne(20L, first)).thenAnswer(call -> {
            secondEdition.setFilm(first);
            first.getEditions().add(secondEdition);
            return 1;
        });
        when(editions.countByFilmId(2L)).thenReturn(0L);
        survivorAfterwards(first);

        Applied applied = writer.apply(write(plex(null), List.of(100L, 200L), null, null, null));

        assertThat(applied).isEqualTo(new Applied(0, 1, 0, 0, 0));
        verify(ingest).merge(2L, 1L);
        verify(films, never()).findByTmdbIdOrderByIdAsc(949L);
        assertThat(first.getTmdbId()).isEqualTo(949L);
    }

    @Test
    void tmdbEnrichmentIsAppliedWhenFetchedAndSkippedWhenNot() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);
        MovieDetails details = movie();

        writer.apply(write(plex(949L), List.of(100L), details, null, null));
        verify(ingest).applyMovie(first, details);

        writer.apply(write(plex(949L), List.of(100L), null, null, null));
        verify(ingest, org.mockito.Mockito.times(1)).applyMovie(first, details);
        verifyNoMoreInteractions(ingest);
    }

    @Test
    void artworkAlreadyStoredSurvivesTmdbsOverwriteAndPlexsNewArtworkWinsOverBoth() {
        first.setPosterPath(LOCAL_POSTER);
        first.setBackdropPath(LOCAL_BACKDROP);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);
        MovieDetails details = movie();
        doAnswer(call -> {
            Film film = call.getArgument(0);
            film.setPosterPath("/tmdb-poster.jpg");
            film.setBackdropPath("/tmdb-bd.jpg");
            return null;
        }).when(ingest).applyMovie(first, details);

        writer.apply(write(plex(949L), List.of(100L), details, null, null));
        assertThat(first.getPosterPath()).isEqualTo(LOCAL_POSTER);
        assertThat(first.getBackdropPath()).isEqualTo(LOCAL_BACKDROP);

        writer.apply(write(plex(949L), List.of(100L), details, NEW_POSTER, null));
        assertThat(first.getPosterPath()).isEqualTo(NEW_POSTER);
        assertThat(first.getBackdropPath()).isEqualTo(LOCAL_BACKDROP);
    }

    @Test
    void tmdbArtworkIsKeptWhenThereIsNoLocalArtworkToPreserve() {
        first.setPosterPath(null);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);
        MovieDetails details = movie();
        doAnswer(call -> {
            Film film = call.getArgument(0);
            film.setPosterPath("/tmdb-poster.jpg");
            return null;
        }).when(ingest).applyMovie(first, details);

        writer.apply(write(plex(949L), List.of(100L), details, null, null));

        assertThat(first.getPosterPath()).isEqualTo("/tmdb-poster.jpg");
    }

    @Test
    void plexsMetadataOverlaysTheFilmAndTheMatchIsSettledWhateverItWas() {
        first.setMatchStatus(MatchStatus.NEEDS_MATCH);
        first.setMatchCandidates("[{}]");
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);

        writer.apply(write(viewed(949L, 0, 0, "Director's Cut"), List.of(100L), null, null, null));

        assertThat(first.getTitle()).isEqualTo("Heat (Plex)");
        assertThat(first.getSortTitle()).isEqualTo("heat, plex");
        assertThat(first.getOverview()).isEqualTo("Plex overview");
        assertThat(first.getAddedAt()).isEqualTo(ADDED);
        assertThat(first.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(first.getMatchCandidates()).isNull();
        assertThat(firstEdition.getName()).isEqualTo("Director's Cut");
    }

    @Test
    void aBarePlexFilmChangesNothingButTheMatch() {
        first.setMatchStatus(MatchStatus.PENDING);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);
        PlexFilm bare = new PlexFilm(null, null, null, null, null, null, null, null, null, null, 949L, 0, 0, null,
            List.of());

        writer.apply(write(bare, List.of(100L), null, null, null));

        assertThat(first.getTitle()).isEqualTo("Heat");
        assertThat(first.getYear()).isEqualTo(1995);
        assertThat(first.getOverview()).isNull();
        assertThat(first.getAddedAt()).isEqualTo(ADDED);
        assertThat(firstEdition.getName()).isEqualTo("Original");
        assertThat(first.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
    }

    @Test
    void anEditionTitleLongerThanTheColumnIsCut() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);

        writer.apply(write(viewed(949L, 0, 0, "E".repeat(400)), List.of(100L), null, null, null));

        assertThat(firstEdition.getName()).hasSize(255);
    }

    @Test
    void theResumePositionGoesToTheEditionPlexListsFirstAndEveryPairedEditionGetsTheView() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        doAnswer(call -> {
            secondEdition.setFilm(first);
            first.getEditions().add(secondEdition);
            return null;
        }).when(ingest).merge(2L, 1L);
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first, second));
        survivorAfterwards(first);

        // Plex lists the second edition's file first: it carries the offset
        Applied applied = writer.apply(write(viewed(949L, 1, 30 * MIN, null), List.of(200L, 100L), null, null,
            null));

        ArgumentCaptor<PlaybackState> saved = ArgumentCaptor.forClass(PlaybackState.class);
        verify(states, org.mockito.Mockito.times(2)).save(saved.capture());
        PlaybackState forFirst = saved.getAllValues().stream().filter(s -> s.getEditionId() == 10L).findFirst()
            .orElseThrow();
        PlaybackState forSecond = saved.getAllValues().stream().filter(s -> s.getEditionId() == 20L).findFirst()
            .orElseThrow();
        assertThat(forSecond.getPositionMs()).isEqualTo(30 * MIN);
        assertThat(forFirst.getPositionMs()).isZero();
        assertThat(forFirst.isWatched()).isTrue();
        assertThat(forSecond.isWatched()).isTrue();
        assertThat(forFirst.getLastPlayedAt()).isEqualTo(VIEWED);
        assertThat(applied).isEqualTo(new Applied(1, 0, 2, 1, 0));
    }

    @Test
    void anExistingNewerStateIsKeptAndCountedAndNotSaved() {
        PlaybackState newer = TestData.state(firstEdition, 5 * MIN, false, VIEWED.plusSeconds(60), null);
        when(states.findById(10L)).thenReturn(Optional.of(newer));
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);

        Applied applied = writer.apply(write(viewed(949L, 1, 0, null), List.of(100L), null, null, null));

        assertThat(applied).isEqualTo(new Applied(0, 0, 0, 0, 1));
        verify(states).findById(10L);
        verifyNoMoreInteractions(states);
    }

    @Test
    void anExistingOlderStateIsUpdatedInPlaceNotReplaced() {
        PlaybackState older = TestData.state(firstEdition, 5 * MIN, false, VIEWED.minusSeconds(60), null);
        when(states.findById(10L)).thenReturn(Optional.of(older));
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);

        writer.apply(write(viewed(949L, 1, 0, null), List.of(100L), null, null, null));

        verify(states).save(older);
        assertThat(older.isWatched()).isTrue();
        assertThat(older.getPositionMs()).isZero();
    }

    @Test
    void aFilmPlexNeverPlayedWritesNoPlaybackState() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        survivorAfterwards(first);

        Applied applied = writer.apply(write(plex(949L), List.of(100L), null, null, null));

        assertThat(applied).isEqualTo(new Applied(0, 0, 0, 0, 0));
        verify(states).findById(10L);
        verifyNoMoreInteractions(states);
    }

    // ---- error paths ---------------------------------------------------------------------------------------------

    @Test
    void aSurvivorThatCannotBeReloadedIsAnErrorSoTheTransactionRollsBack() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        when(films.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.apply(write(plex(949L), List.of(100L), null, null, null)))
            .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(states);
    }

    @Test
    void anEditionThatVanishedMidwayIsAnError() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first));
        when(editions.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.apply(write(plex(949L), List.of(100L), null, null, null)))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aMergeFailureLeavesTheRestUntouched() {
        when(films.findByTmdbIdOrderByIdAsc(949L)).thenReturn(List.of(first, second));
        doAnswer(call -> {
            throw new IllegalStateException("boom");
        }).when(ingest).merge(2L, 1L);

        assertThatThrownBy(() -> writer.apply(write(plex(949L), List.of(100L, 200L), movie(), null, null)))
            .isInstanceOf(IllegalStateException.class);
        verify(ingest).merge(2L, 1L);
        verifyNoMoreInteractions(ingest);
        verifyNoInteractions(states);
    }

    @Test
    void storedArtworkPathsAreThePostersFollowedByTheBackdrops() {
        when(films.findStoredPosterPaths()).thenReturn(List.of("/api/art/p.jpg"));
        when(films.findStoredBackdropPaths()).thenReturn(List.of("/api/art/b.jpg", "/api/art/c.jpg"));

        assertThat(writer.storedArtworkPaths()).containsExactly("/api/art/p.jpg", "/api/art/b.jpg", "/api/art/c.jpg");
    }
}
