package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsApplier;
import gregochr.cinefilm.credits.CreditsApplier.PassResult;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The credits apply pass on PostgreSQL: it sets, clears and keeps markers, a second pass writes nothing, and a pass
 * costs a fixed number of statements however many editions a batch holds. Not transactional: the pass runs its own
 * transactions, so the seeded rows are committed. The database is fresh per class and shared only by this class's
 * tests; each test leaves every edition it seeded settled, so the other's first pass changes only its own.
 */
class CreditsApplierIntegrationTest extends IntegrationTestBase {

    private static final String FINGERPRINT = "4000000000:" + NOW.toEpochMilli();

    @Autowired
    private CreditsApplier applier;

    @Autowired
    private FilmRepository films;

    @Autowired
    private PlexCreditsMarkerRepository markers;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory factory;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    /** A saved edition of one 100-minute file whose fingerprint is {@link #FINGERPRINT}; returns the edition. */
    private Edition seeded(String title, Long marker, CreditsMarkerSource source) {
        Film film = film(null, title, 2008, NOW);
        Edition edition = edition(film, null, "Original", 100 * MIN, marker);
        edition.setCreditsMarkerSource(source);
        MediaFile file = file(edition, title + ".m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        return films.saveAndFlush(film).getEditions().get(0);
    }

    private static Long fileId(Edition edition) {
        return edition.originalParts().get(0).getId();
    }

    private void plexRow(Edition edition, String fingerprint, long startMs) {
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(fileId(edition));
        row.setFingerprint(fingerprint);
        row.setStartMs(startMs);
        row.setImportedAt(NOW);
        markers.saveAndFlush(row);
    }

    private Map<String, Object> stored(Edition edition) {
        return jdbc.queryForMap("select credits_marker_ms, credits_marker_source from edition where id = ?",
            edition.getId());
    }

    @Test
    void aPassSetsClearsAndKeepsAndASecondPassWritesNothing() {
        Edition toSet = seeded("Apply Set", null, null);
        plexRow(toSet, FINGERPRINT, 5_000_000);
        Edition atMarker = seeded("Apply AtMarker", null, null);
        plexRow(atMarker, FINGERPRINT, 5_000_000);
        Edition justBefore = seeded("Apply JustBefore", null, null);
        plexRow(justBefore, FINGERPRINT, 5_000_000);
        Edition hidden = seeded("Apply Hidden", null, null);
        plexRow(hidden, FINGERPRINT, 5_000_000);
        Edition toClear = seeded("Apply Clear", 5_500_000L, CreditsMarkerSource.AUTO);
        plexRow(toClear, "1:2", 5_000_000);
        Edition owner = seeded("Apply Owner", 5_500_000L, CreditsMarkerSource.OWNER);
        plexRow(owner, FINGERPRINT, 5_000_000);
        Edition same = seeded("Apply Same", 5_000_000L, CreditsMarkerSource.PLEX);
        plexRow(same, FINGERPRINT, 5_000_000);
        states.save(inProgress(toSet, 5_200_000, false));
        states.save(inProgress(atMarker, 5_000_000, false));
        states.save(inProgress(justBefore, 4_999_999, false));
        states.save(inProgress(hidden, 5_200_000, true));
        states.save(inProgress(toClear, 5_200_000, false));

        PassResult first = applier.runPass();

        assertThat(first.lockTaken()).isTrue();
        assertThat(first.changed()).isEqualTo(5);
        assertThat(first.cleared()).isEqualTo(1);
        assertThat(first.inProgressPastMarker()).isEqualTo(2);
        assertThat(stored(toSet)).containsEntry("credits_marker_ms", 5_000_000L)
            .containsEntry("credits_marker_source", "PLEX");
        assertThat(stored(toClear)).containsEntry("credits_marker_ms", null)
            .containsEntry("credits_marker_source", null);
        assertThat(stored(owner)).containsEntry("credits_marker_ms", 5_500_000L)
            .containsEntry("credits_marker_source", "OWNER");
        assertThat(stored(same)).containsEntry("credits_marker_ms", 5_000_000L)
            .containsEntry("credits_marker_source", "PLEX");

        PassResult second = applier.runPass();

        assertThat(second.lockTaken()).isTrue();
        assertThat(second.changed()).isZero();
        assertThat(second.inProgressPastMarker()).isZero();
        assertThat(second.unchanged()).isEqualTo(first.changed() + first.unchanged());
    }

    private static PlaybackState inProgress(Edition edition, long positionMs, boolean hidden) {
        PlaybackState state = new PlaybackState(edition.getId());
        state.setPositionMs(positionMs);
        state.setHiddenFromContinueWatching(hidden);
        return state;
    }

    /** The statements a pass that changes nothing prepares: reads only, so they must not grow with the editions. */
    private long statementsOfOnePass() {
        statistics.clear();
        PassResult result = applier.runPass();
        assertThat(result.lockTaken()).isTrue();
        assertThat(result.changed()).isZero();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void aPassCostsTheSameStatementsForMoreEditionsInOneBatch() {
        Edition one = seeded("Count One", null, null);
        plexRow(one, FINGERPRINT, 5_000_000);
        applier.runPass();
        long withOne = statementsOfOnePass();

        for (String title : List.of("Count Two", "Count Three", "Count Four")) {
            Edition more = seeded(title, null, null);
            plexRow(more, FINGERPRINT, 5_000_000);
        }
        applier.runPass();
        long withMore = statementsOfOnePass();

        assertThat(withOne).isPositive();
        assertThat(withMore).isEqualTo(withOne);
    }
}
