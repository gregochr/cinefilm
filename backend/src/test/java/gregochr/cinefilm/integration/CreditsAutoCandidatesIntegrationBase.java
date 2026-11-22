package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.credits.CreditsAutoTestData.crawl;
import static gregochr.cinefilm.credits.CreditsAutoTestData.tmdbOn;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsAdminDtos.AutoCandidate;
import gregochr.cinefilm.credits.CreditsApplier;
import gregochr.cinefilm.credits.CreditsAutoCandidates;
import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsFinder;
import gregochr.cinefilm.credits.learn.Side;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The candidates list against the apply pass on PostgreSQL: one fixture, and every candidate not flagged
 * {@code owner} is exactly an edition the pass sets to {@code AUTO}, with the marker the pass sets. The fixture holds
 * a visible and a hidden crawl, a card, an owner edition (a candidate, flagged), an edition with a usable Plex row
 * (no candidate; the pass sets {@code PLEX}), and a test-side edition (denied). The two concrete classes run it with
 * cards on and off (each has its own database).
 */
abstract class CreditsAutoCandidatesIntegrationBase extends IntegrationTestBase {

    private static final String FINGERPRINT = "4000000000:" + NOW.toEpochMilli();
    private static final String DENY_FILE = "# cinefilm-deny 2026-11-13 1\nfile 987654321\n";

    /** Switches AUTO on with a readable deny file, shared by both classes. */
    @DynamicPropertySource
    static void autoOn(DynamicPropertyRegistry registry) throws IOException {
        Path file = Files.createTempFile("cinefilm-deny", ".txt");
        file.toFile().deleteOnExit();
        Files.writeString(file, DENY_FILE);
        registry.add("cinefilm.credits.auto.enabled", () -> "true");
        registry.add("cinefilm.credits.auto.denylist", file::toString);
    }

    private static int nextDevTmdb = 1;

    @Autowired
    private CreditsApplier applier;

    @Autowired
    private CreditsAutoCandidates candidates;

    @Autowired
    private FilmRepository films;

    @Autowired
    private CreditsDetectionRepository detections;

    @Autowired
    private PlexCreditsMarkerRepository markers;

    @Autowired
    private JdbcTemplate jdbc;

    /** Whether the concrete class runs with cards on. */
    abstract boolean cardsOn();

    private Edition seeded(String title, long tmdbId, boolean hidden, CreditsMarkerSource source, Long marker) {
        Film film = film(null, title, 2008, NOW);
        film.setTmdbId(tmdbId);
        Edition edition = edition(film, null, "Original", 100 * MIN, marker);
        edition.setCreditsMarkerSource(source);
        MediaFile file = file(edition, title + ".m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        file.setPresence(hidden ? Presence.MISSING : Presence.PRESENT);
        return films.saveAndFlush(film).getEditions().get(0);
    }

    private static long fileId(Edition edition) {
        return edition.originalParts().get(0).getId();
    }

    private void crawlFor(Edition edition) {
        detections.saveAndFlush(crawl(fileId(edition), FINGERPRINT, 5_000_000));
    }

    private void cardFor(Edition edition) {
        CreditsDetection row = crawl(fileId(edition), FINGERPRINT, 5_000_000);
        row.setReasons(CreditsFinder.CARD_OPENING + "01:23:20");
        detections.saveAndFlush(row);
    }

    @Test
    void theCandidatesNotFlaggedOwnerAreExactlyWhatThePassSetsToAuto() {
        Edition visible = seeded("Cand Visible", tmdbOn(Side.DEV, nextDevTmdb++), false, null, null);
        crawlFor(visible);
        Edition hidden = seeded("Cand Hidden", tmdbOn(Side.DEV, nextDevTmdb++), true, null, null);
        crawlFor(hidden);
        Edition card = seeded("Cand Card", tmdbOn(Side.DEV, nextDevTmdb++), false, null, null);
        cardFor(card);
        Edition owner = seeded("Cand Owner", tmdbOn(Side.DEV, nextDevTmdb++), false, CreditsMarkerSource.OWNER,
            4_000_000L);
        crawlFor(owner);
        Edition plex = seeded("Cand Plex", tmdbOn(Side.DEV, nextDevTmdb++), false, null, null);
        crawlFor(plex);
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(fileId(plex));
        row.setFingerprint(FINGERPRINT);
        row.setStartMs(4_000_000);
        row.setImportedAt(NOW);
        markers.saveAndFlush(row);
        Edition denied = seeded("Cand Denied", tmdbOn(Side.TEST, 1), false, null, null);
        crawlFor(denied);

        applier.runPass();
        List<AutoCandidate> found = candidates.candidates();

        Map<Long, Long> autoMarkers = jdbc.queryForList(
                "select id, credits_marker_ms from edition where credits_marker_source = 'AUTO'").stream()
            .collect(Collectors.toMap(r -> ((Number) r.get("id")).longValue(),
                r -> ((Number) r.get("credits_marker_ms")).longValue()));
        Map<Long, Long> notOwner = found.stream().filter(c -> !c.owner())
            .collect(Collectors.toMap(AutoCandidate::editionId, AutoCandidate::markerMs));
        assertThat(notOwner).isEqualTo(autoMarkers);
        Set<Long> expected = cardsOn()
            ? Set.of(visible.getId(), hidden.getId(), card.getId())
            : Set.of(visible.getId(), hidden.getId());
        assertThat(notOwner.keySet()).isEqualTo(expected);
        assertThat(found).filteredOn(AutoCandidate::owner).extracting(AutoCandidate::editionId)
            .containsExactly(owner.getId());
        assertThat(found).extracting(AutoCandidate::editionId)
            .doesNotContain(plex.getId(), denied.getId());
        assertThat(jdbc.queryForObject("select credits_marker_source from edition where id = ?", String.class,
            owner.getId())).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select credits_marker_source from edition where id = ?", String.class,
            plex.getId())).isEqualTo("PLEX");
    }
}
