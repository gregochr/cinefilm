package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The End credits list through the database predicates: filters, counts, hidden editions and the film's count. */
class CreditsAdminApiTest extends ApiTestBase {

    private static final String FINGERPRINT = "4000000000:" + NOW.toEpochMilli();

    @Autowired
    private PlexCreditsMarkerRepository plexRows;

    /** One film with one 100-minute edition and one file with a fingerprint. */
    private Edition seed(String title, Integer year, Long marker, CreditsMarkerSource source) {
        Film film = film(null, title, year, NOW);
        Edition edition = edition(film, null, "Original", 100 * MIN, marker);
        edition.setCreditsMarkerSource(source);
        MediaFile file = file(edition, title + ".m4v");
        file.setSizeBytes(4_000_000_000L);
        file.setModifiedAt(NOW);
        return films.saveAndFlush(film).getEditions().get(0);
    }

    private Edition addEdition(Edition existing, String name) {
        Film film = existing.getFilm();
        Edition added = edition(film, null, name, 100 * MIN, null);
        file(added, film.getTitle() + "-" + name + ".m4v");
        return films.saveAndFlush(film).getEditions().stream().filter(e -> name.equals(e.getName())).findFirst()
            .orElseThrow();
    }

    private static MediaFile onlyFile(Edition edition) {
        return edition.originalParts().get(0);
    }

    private void seedEveryFilter() {
        seed("Alpha", 2000, null, null);
        seed("Bravo", 2001, 5_000_000L, null);
        seed("Charlie", 2002, 5_000_000L, CreditsMarkerSource.PLEX);
        seed("Delta", 2003, 5_500_000L, CreditsMarkerSource.OWNER);
        seed("Echo", 2004, null, CreditsMarkerSource.PLEX);
    }

    @Test
    void needsALookIsTheDefaultAndHoldsEditionsWithNoSourceMarkerOrNot() throws Exception {
        seedEveryFilter();

        mvc.perform(get("/api/admin/credits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(25))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].title").value("Alpha"))
            .andExpect(jsonPath("$.items[0].markerMs").doesNotExist())
            .andExpect(jsonPath("$.items[0].source").doesNotExist())
            .andExpect(jsonPath("$.items[1].title").value("Bravo"))
            .andExpect(jsonPath("$.items[1].markerMs").value(5_000_000))
            .andExpect(jsonPath("$.items[1].source").doesNotExist());
    }

