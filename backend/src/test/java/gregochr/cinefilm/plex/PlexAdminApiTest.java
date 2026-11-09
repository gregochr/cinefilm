package gregochr.cinefilm.plex;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Plex routes and artwork through the whole application with Plex not configured (the test profile). */
class PlexAdminApiTest extends ApiTestBase {

    @Autowired
    private ArtworkStore artwork;

    @Test
    void withPlexNotConfiguredTheStatusSaysSoAndThePanelStaysHidden() throws Exception {
        mvc.perform(get("/api/admin/plex"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false))
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.lastImport").doesNotExist());
    }

    @Test
    void startingAnImportWithPlexNotConfiguredIs404() throws Exception {
        mvc.perform(post("/api/admin/plex/import"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Plex is not configured"));
    }

    @Test
    void storedArtworkIsServedThroughTheWholeStack() throws Exception {
        String name = artwork.store(new byte[] {7, 7, 7}, "image/png").substring(9);

        mvc.perform(get("/api/art/" + name))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(new byte[] {7, 7, 7}))
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"));
    }

    @Test
    void anUnknownArtworkNameIs404() throws Exception {
        mvc.perform(get("/api/art/" + "e".repeat(64) + ".jpg"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such artwork"));
    }
}
