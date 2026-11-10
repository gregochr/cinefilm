package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.FieldError;
import gregochr.cinefilm.config.FieldErrorsException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.EditionDtos.EditionFileDto;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.EditionsView;
import gregochr.cinefilm.library.EditionDtos.FileRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import gregochr.cinefilm.library.EditionDtos.SaveResult;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/** {@link EditionArranger} on its own: mocked collaborators, no Spring context and no database. */
class EditionArrangerUnitTest {

    private static final long FILM_ID = 7L;

    private FilmRepository films;
    private EditionRepository editions;
    private MediaFileRepository files;
    private PlaybackStateRepository states;
    private FilmPreferenceRepository preferences;
    private GroupingChangeRepository changes;
    private EditionJoin join;
    private LibraryLock lock;
    private EditionArranger arranger;
    private Film film;
    private Edition edition;

    @BeforeEach
    void setUp() {
        films = mock(FilmRepository.class);
        editions = mock(EditionRepository.class);
        files = mock(MediaFileRepository.class);
        states = mock(PlaybackStateRepository.class);
        preferences = mock(FilmPreferenceRepository.class);
        changes = mock(GroupingChangeRepository.class);
        join = mock(EditionJoin.class);
        lock = new LibraryLock();
        arranger = new EditionArranger(films, editions, files, states, preferences, changes, join, lock,
            mock(PlatformTransactionManager.class), CLOCK);
        film = TestData.film(FILM_ID, "Heat", 1995, NOW);
        edition = TestData.edition(film, 5L, "Original", 170 * MIN, null);
        MediaFile file = TestData.file(edition, "Heat.m4v");
        file.setId(11L);
        when(films.findById(FILM_ID)).thenReturn(Optional.of(film));
    }

    private static SaveRequest rename(String name) {
        return new SaveRequest(List.of(new EditionRequest(5L, name, true, List.of(new FileRequest(11L, null)))), null);
    }

    @Test
    void aSaveWhileTheLibraryIsLockedIs409AndTouchesNothing() {
        lock.tryAcquire(LibraryLock.MATCH);

        assertThatThrownBy(() -> arranger.save(FILM_ID, rename("Cut"))).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");

        assertThat(lock.holder()).as("the other holder keeps it").isEqualTo(LibraryLock.MATCH);
        verifyNoInteractions(films, editions, files, states, preferences, changes, join);
    }

    @Test
    void anUnknownFilmIs404AndTheLockIsGivenBack() {
        when(films.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> arranger.save(99L, rename("Cut"))).isInstanceOf(NotFoundException.class)
            .hasMessage("No film 99");
        assertThatThrownBy(() -> arranger.view(99L)).isInstanceOf(NotFoundException.class);

        assertThat(lock.holder()).isNull();
    }

    @Test
    void aBrokenArrangementIsRefusedBeforeAnythingIsWritten() {
        SaveRequest blank = rename("  ");

        assertThatThrownBy(() -> arranger.save(FILM_ID, blank)).isInstanceOfSatisfying(FieldErrorsException.class,
            e -> assertThat(e.errors()).extracting(FieldError::message)
                .containsExactly("Give this edition a name."));

        assertThat(lock.holder()).isNull();
        verifyNoInteractions(editions, files, preferences, changes, join);
    }

    @Test
    void renamingOneEditionWritesItsNameTheChoiceTheFilmAndTheReportAndMovesNothing() {
        when(editions.findById(5L)).thenReturn(Optional.of(edition));
        when(states.findById(5L)).thenReturn(Optional.empty());
        when(preferences.findById(FILM_ID)).thenReturn(Optional.empty());

        SaveResult result = arranger.save(FILM_ID, rename(" Cut "));

        assertThat(result).isEqualTo(new SaveResult(FILM_ID, List.of()));
        assertThat(edition.getName()).isEqualTo("Cut");
        assertThat(edition.getGroupingSource()).isEqualTo(GroupingSource.OWNER);
        assertThat(film.isEditionsConfirmed()).isTrue();
        verifyNoInteractions(files);
        ArgumentCaptor<FilmPreference> preference = ArgumentCaptor.forClass(FilmPreference.class);
        verify(preferences).save(preference.capture());
        assertThat(preference.getValue().getDefaultEditionId()).isEqualTo(5L);
        assertThat(preference.getValue().isDefaultEditionChosen()).isTrue();
        verify(join).repoint(FILM_ID, 5L, Set.of());
        verify(films).save(film);
        ArgumentCaptor<GroupingChange> change = ArgumentCaptor.forClass(GroupingChange.class);
        verify(changes).save(change.capture());
        assertThat(change.getValue().getKind()).isEqualTo(GroupingChange.JOIN);
        assertThat(change.getValue().getSummary()).isEqualTo("You arranged \"Heat\" into 1 edition with Edit editions");
        assertThat(change.getValue().getHappenedAt()).isEqualTo(NOW);
    }

    @Test
    void theViewNamesTheFileAndKeepsOnlyTheFileNameOfAWindowsStylePath() {
        film.getEditions().get(0).getMediaFiles().get(0).setPath("Heat\\Heat (1995).m4v");
        when(preferences.findById(FILM_ID)).thenReturn(Optional.empty());

        EditionsView view = arranger.view(FILM_ID);

        assertThat(view.files()).extracting(EditionFileDto::fileName).containsExactly("Heat (1995).m4v");
    }
}
