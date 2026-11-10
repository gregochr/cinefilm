package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.collection.CollectionPart;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryIngest.Pending;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionRef;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Part;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The scan's database operations on H2 (PostgreSQL mode), through the real repositories and mappings. */
class LibraryIngestTest extends ApiTestBase {

    private static final Instant CREATED = NOW.minus(Duration.ofDays(3));
    private static final Instant MODIFIED = NOW.minus(Duration.ofDays(2)).plusMillis(123);

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private ScanRunRepository runs;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbApi tmdb;

    private static FoundFile found(String path, long size) {
        return new FoundFile(path, size, MODIFIED, CREATED, false);
    }

    private static ProbeResult probe(long durationMs) {
        return new ProbeResult(durationMs, "mp4", "H.264", 1920, 800, 9_000_000L,
            List.of(new ProbeResult.Audio(1, "English", "Stereo", "AAC", null, true, true),
                new ProbeResult.Audio(2, "English", "5.1", "AC-3", "Surround", false, true)),
            List.of(new ProbeResult.Subtitle(3, "English", false, null, "mov_text"),
                new ProbeResult.Subtitle(4, "English", true, "English (forced)", "mov_text")),
            new ProbeResult.Tags("Heat", 1995, "9", "1", "A crew of thieves."), false, "yuv420p");
    }

    private static MovieDetails movie(long id, String title, Integer year, CollectionRef collection) {
        return new MovieDetails(id, title, title, year, "Overview of " + title, 170, List.of("Crime", "Drama"),
            "/poster.jpg", "/backdrop.jpg", 10, List.of(new TmdbModels.Credit(100, "Michael Mann", "/mm.jpg", null)),
            List.of(new TmdbModels.Credit(200, "Al Pacino", "/ap.jpg", "Vincent Hanna"),
                new TmdbModels.Credit(201, "Robert De Niro", null, "Neil McCauley")), collection);
    }

    private static MatchOutcome matched(MovieDetails movie) {
        return new MatchOutcome(Status.MATCHED, movie, List.of());
    }

