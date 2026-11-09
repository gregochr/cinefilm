package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.library.ScanService;
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
            "GET /api/collections/{id}", "GET /api/editions/{id}/stream",
            "PUT /api/editions/{id}/progress", "POST /api/editions/{id}/watched",
            "PUT /api/films/{id}/preferences", "GET /api/session", "POST /api/session",
            "DELETE /api/session", "GET /api/admin/scan", "POST /api/admin/scan", "GET /api/admin/attention",
            "GET /api/media-files/{id}/subtitles/{streamIndex}.vtt", "GET /api/art/{name}", "GET /api/admin/plex",
            "POST /api/admin/plex/import");
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
    void anUnmappedApiPathIsAlsoBehindAuthenticationNotA404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/not-a-route"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
