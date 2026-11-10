package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.EditionJoin;
import gregochr.cinefilm.library.LibraryGrouping;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.transcode.MediaKeyframes;
import gregochr.cinefilm.transcode.MediaKeyframesRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The grouping statements on PostgreSQL, committed like production: the bulk file move, the delete order (playback
 * state before the editions it references) and the queries the scan's grouping passes read the library with.
 */
class EditionJoinIntegrationTest extends IntegrationTestBase {

    private static final Instant MONDAY = NOW.minus(Duration.ofDays(5));

    @Autowired
    private EditionJoin join;

    @Autowired
    private LibraryGrouping grouping;

    @Autowired
    private FilmRepository films;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private FilmPreferenceRepository preferences;

    @Autowired
    private MediaKeyframesRepository keyframes;

    @Autowired
    private GroupingChangeRepository changes;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoBean
    private TmdbApi tmdb;

    @BeforeEach
    void clean() {
        changes.deleteAll();
        preferences.deleteAll();
        states.deleteAll();
        films.deleteAll();
    }

    private Film film(String title, MatchStatus status, String editionName, long minutes, String path) {
        Film film = TestData.film(null, title, 2002, NOW);
        film.setMatchStatus(status);
        TestData.file(TestData.edition(film, null, editionName, minutes * MIN, null), path);
        return films.saveAndFlush(film);
    }

    private <T> T read(java.util.function.Supplier<T> reader) {
        return new TransactionTemplate(transactions).execute(status -> reader.get());
    }

    @Test
    void filesMoveByOneBulkUpdateKeepingTheirIdsStreamsAndKeyframesAndTheOrderOfDeletesSatisfiesTheForeignKeys() {
        Film one = film("Towers", MatchStatus.MATCHED, "Extended", 90, "Towers - pt1.m4v");
        Film two = film("Towers - pt2", MatchStatus.NEEDS_MATCH, "Extended", 110, "Towers - pt2.m4v");
        Edition survivor = one.getEditions().get(0);
        Edition absorbed = two.getEditions().get(0);
        Long first = survivor.getMediaFiles().get(0).getId();
        Long second = absorbed.getMediaFiles().get(0).getId();
        List<Long> streamIds = read(() -> mediaFiles.findById(second).orElseThrow().getAudioStreams().stream()
            .map(AudioStream::getId).toList());
        keyframes.save(new MediaKeyframes(second, "0,5000", 5000, true, 10, 20, NOW, 24.0));
        states.save(TestData.state(absorbed, 25 * MIN, false, MONDAY, null));
        FilmPreference preference = new FilmPreference(two.getId());
        preference.setLastEditionId(absorbed.getId());
        preferences.save(preference);

        join.join(List.of(first, second));

        assertThat(films.findAll()).extracting(Film::getId).containsExactly(one.getId());
        assertThat(editions.findById(absorbed.getId())).isEmpty();
        assertThat(states.findById(absorbed.getId())).isEmpty();
        PlaybackState folded = states.findById(survivor.getId()).orElseThrow();
        assertThat(folded.getPositionMs()).isEqualTo(115 * MIN);
        assertThat(mediaFiles.findById(second).orElseThrow().getPartNumber()).isEqualTo(2);
        assertThat(read(() -> editions.findById(survivor.getId()).orElseThrow().getRuntimeMs())).isEqualTo(200 * MIN);
        assertThat(read(() -> mediaFiles.findById(second).orElseThrow().getAudioStreams().stream()
            .map(AudioStream::getId).toList())).containsExactlyElementsOf(streamIds);
        assertThat(read(() -> mediaFiles.findById(second).orElseThrow().getEdition().getId()))
            .isEqualTo(survivor.getId());
        assertThat(keyframes.findById(second)).isPresent();
        assertThat(preferences.findById(one.getId()).orElseThrow().getLastEditionId()).isEqualTo(survivor.getId());
    }

    @Test
    void anEditionSplitIsMovedByTheSameBulkStatement() {
        Film film = TestData.film(null, "Towers", 2002, NOW);
        Edition edition = TestData.edition(film, null, "Extended", 90 * MIN, null);
        TestData.file(edition, "Towers - pt1.m4v");
        MediaFile second = TestData.file(edition, "Towers - pt2.m4v");
        second.setDurationMs(110 * MIN);
        second.setPartNumber(2);
        films.saveAndFlush(film);
        Long editionId = edition.getId();
        Long firstId = edition.getMediaFiles().get(0).getId();
        Long secondId = second.getId();
        states.save(TestData.state(edition, 100 * MIN, false, MONDAY, null));

        List<Long> ids = join.unfold(editionId, List.of(List.of(firstId), List.of(secondId)));

        assertThat(ids).hasSize(2);
        assertThat(read(() -> mediaFiles.findById(secondId).orElseThrow().getEdition().getId()))
            .isEqualTo(ids.get(1));
        assertThat(states.findById(ids.get(1)).orElseThrow().getPositionMs()).isEqualTo(10 * MIN);
        assertThat(states.findById(editionId)).isEmpty();
    }

