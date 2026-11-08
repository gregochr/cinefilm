package gregochr.cinefilm.config;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Only health and info are exposed, signed out, and they reveal nothing beyond status and the commit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void theContainerHealthGroupIsUpWithStatusOnlyAndNoSignInNeeded() throws Exception {
        mvc.perform(get("/actuator/health/container"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void theOverallHealthShowsNoComponentsToACallerWhoIsNotSignedIn() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void infoReportsTheFullCommitTheBuildWasMadeFrom() throws Exception {
        mvc.perform(get("/actuator/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.git.commit.id.full").value(matchesPattern("[0-9a-f]{40}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "beans", "metrics", "configprops", "mappings", "heapdump", "loggers"})
    void everyOtherActuatorEndpointIsNotExposed(String endpoint) throws Exception {
        mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }
}
