package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.collection.CollectionPart;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionRef;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Part;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Details of the ingest that the broader {@link LibraryIngestTest} does not pin: what each update replaces. */
class LibraryIngestDetailsTest extends ApiTestBase {

    private static final Instant CREATED = NOW.minus(Duration.ofDays(3));

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbApi tmdb;

    private static FoundFile found(String path, long size) {
        return new FoundFile(path, size, CREATED, CREATED, false);
    }

    private static ProbeResult probe(long durationMs) {
        return new ProbeResult(durationMs, "mp4", "H.264", 1920, 800, 9_000_000L,
            List.of(new ProbeResult.Audio(1, "English", "Stereo", "AAC", null, true, true),
                new ProbeResult.Audio(2, "English", "5.1", "AC-3", null, false, true)),
            List.of(new ProbeResult.Subtitle(3, "English", false, null, "mov_text"),
                new ProbeResult.Subtitle(4, "English", true, null, "mov_text")),
            new ProbeResult.Tags(null, null, null, null, null), false);
    }

    private static MovieDetails movie(List<String> genres, List<TmdbModels.Credit> directors, CollectionRef ref) {
        return new MovieDetails(949, "Heat", "Heat", 1995, null, 170, genres, null, null, 0, directors, List.of(),
            ref);
    }

    private static MatchOutcome matched(MovieDetails movie) {
        return new MatchOutcome(Status.MATCHED, movie, List.of());
    }

    private Film only() {
        em.flush();
        em.clear();
        return films.findAll().get(0);
    }

    private Long add(String path) {
        ingest.addNew(found(path, 10), Playability.PLAYABLE, probe(1), new TitleGuess("A", null), null);
        return ingest.known().get(path).id();
    }

    @Test
    void aRefreshOfAFileThatHadGoneMissingMarksItPresentAgain() {
        Long id = add("A.m4v");
        ingest.markMissing(List.of(id));

        ingest.refresh(id, found("A.m4v", 11), Playability.PLAYABLE, probe(1));
        em.flush();
        em.clear();

        assertThat(mediaFiles.findById(id).orElseThrow().getPresence()).isEqualTo(Presence.PRESENT);
    }

    @Test
    void aRenameOfAFileThatHadGoneMissingMarksItPresentAtTheNewPath() {
        Long id = add("Old.m4v");
        ingest.markMissing(List.of(id));

        ingest.repoint(id, found("New.m4v", 10));
        em.flush();
        em.clear();

        MediaFile file = mediaFiles.findById(id).orElseThrow();
        assertThat(file.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(file.getPath()).isEqualTo("New.m4v");
    }

    @Test
    void aRefreshReplacesTheSubtitleStreamsAndKeepsEachAudioStreamsSafariFlag() {
        ProbeResult trueHd = new ProbeResult(1, "mp4", "H.264", 1, 1, 1L,
            List.of(new ProbeResult.Audio(1, "English", "7.1", "TrueHD", null, true, false)),
            List.of(new ProbeResult.Subtitle(7, "English", false, null, "mov_text")),
            new ProbeResult.Tags(null, null, null, null, null), false);
        Long id = add("A.m4v");

        ingest.refresh(id, found("A.m4v", 11), Playability.PLAYABLE, trueHd);
        em.flush();
        em.clear();

        MediaFile file = mediaFiles.findById(id).orElseThrow();
        assertThat(file.getSubtitleStreams()).extracting("index").containsExactly(7);
        assertThat(file.getAudioStreams()).singleElement().satisfies(a -> {
            assertThat(a.getCodec()).isEqualTo("TrueHD");
            assertThat(a.isSafariPlayable()).isFalse();
        });
    }

    @Test
    void aSecondMatchReplacesTheGenresAndClearsTheStoredCandidates() {
        ingest.addNew(found("Dune.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Dune", null),
            new MatchOutcome(Status.NEEDS_MATCH, null, List.of(new Candidate(1, "Dune", 1984, 136, null, 40))));
        Long filmId = films.findAll().get(0).getId();

        ingest.applyOutcome(filmId, matched(movie(List.of("Crime", "Drama"), List.of(), null)));
        ingest.applyOutcome(filmId, matched(movie(List.of("Sci-Fi"), List.of(), null)));
        em.flush();
        em.clear();

        Film film = films.findById(filmId).orElseThrow();
        assertThat(film.getGenres()).containsExactly("Sci-Fi");
        assertThat(film.getMatchCandidates()).isNull();
        assertThat(film.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
    }

    @Test
    void severalDirectorsAreNumberedInOrderAndCollectionPartsKeepTheirPosters() {
        when(tmdb.collection(55)).thenReturn(Optional.of(new CollectionDetails(55, "C", null,
            List.of(new Part(1, "One", 1990, "/one.jpg"), new Part(2, "Two", 1991, "/two.jpg")))));
        MovieDetails duo = movie(List.of(), List.of(new TmdbModels.Credit(1, "First Director", null, null),
            new TmdbModels.Credit(2, "Second Director", null, null)), new CollectionRef(55, "C", null));

        ingest.addNew(found("Duo.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Duo", 1995),
            matched(duo));

        Film film = only();
        assertThat(film.getCredits()).extracting(c -> c.getPerson().getName(), Credit::getSequence)
            .containsExactly(tuple("First Director", 0), tuple("Second Director", 1));
        assertThat(film.getCollection().getParts()).extracting(CollectionPart::getPosterPath)
            .containsExactly("/one.jpg", "/two.jpg");
    }
}
