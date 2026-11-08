package gregochr.cinefilm.person;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.person.PersonDtos.PersonPage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PersonController.class)
@Import(SecurityConfig.class)
class PersonControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PersonService service;

    @Test
    void thePathIdSelectsThePersonPage() throws Exception {
        when(service.get(3L)).thenReturn(new PersonPage(3L, "Orson Welles", "/welles.jpg", List.of()));

        mvc.perform(get("/api/people/{id}", 3).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(3))
            .andExpect(jsonPath("$.name").value("Orson Welles"))
            .andExpect(jsonPath("$.profilePath").value("/welles.jpg"));
    }

    @Test
    void anUnknownPersonIsA404WithTheServiceMessage() throws Exception {
        when(service.get(424_242L)).thenThrow(new NotFoundException("No such person"));

        mvc.perform(get("/api/people/{id}", 424_242).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such person"));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(get("/api/people/{id}", 3))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
