package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.library.ScanService;
import gregochr.cinefilm.plex.PlexCreditsImportService;
import gregochr.cinefilm.plex.PlexImportService;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Drift guard for AGENTS.md rule 5 and rule 6: discovers every {@code /api/**} route the application maps and fails
 * when one is reachable without signing in, so a new controller cannot ship unprotected by forgetting to add itself
 * to a hand-kept list. The only routes allowed to answer a signed-out caller are the session endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RouteSecurityDriftTest {

    /** The signed-in sweep calls POST /api/admin/scan: a real scan must not start from a security test. */
    @MockitoBean
    private ScanService scans;

    /** The signed-in sweep calls POST /api/admin/plex/import: a real import must not start from a security test. */
    @MockitoBean
    private PlexImportService plexImports;

    /** The signed-in sweep calls POST /api/admin/plex/credits/import: no real import from a security test. */
    @MockitoBean
    private PlexCreditsImportService plexCreditsImports;

    /** Routes that may be called signed out: sign in, check status, sign out. */
    private static final Set<String> SESSION_ROUTES = Set.of(
        "GET /api/session", "POST /api/session", "DELETE /api/session");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private List<RequestMappingHandlerMapping> mappings;

    /** Every mapped {@code /api/**} route as {@code "METHOD /pattern"}, sorted. */
    private Set<String> apiRoutes() {
        Set<String> routes = new TreeSet<>();
        List<RequestMappingInfo> infos = mappings.stream()
            .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
            .toList();
        for (RequestMappingInfo info : infos) {
            Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    routes.add("GET " + pattern);
                }
                methods.forEach(method -> routes.add(method + " " + pattern));
            }
        }
        return routes;
    }

    private static MockHttpServletRequestBuilder request(String route) {
        String[] parts = route.split(" ", 2);
        String path = parts[1].replaceAll("\\{[^}]+}", "1");
        return MockMvcRequestBuilders.request(HttpMethod.valueOf(parts[0]), path)
            .header("X-CineFilm", "1")
            .contentType("application/json")
            .content("{}");
    }

    @Test
    void discoveryFindsTheWholeApiSoTheGuardCannotPassByFindingNothing() {
        assertThat(apiRoutes()).contains(
            "GET /api/home", "GET /api/films", "GET /api/films/{id}", "GET /api/people/{id}",
            "GET /api/collections", "GET /api/collections/{id}", "GET /api/editions/{id}/stream",
            "GET /api/editions/{id}/still",
            "PUT /api/editions/{id}/progress", "POST /api/editions/{id}/watched",
            "PUT /api/films/{id}/preferences", "GET /api/session", "POST /api/session",
            "DELETE /api/session", "GET /api/admin/scan", "POST /api/admin/scan", "GET /api/admin/attention",
            "GET /api/media-files/{id}/subtitles/{streamIndex}.vtt",
            "GET /api/editions/{id}/subtitles/{streamIndex}.vtt", "GET /api/art/{name}", "GET /api/admin/plex",
            "POST /api/admin/plex/import", "GET /api/admin/plex/credits", "POST /api/admin/plex/credits/import",
            "POST /api/editions/{id}/sessions", "GET /api/sessions/{id}/master.m3u8",
            "GET /api/sessions/{id}/{rung}/index.m3u8", "GET /api/sessions/{id}/{rung}/{file}",
            "DELETE /api/sessions/{id}", "GET /api/admin/transcoding",
            "DELETE /api/admin/transcoding/sessions/{id}", "GET /api/admin/films/{id}/match",
            "POST /api/admin/films/{id}/match", "GET /api/admin/grouping-report",
            "GET /api/admin/films/{id}/editions", "PUT /api/admin/films/{id}/editions",
            "PUT /api/playback-health/{playbackId}", "GET /api/admin/playback-health",
            "GET /api/admin/settings", "PUT /api/admin/settings",
            "GET /api/admin/playback-health/{playbackId}", "GET /api/editions/{id}/forced-subtitles.vtt",
            "GET /api/editions/{id}/full-subtitles.vtt",
            "GET /api/admin/subtitles", "GET /api/admin/subtitles/{id}/cues",
            "POST /api/admin/subtitles/{id}/attach", "POST /api/admin/subtitles/{id}/detach",
            "PUT /api/admin/subtitles/{id}/offset", "POST /api/admin/subtitles/{id}/verify",
            "DELETE /api/admin/subtitles/{id}/verify");
    }

    @Test
    void theSessionEndpointsAreTheOnlyAllowListedRoutesAndAllExist() {
        assertThat(apiRoutes()).containsAll(SESSION_ROUTES);
    }

    @Test
    void everyOtherApiRouteReturns401JsonWithTheAuthenticationRequiredErrorWhenSignedOut() throws Exception {
        List<String> protectedRoutes = apiRoutes().stream().filter(r -> !SESSION_ROUTES.contains(r)).toList();
        assertThat(protectedRoutes).isNotEmpty();

        for (String route : protectedRoutes) {
            mvc.perform(request(route))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("Authentication required"));
        }
    }

    @Test
    void signedInTheSameRoutesGetPastSecurityProvingTheRequestsAreWellFormed() throws Exception {
        List<String> protectedRoutes = apiRoutes().stream().filter(r -> !SESSION_ROUTES.contains(r)).toList();

        for (String route : protectedRoutes) {
            int status = mvc.perform(request(route).with(user("owner").roles("OWNER"))).andReturn()
                .getResponse().getStatus();
            assertThat(status).as(route).isNotIn(401, 403);
        }
    }

    @Test
    void theStreamingSessionRoutesThatChangeStateAlsoNeedTheCsrfHeaderWhenSignedIn() throws Exception {
        List<String> mutating = List.of("POST /api/editions/{id}/sessions", "DELETE /api/sessions/{id}",
            "DELETE /api/admin/transcoding/sessions/{id}", "PUT /api/playback-health/{playbackId}",
            "POST /api/admin/subtitles/{id}/attach", "POST /api/admin/subtitles/{id}/detach",
            "PUT /api/admin/subtitles/{id}/offset", "POST /api/admin/subtitles/{id}/verify",
            "DELETE /api/admin/subtitles/{id}/verify");
        assertThat(apiRoutes()).containsAll(mutating);

        for (String route : mutating) {
            String[] parts = route.split(" ", 2);
            mvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(parts[0]),
                    parts[1].replaceAll("\\{[^}]+}", "1")).with(user("owner").roles("OWNER"))
                    .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        }
    }

    @Test
    void anUnmappedApiPathIsAlsoBehindAuthenticationNotA404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/not-a-route"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
