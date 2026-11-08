package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.collection.CollectionPart;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionRef;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Part;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Merging films, refreshing streams and completing collections without losing what the owner has chosen. */
class LibraryIngestRepairsTest extends ApiTestBase {

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

    private static ProbeResult probe(ProbeResult.Audio[] audio, ProbeResult.Subtitle[] subtitles) {
        return new ProbeResult(1_000L, "mp4", "H.264", 1920, 800, 1L, List.of(audio), List.of(subtitles),
            new ProbeResult.Tags(null, null, null, null, null), false);
    }

    private static ProbeResult standard() {
        return probe(new ProbeResult.Audio[] {new ProbeResult.Audio(1, "English", "Stereo", "AAC", null, true, true),
            new ProbeResult.Audio(2, "English", "5.1", "AC-3", null, false, true)},
            new ProbeResult.Subtitle[] {new ProbeResult.Subtitle(3, "English", false, null, "mov_text"),
                new ProbeResult.Subtitle(4, "English", true, null, "mov_text")});
    }

    private static MovieDetails heat(CollectionRef collection) {
        return new MovieDetails(949, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0, List.of(), List.of(),
            collection);
    }

    private static MatchOutcome matched(MovieDetails movie) {
        return new MatchOutcome(Status.MATCHED, movie, List.of());
    }

    private Long add(String path, ProbeResult probe) {
        ingest.addNew(found(path, 10), Playability.PLAYABLE, probe, new TitleGuess("Film " + path, null), null);
        return ingest.known().get(path).id();
    }

    private MediaFile reload(Long id) {
        em.flush();
        em.clear();
        return mediaFiles.findById(id).orElseThrow();
    }

    // ---- 1. merging a film that has been played

    @Test
    void aPendingFilmThatHasBeenPlayedMergesCleanlyAndItsChoicesMoveToTheFilmThatStays() {
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, standard(), new TitleGuess("Heat", 1995),
            matched(heat(null)));
        Long keeper = films.findFirstByTmdbIdOrderByIdAsc(949L).orElseThrow().getId();
        Long copyFile = add("Heat copy.m4v", standard());
        Film pending = mediaFiles.findById(copyFile).orElseThrow().getEdition().getFilm();
        Long copyEdition = mediaFiles.findById(copyFile).orElseThrow().getEdition().getId();
        FilmPreference played = new FilmPreference(pending.getId());
        played.setLastEditionId(copyEdition);
        played.setQuality("original");
        played.setSubtitle("off");
        played.setAudioStreamId(7L);
        preferences.save(played);
        PlaybackState state = new PlaybackState(copyEdition);
        state.setPositionMs(99);
        states.save(state);

        ingest.applyOutcome(pending.getId(), matched(heat(null)));
        em.flush();
        em.clear();