    @Test
    void theGroupingPassesJoinFilmsAndPartsAndChooseTheDefaultOnPostgres() {
        film("Gladiator", MatchStatus.PENDING, "Extended Cut", 175, "Gladiator (2000) {edition-Extended Cut}.m4v");
        film("Gladiator", MatchStatus.PENDING, "Theatrical Cut", 155,
            "Gladiator (2000) {edition-Theatrical Cut}.m4v");
        film("Towers", MatchStatus.MATCHED, "Original", 90, "Towers - pt1.m4v");
        film("Towers 2", MatchStatus.PENDING, "Original", 110, "Towers - pt2.m4v");

        grouping.repair();
        int grouped = grouping.group();
        int defaults = grouping.refreshDefaults();

        assertThat(grouped).isEqualTo(2);
        assertThat(defaults).isEqualTo(1);
        assertThat(films.count()).isEqualTo(2);
        assertThat(changes.findAll()).extracting(GroupingChange::getKind).containsOnly(GroupingChange.JOIN);
        Film gladiator = films.findAll().stream().filter(f -> f.getTitle().equals("Gladiator")).findFirst()
            .orElseThrow();
        Long defaultId = preferences.findById(gladiator.getId()).orElseThrow().getDefaultEditionId();
        assertThat(read(() -> editions.findById(defaultId).orElseThrow().getName())).isEqualTo("Theatrical Cut");
        assertThat(grouping.group()).isZero();
        assertThat(grouping.refreshDefaults()).isZero();
    }

    @Test
    void aFilmWithTwoTitlesIsSplitOnPostgres() {
        Film rocky = TestData.film(null, "Rocky IV", 1985, NOW);
        rocky.setMatchStatus(MatchStatus.MATCHED);
        rocky.setTmdbId(1001L);
        TestData.file(TestData.edition(rocky, null, "Original", 91 * MIN, null), "Rocky IV (1985).m4v");
        Edition five = TestData.edition(rocky, null, "Original", 104 * MIN, null);
        TestData.file(five, "Rocky V (1990).m4v");
        films.saveAndFlush(rocky);
        states.save(TestData.state(five, 30 * MIN, false, MONDAY, null));

        assertThat(grouping.repair()).isEqualTo(1);

        assertThat(films.count()).isEqualTo(2);
        Film moved = films.findAll().stream().filter(f -> f.getTitle().equals("Rocky V")).findFirst().orElseThrow();
        assertThat(read(() -> films.findById(moved.getId()).orElseThrow().getEditions().get(0).getId()))
            .isEqualTo(five.getId());
        assertThat(states.findById(five.getId()).orElseThrow().getPositionMs()).isEqualTo(30 * MIN);
        assertThat(grouping.repair()).isZero();
    }

    @Test
    void theLibraryQueriesTheGroupingPassesUse() {
        film("Heat", MatchStatus.MATCHED, "Original", 170, "Heat.m4v");
        film("Alien", MatchStatus.MATCHED, "Original", 117, "Movies/Alien.m4v");
        film("Dune", MatchStatus.MATCHED, "Original", 155, "Movies/Old/Dune.m4v");
        Film two = film("Gladiator", MatchStatus.MATCHED, "Extended", 175, "Gladiator.m4v");
        Edition other = TestData.edition(two, null, "Theatrical", 155, null);
        TestData.file(other, "Gladiator - theatrical.m4v");
        films.saveAndFlush(two);

        assertThat(mediaFiles.findAllOriginalsWithEdition()).extracting(MediaFile::getPath)
            .containsExactlyInAnyOrder("Gladiator - theatrical.m4v", "Gladiator.m4v", "Heat.m4v",
                "Movies/Alien.m4v", "Movies/Old/Dune.m4v");
        assertThat(mediaFiles.findRootOriginals()).extracting(MediaFile::getPath)
            .containsExactlyInAnyOrder("Heat.m4v", "Gladiator.m4v", "Gladiator - theatrical.m4v");
        assertThat(mediaFiles.findOriginalsUnder("Movies/%")).extracting(MediaFile::getPath)
            .containsExactlyInAnyOrder("Movies/Alien.m4v", "Movies/Old/Dune.m4v");
        assertThat(films.findWithSeveralEditions()).extracting(Film::getTitle).containsExactly("Gladiator");
        changes.save(new GroupingChange(NOW, GroupingChange.FLAG, "Files look like different films"));
        assertThat(changes.existsByKindAndSummary(GroupingChange.FLAG, "Files look like different films")).isTrue();
        assertThat(changes.existsByKindAndSummary(GroupingChange.JOIN, "Files look like different films")).isFalse();
    }
}