    @Test
    void thePlexFilterHoldsOnlyPlexEditions() throws Exception {
        seedEveryFilter();

        mvc.perform(get("/api/admin/credits?filter=plex"))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].title").value("Charlie"))
            .andExpect(jsonPath("$.items[0].source").value("PLEX"))
            .andExpect(jsonPath("$.items[1].title").value("Echo"));
    }

    @Test
    void theOwnerFilterHoldsOnlyOwnerEditions() throws Exception {
        seedEveryFilter();

        mvc.perform(get("/api/admin/credits?filter=owner"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Delta"))
            .andExpect(jsonPath("$.items[0].source").value("OWNER"));
    }

    @Test
    void allHoldsEveryVisibleEditionAndTheCountsAddUpWithAnUnsettledMarkerUnderNone() throws Exception {
        seedEveryFilter();

        mvc.perform(get("/api/admin/credits?filter=all"))
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.counts.none").value(2))
            .andExpect(jsonPath("$.counts.plex").value(2))
            .andExpect(jsonPath("$.counts.auto").value(0))
            .andExpect(jsonPath("$.counts.owner").value(1))
            .andExpect(jsonPath("$.counts.all").value(5));
    }

    @Test
    void theCountsDoNotDependOnTheChosenFilter() throws Exception {
        seedEveryFilter();

        mvc.perform(get("/api/admin/credits?filter=owner"))
            .andExpect(jsonPath("$.counts.none").value(2))
            .andExpect(jsonPath("$.counts.plex").value(2))
            .andExpect(jsonPath("$.counts.auto").value(0))
            .andExpect(jsonPath("$.counts.owner").value(1))
            .andExpect(jsonPath("$.counts.all").value(5));
    }

    @Test
    void anAutoEditionAppearsUnderAutoAndAllAndInNoOtherFilter() throws Exception {
        seed("Alpha", 2000, null, null);
        seed("Auto", 2001, 5_000_000L, CreditsMarkerSource.AUTO);

        mvc.perform(get("/api/admin/credits?filter=none"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Alpha"));
        mvc.perform(get("/api/admin/credits?filter=plex")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/admin/credits?filter=owner")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/admin/credits?filter=auto"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Auto"))
            .andExpect(jsonPath("$.items[0].source").value("AUTO"))
            .andExpect(jsonPath("$.counts.auto").value(1));
        mvc.perform(get("/api/admin/credits?filter=all"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[1].source").value("AUTO"))
            .andExpect(jsonPath("$.counts.all").value(2));
    }

    @Test
    void editionsHiddenFromBrowseAreLeftOutOfEveryFilterAndCount() throws Exception {
        seed("Visible", 2000, null, null);
        onlyFile(seed("Missing", 2001, null, null)).setPresence(Presence.MISSING);
        onlyFile(seed("Protected", 2002, null, null)).setPlayability(Playability.PROTECTED);
        Edition parts = seed("Two Parts", 2003, null, null);
        MediaFile second = file(parts, "Two Parts-2.m4v");
        second.setPartNumber(2);
        second.setDurationMs(null);
        Edition withPremade = seed("Premade Beside", 2004, null, null);
        onlyFile(withPremade).setDurationMs(null);
        file(withPremade, "Premade Beside.m4v").setKind(MediaKind.PREMADE_720);
        films.saveAndFlush(parts.getFilm());

        mvc.perform(get("/api/admin/credits?filter=all"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].title").value("Premade Beside"))
            .andExpect(jsonPath("$.items[1].title").value("Visible"))
            .andExpect(jsonPath("$.counts.none").value(2))
            .andExpect(jsonPath("$.counts.all").value(2))
            .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void editionCountIsTheFilmsVisibleEditionsOnly() throws Exception {
        Edition cut = seed("Heat", 1995, null, null);
        addEdition(cut, "Director's Cut");
        Edition gone = addEdition(cut, "Gone");
        onlyFile(gone).setPresence(Presence.MISSING);
        films.saveAndFlush(cut.getFilm());
        seed("Solo", 2000, null, null);

        mvc.perform(get("/api/admin/credits?filter=all"))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].title").value("Heat"))
            .andExpect(jsonPath("$.items[0].editionCount").value(2))
            .andExpect(jsonPath("$.items[1].title").value("Heat"))
            .andExpect(jsonPath("$.items[1].editionCount").value(2))
            .andExpect(jsonPath("$.items[2].title").value("Solo"))
            .andExpect(jsonPath("$.items[2].editionCount").value(1));
    }

    @Test
    void aRowCarriesTheRuntimeAndPlexsStateForTheLastPart() throws Exception {
        Edition edition = seed("Plexed", 2000, null, null);
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(onlyFile(edition).getId());
        row.setFingerprint(FINGERPRINT);
        row.setStartMs(5_000_000);
        row.setImportedAt(NOW);
        plexRows.saveAndFlush(row);

        mvc.perform(get("/api/admin/credits"))
            .andExpect(jsonPath("$.items[0].editionId").value(edition.getId()))
            .andExpect(jsonPath("$.items[0].filmId").value(edition.getFilm().getId()))
            .andExpect(jsonPath("$.items[0].year").value(2000))
            .andExpect(jsonPath("$.items[0].editionName").value("Original"))
            .andExpect(jsonPath("$.items[0].runtimeMs").value(100 * MIN))
            .andExpect(jsonPath("$.items[0].plex.state").value("USABLE"))
            .andExpect(jsonPath("$.items[0].plex.markerMs").value(5_000_000));
    }

    @Test
    void aPageHoldsTheRowsAfterTheFirstOnes() throws Exception {
        seed("Alpha", 2000, null, null);
        seed("Bravo", 2001, null, null);
        seed("Charlie", 2002, null, null);

        mvc.perform(get("/api/admin/credits?size=2&page=1"))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("Charlie"));
        mvc.perform(get("/api/admin/credits?size=2&page=2")).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/credits"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
