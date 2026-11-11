package gregochr.cinefilm.settings;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.settings.SettingsDtos.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettingsController.class)
@Import(SecurityConfig.class)
class SettingsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServerSettingsService settings;

    @Test
    void getReturnsTheMinimumAndTheCount() throws Exception {
        when(settings.current()).thenReturn(new Settings(5, 91));

        mvc.perform(get("/api/admin/settings").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.continueWatchingMinMinutes").value(5))
            .andExpect(jsonPath("$.inProgressCount").value(91));
    }

    @Test
    void putPassesTheMinimumToTheServiceAndReturnsTheRecomputedCount() throws Exception {
        when(settings.update(10)).thenReturn(new Settings(10, 40));

        mvc.perform(put("/api/admin/settings").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"continueWatchingMinMinutes\":10}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.continueWatchingMinMinutes").value(10))
            .andExpect(jsonPath("$.inProgressCount").value(40));
        verify(settings).update(10);
    }

    @Test
    void putWithAnOutOfRangeValueAnswers400WithTheServiceMessage() throws Exception {
        when(settings.update(31)).thenThrow(new BadRequestException("continueWatchingMinMinutes out of range"));

        mvc.perform(put("/api/admin/settings").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"continueWatchingMinMinutes\":31}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("continueWatchingMinMinutes out of range"));
    }

    @Test
    void signedOutIsA401ForBothMethods() throws Exception {
        mvc.perform(get("/api/admin/settings"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(put("/api/admin/settings").header("X-CineFilm", "1").contentType("application/json")
                .content("{\"continueWatchingMinMinutes\":10}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void putWithoutTheCsrfHeaderIsRefused() throws Exception {
        mvc.perform(put("/api/admin/settings").with(user("owner")).contentType("application/json")
                .content("{\"continueWatchingMinMinutes\":10}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
