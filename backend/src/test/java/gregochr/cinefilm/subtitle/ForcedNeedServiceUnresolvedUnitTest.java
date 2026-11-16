package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.PendingDownloads.Pending;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link ForcedNeedService#unresolvedCount()} is the cheap path the Admin summary polls: it must answer what the
 * list's {@code counts.unresolved} says while asking playback only about the needed films and never building the
 * edition picker (strict stubs fail the test if a call is made that nothing stubbed).
 */
@ExtendWith(MockitoExtension.class)
class ForcedNeedServiceUnresolvedUnitTest {

    private static final Instant ASKED = NOW.minusSeconds(3600);

    @Mock
    private FilmRepository films;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private MediaFileRepository files;

    @Mock
    private EditionRepository editions;

    @Mock
    private ForcedSubtitleService forcedSubtitles;

    @Mock
    private PendingDownloads pending;

    @Mock
    private PlatformTransactionManager manager;

    private ForcedNeedService service;

    @BeforeEach
    void setUp() {
        service = withSwitches(OpenSubtitlesSwitches.on());
    }

    private ForcedNeedService withSwitches(OpenSubtitlesSwitches switches) {
        return new ForcedNeedService(films, sidecars, files, editions, forcedSubtitles, pending, switches,
            new LibraryLock(), manager);
    }

    /** A film whose need is {@code NEEDED} by the owner's say, with the given OpenSubtitles facts. */
    private static NeedRow needed(long id, String imdbId, Integer count, String searchedImdbId,
                                  boolean candidatesMissing) {
        return new NeedRow(id, "Film " + id, 2000, id * 10, "fr", "fr", ASKED, ForcedNeedChoice.NEEDED, imdbId, count,
            count == null ? null : ASKED, searchedImdbId, candidatesMissing);
    }

    private static NeedRow notNeeded(long id) {
        return new NeedRow(id, "Film " + id, 2000, id * 10, "fr", "fr", ASKED, ForcedNeedChoice.NOT_NEEDED, "tt" + id,
            null, null, null, false);
    }

    private static Edition editionOf(long film) {
        return edition(film(film, "Film " + film, 2000, NOW), film * 10, "Original", 90 * MIN, null);
    }

