package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryIngest.Pending;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** What a new file becomes: its edition name, part number, and whether TMDB may fold it into a film we have. */
class LibraryIngestGroupingTest extends ApiTestBase {

    private static final Instant CREATED = NOW.minus(Duration.ofDays(3));

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private EntityManager em;

    @MockitoBean
    private TmdbApi tmdb;

    private static FoundFile found(String path) {
        return new FoundFile(path, 10, CREATED, CREATED, false);
    }

    private static ProbeResult probe(long durationMs) {
        return new ProbeResult(durationMs, "mp4", "H.264", 1920, 800, 9_000_000L, List.of(), List.of(),
            new ProbeResult.Tags(null, null, null, null, null), false, "yuv420p");
    }

    private static MovieDetails movie(long id, String title) {
        return new MovieDetails(id, title, title, 1995, "Overview", 170, List.of(), "/p.jpg", "/b.jpg", 1,
            List.of(), List.of(), null);
    }

    private static MatchOutcome matched(MovieDetails movie) {
        return new MatchOutcome(Status.MATCHED, movie, List.of());
    }

    private void add(String path, long minutes, MatchOutcome outcome) {
        ingest.addNew(found(path), Playability.PLAYABLE, probe(minutes * 60_000L),
            TitleGuess.of(null, path.substring(path.lastIndexOf('/') + 1), null), outcome);
    }

    private List<Film> all() {
        em.flush();
        em.clear();
        return films.findAll();
    }

    private Edition editionOf(String path) {
        em.flush();
        em.clear();
        return mediaFiles.findAll().stream().filter(f -> f.getPath().equals(path)).findFirst().orElseThrow()
            .getEdition();
    }

    // ---- the edition a file becomes

    @Test
    void anEditionTagNamesTheEdition() {
        add("Gladiator (2000) {edition-Extended Cut}.m4v", 175, null);

        assertThat(editionOf("Gladiator (2000) {edition-Extended Cut}.m4v").getName()).isEqualTo("Extended Cut");
    }

    @Test
    void aPlainEditionWordInTheTitleNamesTheEditionWhenThereIsNoTag() {
        add("Alien Director's Cut.m4v", 116, null);

        assertThat(editionOf("Alien Director's Cut.m4v").getName()).isEqualTo("Director's Cut");
    }

    @Test
    void anUntaggedFileIsOriginalAndPartOne() {
        add("Heat.m4v", 170, null);

        Edition edition = editionOf("Heat.m4v");

        assertThat(edition.getName()).isEqualTo("Original");
        assertThat(edition.originalFile().getPartNumber()).isEqualTo(1);
        assertThat(edition.getGroupingSource()).isEqualTo(GroupingSource.AUTO);
    }

    @Test
    void aPartSuffixSetsThePartNumber() {
        add("Towers - pt2.m4v", 110, null);

        assertThat(editionOf("Towers - pt2.m4v").originalFile().getPartNumber()).isEqualTo(2);
    }

    // ---- the guard on a TMDB-id fold