        assertThat(films.findAll()).extracting(Film::getId).containsExactly(keeper);
        assertThat(preferences.findById(pending.getId())).isEmpty();
        FilmPreference moved = preferences.findById(keeper).orElseThrow();
        assertThat(moved.getLastEditionId()).isEqualTo(copyEdition);
        assertThat(moved.getQuality()).isEqualTo("original");
        assertThat(moved.getSubtitle()).isEqualTo("off");
        assertThat(moved.getAudioStreamId()).isEqualTo(7L);
        assertThat(states.findById(copyEdition).orElseThrow().getPositionMs()).isEqualTo(99);
    }

    @Test
    void whenTheFilmThatStaysHasItsOwnChoicesTheMergedFilmsAreDropped() {
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, standard(), new TitleGuess("Heat", 1995),
            matched(heat(null)));
        Long keeper = films.findFirstByTmdbIdOrderByIdAsc(949L).orElseThrow().getId();
        FilmPreference own = new FilmPreference(keeper);
        own.setQuality("720p");
        preferences.save(own);
        Long copyFile = add("Heat copy.m4v", standard());
        Long pendingId = mediaFiles.findById(copyFile).orElseThrow().getEdition().getFilm().getId();
        FilmPreference other = new FilmPreference(pendingId);
        other.setQuality("original");
        preferences.save(other);

        ingest.applyOutcome(pendingId, matched(heat(null)));
        em.flush();
        em.clear();

        assertThat(preferences.findById(keeper).orElseThrow().getQuality()).isEqualTo("720p");
        assertThat(preferences.findById(pendingId)).isEmpty();
        assertThat(films.count()).isEqualTo(1);
    }

    // ---- 3. refreshing streams

    @Test
    void aRefreshWithTheSameStreamIndexesKeepsTheStreamIdsAndSavedChoices() {
        Long id = add("A.m4v", standard());
        MediaFile before = reload(id);
        Long filmId = before.getEdition().getFilm().getId();
        List<Long> audioIds = before.getAudioStreams().stream().map(AudioStream::getId).toList();
        List<Long> subtitleIds = before.getSubtitleStreams().stream().map(SubtitleStream::getId).toList();
        FilmPreference choice = new FilmPreference(filmId);
        choice.setAudioStreamId(audioIds.get(1));
        choice.setSubtitle(String.valueOf(subtitleIds.get(1)));
        preferences.save(choice);
        ProbeResult changed = probe(
            new ProbeResult.Audio[] {new ProbeResult.Audio(1, "English", "Stereo", "AAC", "Renamed", true, true),
                new ProbeResult.Audio(2, "English", "5.1", "DTS", null, false, false)},
            new ProbeResult.Subtitle[] {new ProbeResult.Subtitle(3, "English", false, null, "mov_text"),
                new ProbeResult.Subtitle(4, "French", false, "Now not forced", "subrip")});

        ingest.refresh(id, found("A.m4v", 11), Playability.PLAYABLE, changed);
        MediaFile after = reload(id);

        assertThat(after.getAudioStreams()).extracting(AudioStream::getId).containsExactlyElementsOf(audioIds);
        assertThat(after.getSubtitleStreams()).extracting(SubtitleStream::getId)
            .containsExactlyElementsOf(subtitleIds);
        assertThat(after.getAudioStreams().get(0).getTitle()).isEqualTo("Renamed");
        assertThat(after.getAudioStreams().get(1).getCodec()).isEqualTo("DTS");
        assertThat(after.getAudioStreams().get(1).isSafariPlayable()).isFalse();
        SubtitleStream second = after.getSubtitleStreams().get(1);
        assertThat(second.getLanguage()).isEqualTo("French");
        assertThat(second.isForced()).as("the forced flag is updated in place").isFalse();
        assertThat(second.getFormat()).isEqualTo("subrip");
        FilmPreference kept = preferences.findById(filmId).orElseThrow();
        assertThat(kept.getAudioStreamId()).isEqualTo(audioIds.get(1));
        assertThat(kept.getSubtitle()).isEqualTo(String.valueOf(subtitleIds.get(1)));
    }

    @Test
    void aStreamThatIsReallyGoneResetsTheChoiceThatPointedAtItAndNewStreamsAreAdded() {
        Long id = add("A.m4v", standard());
        MediaFile before = reload(id);
        Long filmId = before.getEdition().getFilm().getId();
        Long audioTwo = before.getAudioStreams().get(1).getId();
        Long subtitleThree = before.getSubtitleStreams().get(0).getId();
        Long subtitleFour = before.getSubtitleStreams().get(1).getId();
        FilmPreference choice = new FilmPreference(filmId);
        choice.setAudioStreamId(audioTwo);
        choice.setSubtitle(String.valueOf(subtitleThree));
        preferences.save(choice);
        ProbeResult fewer = probe(
            new ProbeResult.Audio[] {new ProbeResult.Audio(1, "English", "Stereo", "AAC", null, true, true)},
            new ProbeResult.Subtitle[] {new ProbeResult.Subtitle(4, "English", true, null, "mov_text"),
                new ProbeResult.Subtitle(9, "German", false, null, "subrip")});

        ingest.refresh(id, found("A.m4v", 11), Playability.PLAYABLE, fewer);
        MediaFile after = reload(id);

        assertThat(after.getAudioStreams()).extracting(AudioStream::getIndex).containsExactly(1);
        assertThat(after.getSubtitleStreams()).extracting(SubtitleStream::getIndex).containsExactlyInAnyOrder(4, 9);
        assertThat(after.getSubtitleStreams()).extracting(SubtitleStream::getId).contains(subtitleFour);
        FilmPreference reset = preferences.findById(filmId).orElseThrow();
        assertThat(reset.getAudioStreamId()).isNull();
        assertThat(reset.getSubtitle()).isEqualTo("off");
    }

    @Test
    void aGoneStreamLeavesAChoiceForADifferentStreamAlone() {
        Long id = add("A.m4v", standard());
        MediaFile before = reload(id);
        Long filmId = before.getEdition().getFilm().getId();
        FilmPreference choice = new FilmPreference(filmId);
        choice.setAudioStreamId(before.getAudioStreams().get(0).getId());
        choice.setSubtitle("off");
        preferences.save(choice);

        ingest.refresh(id, found("A.m4v", 11), Playability.PLAYABLE, probe(
            new ProbeResult.Audio[] {new ProbeResult.Audio(1, "English", "Stereo", "AAC", null, true, true)},
            new ProbeResult.Subtitle[] {}));
        reload(id);

        FilmPreference kept = preferences.findById(filmId).orElseThrow();
        assertThat(kept.getAudioStreamId()).isEqualTo(before.getAudioStreams().get(0).getId());
        assertThat(kept.getSubtitle()).isEqualTo("off");
    }

    // ---- 4. collections whose parts TMDB could not give

    @Test
    void aCollectionTmdbCouldNotDescribeIsMarkedIncompleteAndFilledInWhenTmdbRecovers() {
        CollectionRef ref = new CollectionRef(55, "Heat Collection", null);
        when(tmdb.collection(55)).thenThrow(new TmdbUnavailableException("down", null));
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, standard(), new TitleGuess("Heat", 1995),
            matched(heat(ref)));
        em.flush();
        em.clear();
        FilmCollection stored = collections.findFirstByTmdbIdOrderByIdAsc(55L).orElseThrow();
        assertThat(stored.isPartsComplete()).isFalse();
        assertThat(stored.getParts()).isEmpty();

        assertThat(ingest.completeCollections()).as("TMDB still down").isZero();
        assertThat(collections.findFirstByTmdbIdOrderByIdAsc(55L).orElseThrow().isPartsComplete()).isFalse();

        doReturn(Optional.of(new CollectionDetails(55, "Heat Collection", null,
            List.of(new Part(949, "Heat", 1995, "/h.jpg"), new Part(950, "Heat 2", null, null))))).when(tmdb)
            .collection(55);
        assertThat(ingest.completeCollections()).isEqualTo(1);
        em.flush();
        em.clear();

        FilmCollection done = collections.findFirstByTmdbIdOrderByIdAsc(55L).orElseThrow();
        assertThat(done.isPartsComplete()).isTrue();
        assertThat(done.getParts()).extracting(CollectionPart::getTitle).containsExactly("Heat", "Heat 2");
        assertThat(ingest.completeCollections()).as("nothing left to do").isZero();
    }

    @Test
    void anotherFilmOfAnIncompleteCollectionCompletesItWhenItAppears() {
        CollectionRef ref = new CollectionRef(55, "Heat Collection", null);
        when(tmdb.collection(55)).thenThrow(new TmdbUnavailableException("down", null));
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, standard(), new TitleGuess("Heat", 1995),
            matched(heat(ref)));
        doReturn(Optional.of(new CollectionDetails(55, "Heat Collection", null,
            List.of(new Part(949, "Heat", 1995, null))))).when(tmdb).collection(55);

        ingest.addNew(found("Sequel.m4v", 11), Playability.PLAYABLE, standard(), new TitleGuess("Sequel", 1999),
            matched(new MovieDetails(950, "Sequel", "Sequel", 1999, null, 100, List.of(), null, null, 0, List.of(),
                List.of(), ref)));
        em.flush();
        em.clear();

        FilmCollection done = collections.findFirstByTmdbIdOrderByIdAsc(55L).orElseThrow();
        assertThat(done.isPartsComplete()).isTrue();
        assertThat(done.getParts()).hasSize(1);
    }

    @Test
    void aCollectionTmdbDoesNotKnowIsCompleteWithNoParts() {
        when(tmdb.collection(55)).thenReturn(Optional.empty());

        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, standard(), new TitleGuess("Heat", 1995),
            matched(heat(new CollectionRef(55, "Heat Collection", null))));
        em.flush();
        em.clear();

        assertThat(collections.findFirstByTmdbIdOrderByIdAsc(55L).orElseThrow().isPartsComplete()).isTrue();
    }
}