    /**
     * Films 1 to 7: 1 plays a subtitle (not in play); 2 has no IMDb id (unresolved); 3 has one and was never searched
     * (being checked by the search); 4 is searched with files and a download is due (being checked); 5 is searched with
     * files and nothing is due (unresolved); 6 is searched with none (unresolved); 7 is marked not needed and has a
     * served subtitle too (the list asks playback about it, the count does not).
     */
    private Edition scenario() {
        Edition plays = editionOf(1);
        when(films.findNeedRows()).thenReturn(List.of(
            needed(1, "tt1", 1, "tt1", false), needed(2, null, null, null, false),
            needed(3, "tt3", null, null, false), needed(4, "tt4", 2, "tt4", false),
            needed(5, "tt5", 2, "tt5", false), needed(6, "tt6", 0, "tt6", false), notNeeded(7)));
        when(sidecars.findFilmIdsWithPresentSidecar(SubtitleKind.FORCED)).thenReturn(List.of());
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(1L, 7L));
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(plays));
        when(forcedSubtitles.playableEditionIds(List.of(plays))).thenReturn(Set.of(plays.getId()));
        when(pending.forced(Map.of(4L, "tt4", 5L, "tt5"))).thenReturn(new Pending(Set.of(4L)));
        return plays;
    }

    /** What the list asks beyond the count: playback is asked about the not-needed film 7 as well. */
    private void listAlsoAsksAboutTheNotNeededFilm(Edition plays) {
        Edition other = editionOf(7);
        when(editions.findWithFilesByFilmIdIn(Set.of(1L, 7L))).thenReturn(List.of(plays, other));
        when(forcedSubtitles.playableEditionIds(List.of(plays, other)))
            .thenReturn(Set.of(plays.getId(), other.getId()));
        when(files.findAudioRows(List.of(2L, 3L, 4L, 5L, 6L))).thenReturn(List.of());
    }

    @Test
    void aFilmBeingCheckedBySearchOrByADueDownloadOrPlayingASubtitleIsNotUnresolved() {
        scenario();

        assertThat(service.unresolvedCount()).isEqualTo(3);
    }

    @Test
    void itAnswersWhatTheListsCountSays() {
        listAlsoAsksAboutTheNotNeededFilm(scenario());

        assertThat(service.unresolvedCount()).isEqualTo(3).isEqualTo(service.list().counts().unresolved());
    }

    @Test
    void aSearchThatIsOffLeavesAnUnsearchedFilmUnresolvedAsTheListSaysToo() {
        ForcedNeedService off = withSwitches(new OpenSubtitlesSwitches(false, false));
        listAlsoAsksAboutTheNotNeededFilm(scenario());

        assertThat(off.unresolvedCount()).isEqualTo(5).isEqualTo(off.list().counts().unresolved());
    }

    @Test
    void storedCandidatesThatAreMissingMakeASearchedFilmBeingCheckedWhileTheSearchIsOn() {
        when(films.findNeedRows()).thenReturn(List.of(needed(1, "tt1", 0, "tt1", true)));
        when(sidecars.findFilmIdsWithPresentSidecar(SubtitleKind.FORCED)).thenReturn(List.of());
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of());

        assertThat(service.unresolvedCount()).isZero();
    }

    @Test
    void anEmbeddedForcedTrackPlaybackServesIsASubtitleThatPlaysSoTheFilmIsNotUnresolved() {
        Edition plays = editionOf(1);
        when(films.findNeedRows()).thenReturn(List.of(needed(1, null, null, null, false)));
        when(sidecars.findFilmIdsWithPresentSidecar(SubtitleKind.FORCED)).thenReturn(List.of());
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of(1L));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(plays));
        when(forcedSubtitles.playableEditionIds(List.of(plays))).thenReturn(Set.of(plays.getId()));

        assertThat(service.unresolvedCount()).isZero();
    }

    @Test
    void noNeededFilmIsZeroAndNeitherPlaybackNorThePipelineIsAsked() {
        when(films.findNeedRows()).thenReturn(List.of(notNeeded(1), notNeeded(2)));
        when(sidecars.findFilmIdsWithPresentSidecar(SubtitleKind.FORCED)).thenReturn(List.of());
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of(2L));

        assertThat(service.unresolvedCount()).isZero();

        verify(films).findNeedRows();
        verify(sidecars).findFilmIdsWithPresentSidecar(SubtitleKind.FORCED);
        verify(files).findFilmIdsWithEmbeddedForced();
        verifyNoMoreInteractions(films, sidecars, files);
        verifyNoInteractions(editions, forcedSubtitles, pending);
    }

    @Test
    void onlyTheNeededFilmsAreAskedWhatPlaybackServesAndTheRepositoriesAreReadOnceEach() {
        when(films.findNeedRows()).thenReturn(List.of(needed(1, null, null, null, false), notNeeded(2)));
        when(sidecars.findFilmIdsWithPresentSidecar(SubtitleKind.FORCED)).thenReturn(List.of());
        when(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)).thenReturn(List.of(2L));
        when(files.findFilmIdsWithEmbeddedForced()).thenReturn(List.of(2L));

        assertThat(service.unresolvedCount()).isEqualTo(1);

        verify(films).findNeedRows();
        verify(sidecars).findFilmIdsWithPresentSidecar(SubtitleKind.FORCED);
        verify(sidecars).findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED);
        verify(files).findFilmIdsWithEmbeddedForced();
        verifyNoMoreInteractions(films, sidecars, files);
        verifyNoInteractions(editions, forcedSubtitles, pending);
    }
}
