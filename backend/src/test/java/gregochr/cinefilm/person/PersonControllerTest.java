package gregochr.cinefilm.person;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.person.PersonDtos.PersonPage;
import gregochr.cinefilm.web.JsonEtag;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PersonController.class)
@Import({SecurityConfig.class, JsonEtag.class})
class PersonControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PersonService service;

    private void welles() {
        when(service.get(3L)).thenReturn(new PersonPage(3L, "Orson Welles", "/welles.jpg", List.of()));
    }

    @Test
    void thePathIdSelectsThePersonPage() throws Exception {
        welles();

        mvc.perform(get("/api/people/{id}", 3).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(3))
            .andExpect(jsonPath("$.name").value("Orson Welles"))
            .andExpect(jsonPath("$.profilePath").value("/welles.jpg"));
    }

    @Test
    void a200CarriesAWeakEtagAndExactlyNoCachePrivate() throws Exception {
        welles();

        mvc.perform(get("/api/people/{id}", 3).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", matchesPattern("W/\"[0-9a-f]{16}\"")))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void aMatchingIfNoneMatchIsA304WithNoBodyAndTheSameHeaders() throws Exception {
        welles();
        String etag = mvc.perform(get("/api/people/{id}", 3).with(user("owner")))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/people/{id}", 3).with(user("owner")).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void aDifferentIfNoneMatchGetsTheBody() throws Exception {
        welles();

        mvc.perform(get("/api/people/{id}", 3).with(user("owner")).header("If-None-Match", "W/\"fedcba9876543210\""))
            .andExpect(status().isOk())
            .andExpect(content().string(not(emptyString())));
    }

    @Test
    void anUnknownPersonIsA404WithTheServiceMessageNoEtagAndNoStore() throws Exception {
        when(service.get(424_242L)).thenThrow(new NotFoundException("No such person"));

        mvc.perform(get("/api/people/{id}", 424_242).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.error").value("No such person"));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(get("/api/people/{id}", 3))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
