package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsList;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsRow;
import gregochr.cinefilm.credits.CreditsAdminService;
import gregochr.cinefilm.credits.CreditsApply;
import gregochr.cinefilm.credits.CreditsFilter;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Admin End credits list on PostgreSQL: each filter, the hidden-edition predicate against
 * {@code Edition#hiddenFromBrowse()}, the order and paging (a null year, a mixed-case sort title), and a page costing
 * a fixed number of statements. Not transactional: the service runs its own read transaction, so the seeded rows are
 * committed, and each test starts from an empty library.
 */
class CreditsAdminIntegrationTest extends IntegrationTestBase {

    @Autowired
    private CreditsAdminService service;

    @Autowired
    private FilmRepository films;

    @Autowired
    private PlexCreditsMarkerRepository plexRows;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private MediaFileRepository files;

    @Autowired
    private EntityManagerFactory factory;

    private Statistics statistics;

    @BeforeEach
    void clean() {
        plexRows.deleteAll();
        films.deleteAll();
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    /**
     * A film with one 100-minute edition and one original file, saved once with {@code shape} already applied. Never
     * change or re-save what comes back: the test base is not transactional, so it is detached after the save.
     */
    private Edition seed(String title, Integer year, String editionName, Long marker, CreditsMarkerSource source,
                         Consumer<Edition> shape) {
        Film film = film(null, title, year, NOW);
        Edition edition = build(film, editionName, marker, source);
        shape.accept(edition);
        return save(film, editionName);
    }

    private Edition seed(String title, Integer year, String editionName, Long marker, CreditsMarkerSource source) {
        return seed(title, year, editionName, marker, source, e -> { });
    }

    /** Adds an edition with one original file to the unsaved film. */
    private Edition build(Film film, String editionName, Long marker, CreditsMarkerSource source) {
        Edition edition = edition(film, null, editionName, 100 * MIN, marker);
        edition.setCreditsMarkerSource(source);
        MediaFile file = file(edition, film.getTitle() + "-" + editionName + ".m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        return edition;
    }

    private Edition save(Film film, String editionName) {
        Film saved = films.saveAndFlush(film);
        return saved.getEditions().stream().filter(e -> editionName.equals(e.getName())).findFirst().orElseThrow();
    }

    private List<String> titles(CreditsFilter filter) {
        return service.list(filter, 0, 100).items().stream().map(CreditsRow::title).toList();
    }

    @Test
    void everyFilterHoldsItsEditionsAndAnUnsettledMarkerCountsUnderNone() {
        seed("Alpha", 2000, "Original", null, null);
        seed("Bravo", 2001, "Original", 5_000_000L, null);
        seed("Charlie", 2002, "Original", 5_000_000L, CreditsMarkerSource.PLEX);
        seed("Delta", 2003, "Original", 5_500_000L, CreditsMarkerSource.OWNER);
        seed("Echo", 2004, "Original", 5_000_000L, CreditsMarkerSource.AUTO);

        assertThat(titles(CreditsFilter.NONE)).containsExactly("Alpha", "Bravo");
        assertThat(titles(CreditsFilter.PLEX)).containsExactly("Charlie");
        assertThat(titles(CreditsFilter.OWNER)).containsExactly("Delta");
        assertThat(titles(CreditsFilter.ALL)).containsExactly("Alpha", "Bravo", "Charlie", "Delta", "Echo");
        CreditsList all = service.list(CreditsFilter.ALL, 0, 25);
        assertThat(all.counts()).isEqualTo(new gregochr.cinefilm.credits.CreditsAdminDtos.Counts(2, 1, 1, 5));
        assertThat(all.total()).isEqualTo(5);
        assertThat(service.list(CreditsFilter.PLEX, 0, 25).total()).isEqualTo(1);
    }

    /** Adds a second original file, as part 2, to the unsaved edition. */
    private static void addPart(Edition edition, Long durationMs) {
        MediaFile part = file(edition, "part2-" + edition.getName() + "-" + durationMs + ".m4v");
        part.setPartNumber(2);
        part.setDurationMs(durationMs);
    }

    @Test
    void theHiddenPredicateAgreesWithHiddenFromBrowseForEveryCase() {
        List<Edition> seeded = new ArrayList<>();
        seeded.add(seed("Plain", 2000, "Original", null, null));
        seeded.add(seed("Missing", 2000, "Original", null, null,
            e -> e.originalParts().get(0).setPresence(Presence.MISSING)));
        for (Playability bad : List.of(Playability.PROTECTED, Playability.NEEDS_CONVERSION, Playability.UNREADABLE)) {
            seeded.add(seed("Unplayable " + bad, 2000, "Original", null, null,
                e -> e.originalParts().get(0).setPlayability(bad)));
        }
        seeded.add(seed("Single No Duration", 2000, "Original", null, null,
            e -> e.originalParts().get(0).setDurationMs(null)));
        seeded.add(seed("Single No Duration With Premade", 2000, "Original", null, null, e -> {
            e.originalParts().get(0).setDurationMs(null);
            MediaFile premade = file(e, "premade-nodur.m4v");
            premade.setKind(MediaKind.PREMADE_720);
        }));
        seeded.add(seed("Single Negative", 2000, "Original", null, null,
            e -> e.originalParts().get(0).setDurationMs(-1L)));
        seeded.add(seed("Premade Missing", 2000, "Original", null, null, e -> {
            MediaFile premade = file(e, "premade.m4v");
            premade.setKind(MediaKind.PREMADE_720);
            premade.setPresence(Presence.MISSING);
        }));
        seeded.add(seed("No Files", 2000, "Original", null, null, e -> e.getMediaFiles().clear()));
        for (Long duration : new Long[] {null, 0L, -5L}) {
            seeded.add(seed("Two Parts " + duration, 2000, "Original", null, null, e -> addPart(e, duration)));
        }
        seeded.add(seed("Two Parts Fine", 2000, "Original", null, null, e -> addPart(e, 50 * MIN)));
        assertThat(editions.count()).isEqualTo(seeded.size());

        Set<Long> listed = service.list(CreditsFilter.ALL, 0, 100).items().stream().map(CreditsRow::editionId)
            .collect(Collectors.toSet());

        Set<Long> expected = seeded.stream().filter(e -> !e.hiddenFromBrowse()).map(Edition::getId)
            .collect(Collectors.toSet());
        assertThat(seeded).anyMatch(Edition::hiddenFromBrowse).anyMatch(e -> !e.hiddenFromBrowse());
        assertThat(listed).isEqualTo(expected);
        assertThat(service.list(CreditsFilter.ALL, 0, 100).counts().all()).isEqualTo(expected.size());
    }

    @Test
    void orderAndPagingAcrossAPageBoundaryPlaceANullYearLastAndIgnoreCase() {
        seed("Alpha", 2001, "Original", null, null);
        seed("Alpha", null, "Original", null, null);
        seed("Alpha", 1999, "Original", null, null);
        Film twoCuts = film(null, "Alpha", 2005, NOW);
        build(twoCuts, "Cut B", null, null);
        build(twoCuts, "Cut A", null, null);
        films.saveAndFlush(twoCuts);
        Film mixedCase = film(null, "Bravo", 2000, NOW);
        mixedCase.setSortTitle("Bravo");
        build(mixedCase, "Original", null, null);
        films.saveAndFlush(mixedCase);
        seed("Charlie", 2000, "Original", null, null);

        List<CreditsRow> whole = service.list(CreditsFilter.ALL, 0, 100).items();
        List<CreditsRow> paged = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            paged.addAll(service.list(CreditsFilter.ALL, page, 3).items());
        }

        assertThat(whole).extracting(r -> r.title() + " " + r.year() + " " + r.editionName()).containsExactly(
            "Alpha 1999 Original", "Alpha 2001 Original", "Alpha 2005 Cut A", "Alpha 2005 Cut B",
            "Alpha null Original", "Bravo 2000 Original", "Charlie 2000 Original");
        assertThat(paged).isEqualTo(whole);
        assertThat(service.list(CreditsFilter.ALL, 1, 3).items()).hasSize(3);
        assertThat(service.list(CreditsFilter.ALL, 2, 3).items()).hasSize(1);
        assertThat(service.list(CreditsFilter.ALL, 3, 3).items()).isEmpty();
    }

    @Test
    void theEditionCountOfAFilmCountsItsVisibleEditionsOnly() {
        Film heat = film(null, "Heat", 1995, NOW);
        build(heat, "Original", null, null);
        build(heat, "Director's Cut", null, null);
        build(heat, "Gone", null, null).originalParts().get(0).setPresence(Presence.MISSING);
        films.saveAndFlush(heat);
        assertThat(editions.count()).isEqualTo(3);
        assertThat(files.findAll()).filteredOn(f -> f.getPresence() == Presence.MISSING).hasSize(1);

        List<CreditsRow> rows = service.list(CreditsFilter.ALL, 0, 25).items();

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(CreditsRow::editionCount).containsExactly(2, 2);
    }

    /** The statements one page of the list prepares. */
    private long statementsOfOnePage() {
        statistics.clear();
        CreditsList list = service.list(CreditsFilter.ALL, 0, 25);
        assertThat(list.items()).isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    @Test
    void aPageCostsTheSameStatementsHoweverManyEditionsItHolds() {
        seed("Count One", 2000, "Original", null, null);
        long withOne = statementsOfOnePage();

        for (int n = 2; n <= 8; n++) {
            Film film = film(null, "Count " + n, 2000 + n, NOW);
            build(film, "Original", 5_000_000L, CreditsMarkerSource.PLEX);
            build(film, "Second", null, null);
            films.saveAndFlush(film);
        }
        long withMore = statementsOfOnePage();

        assertThat(withOne).isPositive();
        assertThat(withMore).isEqualTo(withOne);
    }

    @Test
    void anOwnerTimeIsSetOverPlexAndClearedBackToPlexOnThePersistedRow() {
        Edition seeded = seed("Round Trip", 2000, "Original", null, null);
        MediaFile file = seeded.getMediaFiles().get(0);
        PlexCreditsMarker plex = new PlexCreditsMarker();
        plex.setMediaFileId(file.getId());
        plex.setFingerprint(file.fingerprint());
        plex.setStartMs(5_000_000L);
        plex.setImportedAt(NOW);
        plexRows.saveAndFlush(plex);
        long id = seeded.getId();

        CreditsRow set = service.set(id, 5_500_000L);

        assertThat(set.markerMs()).isEqualTo(5_500_000L);
        assertThat(set.source()).isEqualTo(CreditsMarkerSource.OWNER);
        assertThat(set.plex().state()).isEqualTo(CreditsApply.PlexState.USABLE);
        Edition stored = editions.findById(id).orElseThrow();
        assertThat(stored.getCreditsMarkerMs()).isEqualTo(5_500_000L);
        assertThat(stored.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.OWNER);

        CreditsRow cleared = service.clear(id);

        assertThat(cleared.markerMs()).isEqualTo(5_000_000L);
        assertThat(cleared.source()).isEqualTo(CreditsMarkerSource.PLEX);
        Edition back = editions.findById(id).orElseThrow();
        assertThat(back.getCreditsMarkerMs()).isEqualTo(5_000_000L);
        assertThat(back.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
    }
}
