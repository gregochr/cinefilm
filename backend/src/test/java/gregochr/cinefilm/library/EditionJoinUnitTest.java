package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link EditionJoin} on its own: mocked repositories, no Spring context. Covers how the credits marker travels. */
@ExtendWith(MockitoExtension.class)
class EditionJoinUnitTest {

    @Mock
    private FilmRepository films;
    @Mock
    private EditionRepository editions;
    @Mock
    private MediaFileRepository files;
    @Mock
    private PlaybackStateRepository states;
    @Mock
    private FilmPreferenceRepository preferences;
    @Mock
    private LibraryIngest ingest;
    private EditionJoin join;

    private Edition first;
    private Edition second;
    private MediaFile firstFile;
    private MediaFile secondFile;

    @BeforeEach
    void setUp() {
        join = new EditionJoin(films, editions, files, states, preferences, ingest);

        Film film = TestData.film(5L, "Towers", 2002, NOW);
        first = TestData.edition(film, 1L, "Extended", 90 * MIN, null);
        second = TestData.edition(film, 2L, "Extended", 110 * MIN, null);
        firstFile = file(first, 11L, "Towers - pt1.m4v", 90 * MIN);
        secondFile = file(second, 22L, "Towers - pt2.m4v", 110 * MIN);
    }

    private static MediaFile file(Edition edition, long id, String path, long durationMs) {
        MediaFile file = TestData.file(edition, path);
        file.setId(id);
        file.setDurationMs(durationMs);
        return file;
    }

    /** The files as the join leaves them: the survivor reloaded holds both. */
    private void stubJoinLookups() {
        when(files.findAllById(List.of(11L, 22L))).thenReturn(List.of(firstFile, secondFile));
        when(editions.findById(1L)).thenReturn(Optional.of(first));
        when(editions.findById(2L)).thenReturn(Optional.of(second));
        when(states.findById(1L)).thenReturn(Optional.empty());
        when(states.findById(2L)).thenReturn(Optional.empty());
        when(preferences.findById(5L)).thenReturn(Optional.empty());
        first.getMediaFiles().add(secondFile);
    }

    @Test
    void joinKeepsTheMarkerAndTheSourceOfTheEditionHoldingTheLastPart() {
        first.setCreditsMarkerMs(80 * MIN);
        first.setCreditsMarkerSource(CreditsMarkerSource.AUTO);
        second.setCreditsMarkerMs(100 * MIN);
        second.setCreditsMarkerSource(CreditsMarkerSource.PLEX);
        stubJoinLookups();

        Long survivor = join.join(List.of(11L, 22L));

        assertThat(survivor).isEqualTo(1L);
        assertThat(first.getCreditsMarkerMs()).as("shifted by part 1").isEqualTo(190 * MIN);
        assertThat(first.getCreditsMarkerSource()).as("the last holder's source, not part 1's")
            .isEqualTo(CreditsMarkerSource.PLEX);
        verify(editions).deleteByIds(Set.of(2L));
    }

    @Test
    void joinLeavesNoMarkerAndNoSourceWhenTheLastPartHasNone() {
        first.setCreditsMarkerMs(80 * MIN);
        first.setCreditsMarkerSource(CreditsMarkerSource.OWNER);
        stubJoinLookups();

        join.join(List.of(11L, 22L));

        assertThat(first.getCreditsMarkerMs()).isNull();
        assertThat(first.getCreditsMarkerSource()).as("cleared with the marker").isNull();
    }

    private Edition unfoldTwoParts(Long marker, CreditsMarkerSource source) {
        first.setRuntimeMs(200 * MIN);
        first.setCreditsMarkerMs(marker);
        first.setCreditsMarkerSource(source);
        first.getMediaFiles().add(secondFile);
        secondFile.setEdition(first);
        secondFile.setPartNumber(2);
        AtomicReference<Edition> created = new AtomicReference<>();
        when(editions.findById(1L)).thenReturn(Optional.of(first));
        when(states.findById(1L)).thenReturn(Optional.empty());
        when(editions.saveAndFlush(org.mockito.ArgumentMatchers.any(Edition.class))).thenAnswer(call -> {
            Edition edition = call.getArgument(0);
            edition.setId(2L);
            edition.getMediaFiles().add(secondFile);
            created.set(edition);
            return edition;
        });
        when(editions.findById(2L)).thenAnswer(call -> Optional.of(created.get()));

        join.unfold(1L, List.of(List.of(11L), List.of(22L)));
        return created.get();
    }

    @Test
    void unfoldGivesTheMarkerAndItsSourceOnlyToTheGroupHoldingIt() {
        Edition created = unfoldTwoParts(190 * MIN, CreditsMarkerSource.PLEX);

        assertThat(first.getCreditsMarkerMs()).isNull();
        assertThat(first.getCreditsMarkerSource()).as("no marker, no source").isNull();
        assertThat(created.getCreditsMarkerMs()).isEqualTo(100 * MIN);
        assertThat(created.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
    }

    @Test
    void unfoldOfAnEditionWithNoMarkerLeavesEveryGroupWithNoSource() {
        Edition created = unfoldTwoParts(null, null);

        assertThat(first.getCreditsMarkerMs()).isNull();
        assertThat(first.getCreditsMarkerSource()).isNull();
        assertThat(created.getCreditsMarkerMs()).isNull();
        assertThat(created.getCreditsMarkerSource()).isNull();
    }
}
