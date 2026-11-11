package gregochr.cinefilm.home;

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
import gregochr.cinefilm.home.HomeDtos.Home;
import gregochr.cinefilm.home.HomeDtos.ThemeDto;
import gregochr.cinefilm.web.JsonEtag;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HomeController.class)
@Import({SecurityConfig.class, JsonEtag.class})
class HomeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HomeService service;

    @BeforeEach
    void home() {
        when(service.home()).thenReturn(new Home(null, List.of(), 0, List.of(), List.of(),
            new ThemeDto("This week: the 1990s", "A new theme every Monday", "decade", "1990", List.of())));
    }

    @Test
    void homeReturnsWhatTheServiceAssembled() throws Exception {
        mvc.perform(get("/api/home").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hero").doesNotExist())
            .andExpect(jsonPath("$.weeklyTheme.title").value("This week: the 1990s"))
            .andExpect(jsonPath("$.weeklyTheme.kind").value("decade"))
            .andExpect(jsonPath("$.weeklyTheme.value").value("1990"))
            .andExpect(jsonPath("$.collections").doesNotExist());
    }

    @Test
    void a200CarriesAWeakEtagAndExactlyNoCachePrivate() throws Exception {
        mvc.perform(get("/api/home").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("ETag", matchesPattern("W/\"[0-9a-f]{16}\"")))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void aMatchingIfNoneMatchIsA304WithNoBodyAndTheSameHeaders() throws Exception {
        String etag = mvc.perform(get("/api/home").with(user("owner")))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/home").with(user("owner")).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void aDifferentIfNoneMatchGetsTheBody() throws Exception {
        mvc.perform(get("/api/home").with(user("owner")).header("If-None-Match", "W/\"fedcba9876543210\""))
            .andExpect(status().isOk())
            .andExpect(content().string(not(emptyString())));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(get("/api/home"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
