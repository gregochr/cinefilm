package gregochr.cinefilm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The SPA is served from the same origin: client routes get index.html, API and actuator paths never do. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaFallbackTest {

    private static final String SHELL = "<div id=\"root\"></div>";

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/index.html", "/library", "/films/12", "/films/12/play", "/people/3", "/admin"})
    void clientRoutesGetTheAppShellWithoutSigningIn(String path) throws Exception {
        mvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(SHELL)))
            .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void theRootIsForwardedToTheAppShellByTheWelcomePage() throws Exception {
        // MockMvc does not follow forwards; a real servlet container then serves /index.html as above.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
    }

    @Test
    void hashedAssetsAreServedAsFilesAndCachedForever() throws Exception {
        mvc.perform(get("/assets/app-abc123.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("asset")))
            .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/missing.js", "/favicon.ico", "/films/12.json"})
    void aMissingFileIsA404NotTheAppShell(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void anUnknownApiPathIsNeverTheAppShellForASignedInCaller() throws Exception {
        String body = mvc.perform(get("/api/nope").with(user("owner")))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SHELL);
    }

    @Test
    void aSignedOutApiCallIsStillA401Json() throws Exception {
        mvc.perform(get("/api/home"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void anUnknownActuatorPathIsA404AndHealthIsStillJson() throws Exception {
        mvc.perform(get("/actuator/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest
    @CsvSource({"api,false", "api/home,false", "actuator,false", "actuator/health,false", "library,true",
        "films/12,true", "apiary,true", "actuators/x,true", "assets/a.js,false", "films/1.2/x,true"})
    void clientRouteRule(String path, boolean expected) {
        assertThat(SpaFallbackConfig.isClientRoute(path)).isEqualTo(expected);
    }
}