    @Test
    void aFileWhoseTmdbMatchIsAFilmWithADifferentTitleIsNotFoldedIntoItAndKeepsTheMatchAsACandidate() {
        MovieDetails heat = movie(949, "Heat");
        add("Heat.m4v", 170, matched(heat));
        add("Heat 2.m4v", 120, matched(heat));

        List<Film> all = all();

        assertThat(all).hasSize(2);
        Film second = all.stream().filter(f -> f.getTmdbId() == null).findFirst().orElseThrow();
        assertThat(second.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(second.getMatchCandidates()).contains("\"tmdbId\":949").contains("\"title\":\"Heat\"")
            .contains("\"score\":60");
        assertThat(second.getEditions()).hasSize(1);
        assertThat(all.stream().filter(f -> f.getTmdbId() != null).findFirst().orElseThrow().getEditions())
            .hasSize(1);
    }

    @Test
    void aRefusedFoldKeepsTheCandidatesTheMatcherAlreadyRanked() {
        MovieDetails heat = movie(949, "Heat");
        add("Heat.m4v", 170, matched(heat));
        add("Heat 2.m4v", 120, new MatchOutcome(Status.MATCHED, heat,
            List.of(new Candidate(949, "Heat", 1995, 170, "/p.jpg", 85), new Candidate(950, "Heat 2", null, 120, null,
                40))));

        Film second = all().stream().filter(f -> f.getTmdbId() == null).findFirst().orElseThrow();

        assertThat(second.getMatchCandidates()).contains("\"score\":85").contains("\"tmdbId\":950");
    }

    @Test
    void aFileWithTheSameTitleStillFoldsIntoTheFilm() {
        MovieDetails heat = movie(949, "Heat");
        add("Heat.m4v", 170, matched(heat));
        add("Heat - HD.m4v", 170, matched(heat));

        assertThat(all()).singleElement().satisfies(f -> assertThat(f.getEditions()).hasSize(2));
    }

    @Test
    void aFilmTheOwnerSettledAcceptsAnyFileTmdbMatchesToIt() {
        MovieDetails heat = movie(949, "Heat");
        add("Heat.m4v", 170, matched(heat));
        Film settled = films.findFirstByTmdbIdOrderByIdAsc(949L).orElseThrow();
        settled.setEditionsConfirmed(true);
        films.saveAndFlush(settled);
        add("Heat 2.m4v", 120, matched(heat));

        assertThat(all()).singleElement().satisfies(f -> assertThat(f.getEditions()).hasSize(2));
    }

    @Test
    void theGuardPicksTheFirstFilmWithTheRightTitleWhenSeveralShareATmdbId() {
        MovieDetails heat = movie(949, "Heat");
        Film wrong = films.saveAndFlush(film("Other", "Other.m4v", 949L));
        Film right = films.saveAndFlush(film("Heat", "Heat - HD.m4v", 949L));
        add("Heat - SD.m4v", 170, matched(heat));

        em.flush();
        em.clear();

        assertThat(films.findById(wrong.getId()).orElseThrow().getEditions()).hasSize(1);
        assertThat(films.findById(right.getId()).orElseThrow().getEditions()).hasSize(2);
    }

    private Film film(String title, String path, Long tmdbId) {
        Film film = gregochr.cinefilm.TestData.film(null, title, 1995, NOW);
        film.setTmdbId(tmdbId);
        gregochr.cinefilm.TestData.file(gregochr.cinefilm.TestData.edition(film, null, "Original",
            100 * 60_000L, null), path);
        return film;
    }

    // ---- the guard on a retried match

    @Test
    void aRetriedFileWhoseMatchIsAFilmWithADifferentTitleIsLeftUnmatchedWithTheCandidate() {
        MovieDetails heat = movie(949, "Heat");
        add("Heat.m4v", 170, matched(heat));
        add("Heat 2.m4v", 120, null);
        Long pending = ingest.pending().get(0).filmId();

        ingest.applyOutcome(pending, matched(heat));
        Film refused = all().stream().filter(f -> f.getId().equals(pending)).findFirst().orElseThrow();

        assertThat(films.count()).isEqualTo(2);
        assertThat(refused.getMatchStatus()).isEqualTo(MatchStatus.NEEDS_MATCH);
        assertThat(refused.getTmdbId()).isNull();
        assertThat(refused.getMatchCandidates()).contains("\"tmdbId\":949");
        assertThat(ingest.pending()).isEmpty();
    }

    @Test
    void aRetriedFileMergesIntoTheFirstFilmWithAnAcceptableTitle() {
        MovieDetails heat = movie(949, "Heat");
        films.saveAndFlush(film("Other", "Other.m4v", 949L));
        Film right = films.saveAndFlush(film("Heat", "Heat.m4v", 949L));
        add("Heat - HD.m4v", 170, null);
        Long pending = ingest.pending().get(0).filmId();

        ingest.applyOutcome(pending, matched(heat));
        em.flush();
        em.clear();

        assertThat(films.findById(pending)).isEmpty();
        assertThat(films.findById(right.getId()).orElseThrow().getEditions()).hasSize(2);
    }

    @Test
    void aRetriedFileThatMatchesNoOtherFilmIsSimplyMatched() {
        add("Heat.m4v", 170, null);
        Long pending = ingest.pending().get(0).filmId();

        ingest.applyOutcome(pending, matched(movie(949, "Heat")));

        assertThat(all()).singleElement().satisfies(f -> {
            assertThat(f.getTmdbId()).isEqualTo(949L);
            assertThat(f.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        });
    }

    // ---- another part of an edition we already have

    @Test
    void aPartTwoOfAnEditionWeHaveJoinsItsFilmWithoutALookupAndIsAnEditionOfItUntilTheGroupingPassJoinsIt() {
        add("LOTR/Towers - pt1.m4v", 90, matched(movie(121, "Towers")));

        assertThat(ingest.isPartOfKnownEdition("LOTR/Towers - pt2.m4v")).isTrue();
        add("LOTR/Towers - pt2.m4v", 110, null);

        List<Film> all = all();
        assertThat(all).singleElement().satisfies(f -> {
            assertThat(f.getTmdbId()).isEqualTo(121L);
            assertThat(f.getEditions()).hasSize(2);
        });
        assertThat(ingest.known()).containsOnlyKeys("LOTR/Towers - pt1.m4v", "LOTR/Towers - pt2.m4v");
    }

    @Test
    void aPartTwoInTheLibraryRootIsRecognisedToo() {
        add("Towers - pt1.m4v", 90, null);

        assertThat(ingest.isPartOfKnownEdition("Towers - pt2.m4v")).isTrue();
    }

    @Test
    void aFileIsNotAPartOfAnEditionWhenItsNumberIsTaken() {
        add("Towers - pt1.m4v", 90, null);

        assertThat(ingest.isPartOfKnownEdition("Towers - pt1.mkv")).isFalse();
    }

    @Test
    void anUntaggedFileIsNeverAPartOfAnEdition() {
        add("Towers - pt1.m4v", 90, null);

        assertThat(ingest.isPartOfKnownEdition("Towers.m4v")).isFalse();
    }

    @Test
    void aPartWhoseEditionTagFolderOrTitleDiffersIsNotAPartOfIt() {
        add("A/Towers {edition-Extended} - pt1.m4v", 90, null);

        assertThat(ingest.isPartOfKnownEdition("A/Towers {edition-Theatrical} - pt2.m4v")).isFalse();
        assertThat(ingest.isPartOfKnownEdition("B/Towers {edition-Extended} - pt2.m4v")).isFalse();
        assertThat(ingest.isPartOfKnownEdition("A/Fellowship {edition-Extended} - pt2.m4v")).isFalse();
        assertThat(ingest.isPartOfKnownEdition("Towers {edition-Extended} - pt2.m4v")).isFalse();
        assertThat(ingest.isPartOfKnownEdition("A/Towers {edition-Extended} - pt2.m4v")).isTrue();
    }

    @Test
    void anEditionWithAnUntaggedFileInItIsNotOneToAddPartsTo() {
        Film film = film("Towers", "Towers - pt1.m4v", null);
        Edition edition = film.getEditions().get(0);
        MediaFile stray = gregochr.cinefilm.TestData.file(edition, "Towers - extra.m4v");
        stray.setPartNumber(2);
        films.saveAndFlush(film);

        assertThat(ingest.isPartOfKnownEdition("Towers - pt3.m4v")).isFalse();
    }

    @Test
    void anEditionNotGroupedByTheScanOrOfASettledFilmIsNotOneToAddPartsTo() {
        Film plex = film("Towers", "Towers - pt1.m4v", null);
        plex.getEditions().get(0).setGroupingSource(GroupingSource.PLEX);
        films.saveAndFlush(plex);
        Film settled = film("Fellowship", "Fellowship - pt1.m4v", null);
        settled.setEditionsConfirmed(true);
        films.saveAndFlush(settled);

        assertThat(ingest.isPartOfKnownEdition("Towers - pt2.m4v")).isFalse();
        assertThat(ingest.isPartOfKnownEdition("Fellowship - pt2.m4v")).isFalse();
    }

    @Test
    void aPendingFilmCarriesTheIdTagsOfItsFirstFile() {
        add("Heat {tmdb-949}.m4v", 170, null);
        add("Alien {imdb-tt0078748}.m4v", 117, null);
        add("Dune.m4v", 155, null);

        assertThat(ingest.pending()).extracting(Pending::title, Pending::tmdbId, Pending::imdbId)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Heat {tmdb-949}", 949L, null),
                org.assertj.core.groups.Tuple.tuple("Alien {imdb-tt0078748}", null, "tt0078748"),
                org.assertj.core.groups.Tuple.tuple("Dune", null, null));
    }

    // ---- searching

    @Test
    void aPartNumberedFilmIsSearchedWithADurationOfZeroButAWholeFilmWithItsRuntime() {
        add("Towers - pt1.m4v", 90, null);
        add("Heat.m4v", 170, null);

        List<Pending> pending = ingest.pending();

        assertThat(pending).extracting(Pending::title, Pending::runtimeMs).containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("Towers - pt1", 0L),
            org.assertj.core.groups.Tuple.tuple("Heat", 170 * 60_000L));
    }

    @Test
    void theGuessReadsTheTitleThroughTheFileNameParserUnlessTheFileHasItsOwnTitleTag() {
        FileNameParser.ParsedName parsed = FileNameParser.parse("Rocky.IV.1985.1080p.BluRay.x264-SPARKS.mkv");

        assertThat(TitleGuess.of(null, "Rocky.IV.1985.1080p.BluRay.x264-SPARKS.mkv", null, parsed))
            .isEqualTo(new TitleGuess("Rocky IV", 1985));
        assertThat(TitleGuess.of("", "x.mkv", 1999, FileNameParser.parse("Alien.m4v")))
            .isEqualTo(new TitleGuess("Alien", 1999));
        assertThat(TitleGuess.of("Embedded", "Alien (1979).m4v", 2001, FileNameParser.parse("Alien (1979).m4v")))
            .isEqualTo(new TitleGuess("Embedded", 1979));
    }
}
