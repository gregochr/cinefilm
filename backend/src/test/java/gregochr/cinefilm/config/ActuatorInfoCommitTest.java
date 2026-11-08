package gregochr.cinefilm.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The image has no .git, so the deploy smoke reads the commit from the GIT_COMMIT_SHA environment variable. */
@SpringBootTest(properties = "GIT_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorInfoCommitTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void infoReportsTheCommitFromTheEnvironmentVariableTheImageIsBuiltWith() throws Exception {
        mvc.perform(get("/actuator/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.build.commit").value("0123456789abcdef0123456789abcdef01234567"));
    }
}
