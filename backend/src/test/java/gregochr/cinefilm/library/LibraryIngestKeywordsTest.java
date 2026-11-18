package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.tmdb.TmdbApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A rematch writes the forgotten keywords to the row too; a film whose keywords are stamped keeps them. */
@ExtendWith(MockitoExtension.class)
class LibraryIngestKeywordsTest {

    @Mock
    private FilmRepository films;

    @Mock
    private EditionRepository editions;

    @Mock
    private MediaFileRepository files;

    @Mock
    private PersonRepository people;

    @Mock
    private FilmPreferenceRepository preferences;

    @Mock
    private FilmCollectionRepository collections;

    @Mock
    private ScanRunRepository runs;

    @Mock
    private TmdbApi tmdb;

    private LibraryIngest ingest;

    @BeforeEach
    void setUp() {
        ingest = new LibraryIngest(films, editions, files, people, preferences, collections, runs, tmdb,
            CLOCK);
    }

    @Test
    void aFilmWithNoKeywordStampHasItsKeywordRowsAndStampClearedOnTheRow() {
        Film film = film(7L, "Heat", 1995, NOW);

        ingest.writeLanguages(film);

        verify(films).deleteKeywordRows(7L);
        verify(films).clearKeywordsStamp(7L);
    }

    @Test
    void aFilmWhoseKeywordsAreStampedKeepsThem() {
        Film film = film(7L, "Heat", 1995, NOW);
        film.setKeywordsFetchedAt(NOW);

        ingest.writeLanguages(film);

        verify(films).writeLanguages(7L, null, null, null);
        verifyNoMoreInteractions(films);
    }

    @Test
    void aFilmNotSavedYetTouchesNothing() {
        ingest.writeLanguages(film(null, "Heat", 1995, NOW));

        verifyNoInteractions(films);
    }
}
