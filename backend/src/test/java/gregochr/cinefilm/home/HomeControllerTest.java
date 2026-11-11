package gregochr.cinefilm.home;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.home.HomeDtos.Home;
import gregochr.cinefilm.home.HomeDtos.ThemeDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
@Import(SecurityConfig.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HomeService service;

    @Test
    void homeReturnsWhatTheServiceAssembled() throws Exception {
        when(service.home()).thenReturn(new Home(null, List.of(), 0, List.of(), List.of(),
            new ThemeDto("This week: the 1990s", "A new theme every Monday", "decade", "1990", List.of())));

        mvc.perform(get("/api/home").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hero").doesNotExist())
            .andExpect(jsonPath("$.weeklyTheme.title").value("This week: the 1990s"))
            .andExpect(jsonPath("$.weeklyTheme.kind").value("decade"))
            .andExpect(jsonPath("$.weeklyTheme.value").value("1990"))
            .andExpect(jsonPath("$.collections").doesNotExist());
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(get("/api/home"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