    private Film only() {
        em.flush();
        em.clear();
        List<Film> all = films.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    void anUnmatchedFileBecomesAPendingFilmWithTheGuessedTitleOneOriginalEditionAndItsStreams() {
        ingest.addNew(found("Heat (1995).m4v", 7_000_000_000L), Playability.PLAYABLE, probe(10_200_000L),
            new TitleGuess("Heat", 1995), null);

        Film film = only();
        assertThat(film.getTitle()).isEqualTo("Heat");
        assertThat(film.getSortTitle()).isEqualTo("heat");
        assertThat(film.getYear()).isEqualTo(1995);
        assertThat(film.getTmdbId()).isNull();
        assertThat(film.getMatchStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(film.getMatchCandidates()).isNull();
        assertThat(film.getOverview()).isEqualTo("A crew of thieves.");
        assertThat(film.getAddedAt()).isEqualTo(CREATED);
        assertThat(film.getEditions()).singleElement().satisfies(edition -> {
            assertThat(edition.getName()).isEqualTo("Original");
            assertThat(edition.getYear()).isEqualTo(1995);
            assertThat(edition.getRuntimeMs()).isEqualTo(10_200_000L);
        });
        MediaFile file = film.getEditions().get(0).originalFile();
        assertThat(file.getPath()).isEqualTo("Heat (1995).m4v");
        assertThat(file.getKind()).isEqualTo(MediaKind.ORIGINAL);
        assertThat(file.getSizeBytes()).isEqualTo(7_000_000_000L);
        assertThat(file.getModifiedAt()).isEqualTo(MODIFIED);
        assertThat(file.getAddedAt()).isEqualTo(CREATED);
        assertThat(file.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(file.getPlayability()).isEqualTo(Playability.PLAYABLE);
        assertThat(file.getContainer()).isEqualTo("mp4");
        assertThat(file.getVideoCodec()).isEqualTo("H.264");
        assertThat(file.getPixelFormat()).isEqualTo("yuv420p");
        assertThat(file.getWidth()).isEqualTo(1920);
        assertThat(file.getHeight()).isEqualTo(800);
        assertThat(file.getBitrate()).isEqualTo(9_000_000L);
        assertThat(file.getDurationMs()).isEqualTo(10_200_000L);
        assertThat(file.getAudioStreams()).extracting("index", "language", "channels", "codec", "title",
            "isDefault", "safariPlayable").containsExactly(
                tuple(1, "English", "Stereo", "AAC", null, true, true),
                tuple(2, "English", "5.1", "AC-3", "Surround", false, true));
        assertThat(file.getSubtitleStreams()).extracting("index", "language", "forced", "title", "format")
            .containsExactly(tuple(3, "English", false, null, "mov_text"),
                tuple(4, "English", true, "English (forced)", "mov_text"));
    }

    @Test
    void aFileWithAnUnknownYearStoresNoYearAtAll() {
        ingest.addNew(found("Mystery.m4v", 1), Playability.PLAYABLE, null, new TitleGuess("Mystery", null), null);

        Film film = only();
        assertThat(film.getYear()).isNull();
        assertThat(film.getEditions().get(0).getYear()).isNull();
        assertThat(film.getEditions().get(0).getRuntimeMs()).isZero();
        assertThat(film.getOverview()).isNull();
        assertThat(film.getEditions().get(0).originalFile().getVideoCodec()).isNull();
        assertThat(film.getEditions().get(0).originalFile().getPixelFormat()).isNull();
        assertThat(film.getEditions().get(0).originalFile().getAudioStreams()).isEmpty();
    }

    @Test
    void aTitleGuessWithoutATitleIsRefusedAtTheBoundaryAndStoresNothing() {
        FoundFile file = found("Nameless.m4v", 1);
        TitleGuess nameless = new TitleGuess(null, null);

        assertThatNullPointerException()
            .isThrownBy(() -> ingest.addNew(file, Playability.PLAYABLE, null, nameless, null))
            .withMessage("A title guess must carry a title");
        assertThat(mediaFiles.count()).isZero();
    }

    @Test
    void aProbeWithoutADescriptionLeavesTheOverviewEmpty() {
        ProbeResult bare = new ProbeResult(1, null, null, null, null, null, List.of(), List.of(),
            new ProbeResult.Tags(null, null, null, null, null), false);

        ingest.addNew(found("Bare.m4v", 1), Playability.PLAYABLE, bare, new TitleGuess("Bare", null), null);

        Film film = only();
        assertThat(film.getOverview()).isNull();
        assertThat(film.getEditions().get(0).originalFile().getContainer()).isNull();
        assertThat(film.getEditions().get(0).originalFile().getPixelFormat()).isEmpty();
    }

    @Test
    void aMatchedFileTakesItsMetadataGenresCreditsAndCollectionFromTmdb() {
        when(tmdb.collection(55)).thenReturn(Optional.of(new CollectionDetails(55, "Heat Collection", "/c.jpg",
            List.of(new Part(949, "Heat", 1995, "/h.jpg"), new Part(950, "Heat 2", null, null)))));
        MovieDetails heat = movie(949, "Heat", 1995, new CollectionRef(55, "Heat Collection", "/c.jpg"));

        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("heat", null),
            matched(heat));

        Film film = only();
        assertThat(film.getTmdbId()).isEqualTo(949);
        assertThat(film.getTitle()).isEqualTo("Heat");
        assertThat(film.getYear()).isEqualTo(1995);
        assertThat(film.getOverview()).isEqualTo("Overview of Heat");
        assertThat(film.getPosterPath()).isEqualTo("/poster.jpg");
        assertThat(film.getBackdropPath()).isEqualTo("/backdrop.jpg");
        assertThat(film.getGenres()).containsExactlyInAnyOrder("Crime", "Drama");
        assertThat(film.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(film.getMatchCandidates()).isNull();
        assertThat(film.getCredits()).extracting(Credit::getRole, c -> c.getPerson().getName(), Credit::getCharacter,
            Credit::getSequence).containsExactly(
                tuple(CreditRole.DIRECTOR, "Michael Mann", null, 0),
                tuple(CreditRole.CAST, "Al Pacino", "Vincent Hanna", 0),
                tuple(CreditRole.CAST, "Robert De Niro", "Neil McCauley", 1));
        assertThat(film.getCredits().get(1).getPerson().getTmdbId()).isEqualTo(200);
        assertThat(film.getCredits().get(1).getPerson().getProfilePath()).isEqualTo("/ap.jpg");
        FilmCollection collection = film.getCollection();
        assertThat(collection.getTmdbId()).isEqualTo(55);
        assertThat(collection.getName()).isEqualTo("Heat Collection");
        assertThat(collection.getPosterPath()).isEqualTo("/c.jpg");
        assertThat(collection.getParts()).extracting(CollectionPart::getTitle, CollectionPart::getYear,
            CollectionPart::getReleaseOrder, CollectionPart::getTmdbId).containsExactly(
                tuple("Heat", 1995, 0, 949L),
                tuple("Heat 2", null, 1, 950L));
    }

    @Test
    void aPersonAndACollectionAlreadyKnownAreReusedNotDuplicated() {
        when(tmdb.collection(55)).thenReturn(Optional.empty());
        CollectionRef ref = new CollectionRef(55, "Heat Collection", null);
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            matched(movie(949, "Heat", 1995, ref)));
        ingest.addNew(found("Sequel.m4v", 11), Playability.PLAYABLE, probe(1), new TitleGuess("Sequel", 1999),
            matched(movie(950, "Sequel", 1999, ref)));
        em.flush();
        em.clear();

        assertThat(films.findAll()).hasSize(2);
        assertThat(people.findAll()).extracting(Person::getName)
            .containsExactlyInAnyOrder("Michael Mann", "Al Pacino", "Robert De Niro");
        assertThat(collections.findAll()).singleElement().satisfies(c -> assertThat(c.getParts()).isEmpty());
    }

    @Test
    void aCollectionWhoseDetailsTmdbCannotGiveIsStoredWithoutParts() {
        when(tmdb.collection(55)).thenThrow(new TmdbUnavailableException("down", null));

        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            matched(movie(949, "Heat", 1995, new CollectionRef(55, "Heat Collection", null))));

        assertThat(only().getCollection().getParts()).isEmpty();
    }

    @Test
    void tmdbFieldsThatAreMissingKeepWhatTheFileGaveUs() {
        MovieDetails sparse = new MovieDetails(949, null, null, null, null, null, List.of(), null, null, 0,
            List.of(new TmdbModels.Credit(100, null, null, null)), List.of(), null);

        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            matched(sparse));

        Film film = only();
        assertThat(film.getTitle()).isEqualTo("Heat");
        assertThat(film.getYear()).isEqualTo(1995);
        assertThat(film.getOverview()).isEqualTo("A crew of thieves.");
        assertThat(film.getCollection()).isNull();
        assertThat(film.getCredits()).singleElement().satisfies(c -> assertThat(c.getPerson().getName())
            .isEqualTo("Unknown"));
    }

