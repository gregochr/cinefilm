package gregochr.cinefilm.settings;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SettingsApiTest extends ApiTestBase {

    private void played(String title, long positionMs, long hoursAgo) {
        Film film = film(null, title, 2000, NOW.minus(Duration.ofDays(900)));
        Edition edition = edition(film, null, "Theatrical Cut", 100 * MIN, null);
        save(film);
        saveState(state(edition, positionMs, false, NOW.minus(Duration.ofHours(hoursAgo)), null));
    }

    @Test
    void theMinimumDefaultsToFiveMinutesWithNothingInProgress() throws Exception {
        mvc.perform(get("/api/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.continueWatchingMinMinutes").value(5))
            .andExpect(jsonPath("$.inProgressCount").value(0));
    }

    @Test
    void aPutChangesTheCountAndTheHomeTotalAndTheLibraryFilterStraightAway() throws Exception {
        played("Short", 2 * MIN, 1);
        played("Medium", 10 * MIN, 2);
        played("Long", 50 * MIN, 3);

        mvc.perform(get("/api/admin/settings"))
            .andExpect(jsonPath("$.inProgressCount").value(2));
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.continueWatchingTotal").value(2));

        mvc.perform(put("/api/admin/settings").content("{\"continueWatchingMinMinutes\":30}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.continueWatchingMinMinutes").value(30))
            .andExpect(jsonPath("$.inProgressCount").value(1));
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.continueWatchingTotal").value(1))
            .andExpect(jsonPath("$.hero.title").value("Long"));
        mvc.perform(get("/api/films").param("show", "in-progress"))
            .andExpect(jsonPath("$.films", hasSize(1)));

        mvc.perform(put("/api/admin/settings").content("{\"continueWatchingMinMinutes\":0}"))
            .andExpect(jsonPath("$.inProgressCount").value(3));
        mvc.perform(get("/api/admin/settings"))
            .andExpect(jsonPath("$.continueWatchingMinMinutes").value(0))
            .andExpect(jsonPath("$.inProgressCount").value(3));
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.continueWatchingTotal").value(3));
    }

    @Test
    void aMinimumOutsideZeroToThirtyOrMissingIs400AndChangesNothing() throws Exception {
        for (String json : new String[] {"{\"continueWatchingMinMinutes\":-1}",
            "{\"continueWatchingMinMinutes\":31}", "{\"continueWatchingMinMinutes\":null}", "{}"}) {
            mvc.perform(put("/api/admin/settings").content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("continueWatchingMinMinutes must be a whole number from 0 to 30"));
        }

        mvc.perform(get("/api/admin/settings"))
            .andExpect(jsonPath("$.continueWatchingMinMinutes").value(5));
    }

    @Test
    void aBodyOfJsonNullIs400WithTheValidationMessage() throws Exception {
        mvc.perform(put("/api/admin/settings").content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("continueWatchingMinMinutes must be a whole number from 0 to 30"));
    }

    @Test
    void noBodyAtAllIsA400() throws Exception {
        mvc.perform(put("/api/admin/settings")).andExpect(status().isBadRequest());
    }

    @Test
    void repeatingTheSamePutGivesTheSameAnswer() throws Exception {
        played("Medium", 10 * MIN, 1);

        for (int i = 0; i < 2; i++) {
            mvc.perform(put("/api/admin/settings").content("{\"continueWatchingMinMinutes\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continueWatchingMinMinutes").value(12))
                .andExpect(jsonPath("$.inProgressCount").value(0));
        }
    }
}
