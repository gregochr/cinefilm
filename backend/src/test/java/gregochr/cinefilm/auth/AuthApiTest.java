package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    private static final String JSON = "application/json";

    @Autowired
    private MockMvc mvc;

    @Test
    void statusIs401WhenSignedOut() throws Exception {
        mvc.perform(get("/api/session"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/session").header("X-CineFilm", "1").contentType(JSON)
                .content("{\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(post("/api/session").header("X-CineFilm", "1").contentType(JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void signInWithoutTheCineFilmHeaderIsForbidden() throws Exception {
        mvc.perform(post("/api/session").contentType(JSON).content("{\"password\":\"sesame\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void headerValueMustBeOne() throws Exception {
        mvc.perform(post("/api/session").header("X-CineFilm", "0").contentType(JSON)
                .content("{\"password\":\"sesame\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void signInKeepsYouSignedInUntilSignOut() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/session").session(session).header("X-CineFilm", "1").contentType(JSON)
                .content("{\"password\":\"sesame\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true));

        mvc.perform(get("/api/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/home").session(session)).andExpect(status().isOk());

        mvc.perform(delete("/api/session").session(session).header("X-CineFilm", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/home").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void signingInAgainWithAnExistingSessionStillWorks() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/session").session(session).header("X-CineFilm", "1").contentType(JSON)
                    .content("{\"password\":\"sesame\"}"))
                .andExpect(status().isOk());
        }
        mvc.perform(get("/api/home").session(session)).andExpect(status().isOk());
    }

    @Test
    void signOutWithoutASessionIsHarmless() throws Exception {
        mvc.perform(delete("/api/session").header("X-CineFilm", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void signOutNeedsTheHeaderToo() throws Exception {
        mvc.perform(delete("/api/session")).andExpect(status().isForbidden());
    }

    @Test
    void missingHeaderIsRejectedBeforeAuthenticationOnMutatingRequests() throws Exception {
        mvc.perform(put("/api/editions/1/progress").contentType(JSON).content("{\"positionMs\":1}"))
            .andExpect(status().isForbidden());
    }
}