    @Test
    void twoFilesMatchingOneTmdbIdAreTwoEditionsOfOneFilm() {
        MovieDetails heat = movie(949, "Heat", 1995, null);
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            matched(heat));
        ingest.addNew(found("Heat Directors Cut.m4v", 11), Playability.PLAYABLE, probe(2),
            new TitleGuess("Heat Directors Cut", null), matched(heat));

        Film film = only();
        assertThat(film.getEditions()).hasSize(2);
        assertThat(film.getEditions()).extracting(Edition::getName).containsExactly("Original", "Director's Cut");
        assertThat(film.getEditions()).extracting(e -> e.originalFile().getPath())
            .containsExactly("Heat.m4v", "Heat Directors Cut.m4v");
    }

    @Test
    void aFileTmdbCouldNotPlaceKeepsTheTopCandidatesForTheOwner() {
        List<Candidate> candidates = List.of(new Candidate(1, "Dune", 1984, 136, "/a.jpg", 50),
            new Candidate(2, "Dune", 2021, 155, null, 50));

        ingest.addNew(found("Dune.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Dune", null),
            new MatchOutcome(Status.NEEDS_MATCH, null, candidates));

        Film film = only();
        assertThat(film.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(film.getMatchCandidates()).isEqualTo("[{\"tmdbId\":1,\"title\":\"Dune\",\"year\":1984,"
            + "\"runtimeMinutes\":136,\"posterPath\":\"/a.jpg\",\"score\":50},{\"tmdbId\":2,\"title\":\"Dune\","
            + "\"year\":2021,\"runtimeMinutes\":155,\"posterPath\":null,\"score\":50}]");
    }

    @Test
    void anOutageLeavesTheFilmPendingForTheNextScan() {
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            new MatchOutcome(Status.UNAVAILABLE, null, List.of()));

        assertThat(only().getMatchStatus()).isEqualTo(MatchStatus.PENDING);
    }

    @Test
    void veryLongNamesAreTruncatedToTheirColumns() {
        String longTitle = "T".repeat(400);

        ingest.addNew(found("a.m4v", 1), Playability.PLAYABLE, probe(1), new TitleGuess(longTitle, null), null);

        assertThat(only().getTitle()).hasSize(255);
    }

    @Test
    void knownListsEveryOriginalFileByPathWithWhatTheScannerComparesOn() {
        ingest.addNew(found("A.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("A", null), null);
        ingest.addNew(found("B.avi", 20), Playability.NEEDS_CONVERSION, null, new TitleGuess("B", null), null);

        Map<String, Known> known = ingest.known();

        assertThat(known).containsOnlyKeys("A.m4v", "B.avi");
        assertThat(known.get("A.m4v")).satisfies(k -> {
            assertThat(k.id()).isNotNull();
            assertThat(k.sizeBytes()).isEqualTo(10);
            assertThat(k.modifiedAt()).isEqualTo(MODIFIED);
            assertThat(k.presence()).isEqualTo(Presence.PRESENT);
            assertThat(k.playability()).isEqualTo(Playability.PLAYABLE);
        });
        assertThat(known.get("B.avi").playability()).isEqualTo(Playability.NEEDS_CONVERSION);
    }

    @Test
    void refreshReplacesTheSizeTimeStreamsAndRuntimeAndBringsTheFileBack() {
        ingest.addNew(found("A.m4v", 10), Playability.UNREADABLE, null, new TitleGuess("A", null), null);
        Long id = ingest.known().get("A.m4v").id();
        FoundFile changed = new FoundFile("A.m4v", 99, MODIFIED.plusSeconds(60), CREATED, false);

        ingest.refresh(id, changed, Playability.PLAYABLE, probe(5_000L));
        em.flush();
        em.clear();

        MediaFile file = mediaFiles.findById(id).orElseThrow();
        assertThat(file.getSizeBytes()).isEqualTo(99);
        assertThat(file.getModifiedAt()).isEqualTo(MODIFIED.plusSeconds(60));
        assertThat(file.getPlayability()).isEqualTo(Playability.PLAYABLE);
        assertThat(file.getAudioStreams()).hasSize(2);
        assertThat(file.getSubtitleStreams()).hasSize(2);
        assertThat(file.getEdition().getRuntimeMs()).isEqualTo(5_000L);

        ingest.refresh(id, changed, Playability.PLAYABLE, probe(6_000L));
        em.flush();
        em.clear();

        assertThat(mediaFiles.findById(id).orElseThrow().getAudioStreams()).as("replaced, not appended").hasSize(2);
    }

    @Test
    void refreshingOnePartOfAnEditionSetsTheRuntimeToTheSumOfAllItsParts() {
        ingest.addNew(found("Towers - pt1.m4v", 10), Playability.PLAYABLE, probe(5_000L),
            new TitleGuess("Towers", null), null);
        Edition edition = only().getEditions().get(0);
        MediaFile second = new MediaFile();
        second.setEdition(edition);
        second.setPath("Towers - pt2.m4v");
        second.setPartNumber(2);
        second.setDurationMs(7_000L);
        second.setAddedAt(CREATED);
        edition.getMediaFiles().add(second);
        em.flush();
        Long firstId = ingest.known().get("Towers - pt1.m4v").id();

        ingest.refresh(firstId, found("Towers - pt1.m4v", 11), Playability.PLAYABLE, probe(6_000L));
        em.flush();
        em.clear();

        Edition reloaded = editions.findById(edition.getId()).orElseThrow();
        assertThat(reloaded.getRuntimeMs()).isEqualTo(13_000L);
        assertThat(reloaded.originalParts()).extracting(MediaFile::getPartNumber, MediaFile::getDurationMs)
            .containsExactly(tuple(1, 6_000L), tuple(2, 7_000L));
    }

    @Test
    void refreshWithAFailedProbeKeepsTheRuntimeAndTheStreams() {
        ingest.addNew(found("A.m4v", 10), Playability.PLAYABLE, probe(5_000L), new TitleGuess("A", null), null);
        Long id = ingest.known().get("A.m4v").id();

        ingest.refresh(id, found("A.m4v", 11), Playability.UNREADABLE, null);
        em.flush();
        em.clear();

        MediaFile file = mediaFiles.findById(id).orElseThrow();
        assertThat(file.getPlayability()).isEqualTo(Playability.UNREADABLE);
        assertThat(file.getEdition().getRuntimeMs()).isEqualTo(5_000L);
        assertThat(file.getAudioStreams()).hasSize(2);
    }

    @Test
    void aRenameKeepsTheEditionAndItsWatchHistory() {
        ingest.addNew(found("Old.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Old", null), null);
        Long id = ingest.known().get("Old.m4v").id();
        Long editionId = mediaFiles.findById(id).orElseThrow().getEdition().getId();
        PlaybackState state = new PlaybackState(editionId);
        state.setPositionMs(1234);
        state.setWatched(true);
        states.save(state);

        ingest.repoint(id, found("New.m4v", 10));
        em.flush();
        em.clear();

        assertThat(ingest.known()).containsOnlyKeys("New.m4v");
        assertThat(mediaFiles.findById(id).orElseThrow().getEdition().getId()).isEqualTo(editionId);
        assertThat(states.findById(editionId).orElseThrow().getPositionMs()).isEqualTo(1234);
        assertThat(films.count()).isEqualTo(1);
    }

    @Test
    void aVanishedFileIsMarkedMissingAndItsFilmAndHistoryRemain() {
        ingest.addNew(found("Gone.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Gone", null), null);
        Long id = ingest.known().get("Gone.m4v").id();
        Long editionId = mediaFiles.findById(id).orElseThrow().getEdition().getId();
        PlaybackState state = new PlaybackState(editionId);
        state.setPositionMs(777);
        states.save(state);

        ingest.markMissing(List.of(id));
        em.flush();
        em.clear();

        assertThat(mediaFiles.findById(id).orElseThrow().getPresence()).isEqualTo(Presence.MISSING);
        assertThat(films.count()).isEqualTo(1);
        assertThat(editions.count()).isEqualTo(1);
        assertThat(states.findById(editionId).orElseThrow().getPositionMs()).isEqualTo(777);

        ingest.markPresent(id);
        em.flush();
        em.clear();

        assertThat(mediaFiles.findById(id).orElseThrow().getPresence()).isEqualTo(Presence.PRESENT);
    }

    @Test
    void aScanRunIsRecordedFromStartToFinishWithItsCounts() {
        Long id = ingest.startRun("nightly", NOW);
        ingest.finishRun(id, NOW.plusSeconds(90), 12, 3, 1, null);
        em.flush();
        em.clear();

        ScanRun run = runs.findById(id).orElseThrow();
        assertThat(run.getTrigger()).isEqualTo("nightly");
        assertThat(run.getStartedAt()).isEqualTo(NOW);
        assertThat(run.getFinishedAt()).isEqualTo(NOW.plusSeconds(90));
        assertThat(run.getFilesSeen()).isEqualTo(12);
        assertThat(run.getNewFiles()).isEqualTo(3);
        assertThat(run.getMissingFiles()).isEqualTo(1);
        assertThat(run.getError()).isNull();
    }

    @Test
    void aScanErrorIsTruncatedToFiveHundredCharacters() {
        Long id = ingest.startRun("admin", NOW);

        ingest.finishRun(id, NOW, 0, 0, 0, "E".repeat(600));
        em.flush();
        em.clear();

        assertThat(runs.findById(id).orElseThrow().getError()).hasSize(500);
    }

    @Test
    void pendingFilmsAreThoseAwaitingALookupThatCanBePlayed() {
        ingest.addNew(found("Pending.m4v", 10), Playability.PLAYABLE, probe(90 * 60_000L),
            new TitleGuess("Pending", 2001), null);
        ingest.addNew(found("NoYear.m4v", 11), Playability.PLAYABLE, probe(60_000L), new TitleGuess("NoYear", null),
            null);
        ingest.addNew(found("Drm.m4v", 12), Playability.PROTECTED, probe(1), new TitleGuess("Drm", null), null);
        ingest.addNew(found("Review.m4v", 13), Playability.PLAYABLE, probe(1), new TitleGuess("Review", null),
            new MatchOutcome(Status.NEEDS_MATCH, null, List.of()));
        ingest.addNew(found("Done.m4v", 14), Playability.PLAYABLE, probe(1), new TitleGuess("Done", null),
            matched(movie(1, "Done", 2000, null)));
        em.flush();
        em.clear();

        List<Pending> pending = ingest.pending();

        assertThat(pending).extracting(Pending::title, Pending::year, Pending::runtimeMs)
            .containsExactly(tuple("NoYear", null, 60_000L),
                tuple("Pending", 2001, 90 * 60_000L));
        assertThat(pending.get(0).filmId()).isNotNull();
    }

    @Test
    void aFilmWithNoEditionsIsNotRetried() {
        Film film = new Film();
        film.setTitleAndSort("Empty");
        film.setYear(2000);
        film.setAddedAt(NOW);
        film.setMatchStatus(MatchStatus.PENDING);
        films.save(film);

        assertThat(ingest.pending()).isEmpty();
    }

    @Test
    void aRetriedLookupThatFindsTheFilmFillsItInAndReplacesItsOldCredits() {
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", null), null);
        Long filmId = ingest.pending().get(0).filmId();

        ingest.applyOutcome(filmId, matched(movie(949, "Heat", 1995, null)));
        ingest.applyOutcome(filmId, matched(movie(949, "Heat", 1995, null)));
        em.flush();
        em.clear();

        Film film = films.findById(filmId).orElseThrow();
        assertThat(film.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(film.getTmdbId()).isEqualTo(949);
        assertThat(film.getYear()).isEqualTo(1995);
        assertThat(film.getCredits()).as("replaced, not doubled").hasSize(3);
        assertThat(ingest.pending()).isEmpty();
    }

    @Test
    void aRetriedLookupWithNoConfidentAnswerStoresTheCandidates() {
        ingest.addNew(found("Dune.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Dune", null), null);
        Long filmId = ingest.pending().get(0).filmId();

        ingest.applyOutcome(filmId, new MatchOutcome(Status.NEEDS_MATCH, null,
            List.of(new Candidate(1, "Dune", 1984, null, null, 40))));
        em.flush();
        em.clear();

        Film film = films.findById(filmId).orElseThrow();
        assertThat(film.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(film.getMatchCandidates()).contains("\"tmdbId\":1");
    }

    @Test
    void aRetriedLookupThatFindsNothingBecauseTmdbIsDownChangesNothing() {
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", null), null);
        Long filmId = ingest.pending().get(0).filmId();

        ingest.applyOutcome(filmId, new MatchOutcome(Status.UNAVAILABLE, null, List.of()));
        em.flush();
        em.clear();

        assertThat(films.findById(filmId).orElseThrow().getMatchStatus()).isEqualTo(MatchStatus.PENDING);
    }

    @Test
    void aRetriedFileThatMatchesAFilmWeAlreadyHaveJoinsItAsAnEditionKeepingItsHistory() {
        MovieDetails heat = movie(949, "Heat", 1995, null);
        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            matched(heat));
        ingest.addNew(found("Heat - HD.m4v", 11), Playability.PLAYABLE, probe(1), new TitleGuess("Heat - HD", null),
            null);
        Long orphanFilmId = ingest.pending().get(0).filmId();
        Long copyFileId = ingest.known().get("Heat - HD.m4v").id();
        Long copyEditionId = mediaFiles.findById(copyFileId).orElseThrow().getEdition().getId();
        PlaybackState state = new PlaybackState(copyEditionId);
        state.setPositionMs(4242);
        states.save(state);
        Long keeperId = films.findFirstByTmdbIdOrderByIdAsc(949L).orElseThrow().getId();

        ingest.applyOutcome(orphanFilmId, matched(heat));
        em.flush();
        em.clear();

        assertThat(films.findAll()).extracting(Film::getId).containsExactly(keeperId);
        Film keeper = films.findById(keeperId).orElseThrow();
        assertThat(keeper.getEditions()).extracting(Edition::getId).contains(copyEditionId).hasSize(2);
        assertThat(states.findById(copyEditionId).orElseThrow().getPositionMs()).isEqualTo(4242);
    }

    @Test
    void aCollectionAndPartWithNoNamesGetPlaceholders() {
        when(tmdb.collection(55)).thenReturn(Optional.of(new CollectionDetails(55, null, null,
            List.of(new Part(1, null, null, null)))));

        ingest.addNew(found("Heat.m4v", 10), Playability.PLAYABLE, probe(1), new TitleGuess("Heat", 1995),
            matched(movie(949, "Heat", 1995, new CollectionRef(55, null, null))));

        FilmCollection collection = only().getCollection();
        assertThat(collection.getName()).isEqualTo("Collection");
        assertThat(collection.getParts()).singleElement().satisfies(p -> assertThat(p.getTitle())
            .isEqualTo("Untitled"));
    }
}
