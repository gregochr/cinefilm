package gregochr.cinefilm.journey;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** A GET carries no CSRF header requirement, so there is no missing-header case for this route. */
@WebMvcTest(LinkMapController.class)
@Import(SecurityConfig.class)
class LinkMapControllerTest {

    private static final String URL = "/api/journeys/link-map";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LinkMapService linkMaps;

    private static LinkMap map() {
        return new LinkMap(List.of(new LinkMap.Track(
            List.of(new LinkMap.Point(0.5, -20.0, 90.0, 0), new LinkMap.Point(0.51, -20.0, null, 30)),
            List.of(new LinkMap.Stretch(10, 20, 72.5)))));
    }

    @Test
    void theMapHasExactlyPointsAndStretchesAndIsNeverCached() throws Exception {
        when(linkMaps.linkMap(null)).thenReturn(map());

        mvc.perform(get(URL).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(content().json("""
                {"tracks":[{"points":[{"latitude":0.5,"longitude":-20.0,"courseDeg":90.0,"t":0},
                                      {"latitude":0.51,"longitude":-20.0,"courseDeg":null,"t":30}],
                            "stretches":[{"from":10,"to":20,"need":72.5}]}]}
                """, true));

        verify(linkMaps).linkMap(null);
    }

    @Test
    void excludeJourneyReachesTheService() throws Exception {
        when(linkMaps.linkMap(7L)).thenReturn(new LinkMap(List.of()));

        mvc.perform(get(URL).param("excludeJourney", "7").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tracks.length()").value(0));

        verify(linkMaps).linkMap(7L);
    }

    @Test
    void anExcludeJourneyThatIsNotANumberIsA400() throws Exception {
        mvc.perform(get(URL).param("excludeJourney", "abc").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(get(URL))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
