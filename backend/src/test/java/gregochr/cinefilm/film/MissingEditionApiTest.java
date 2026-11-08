package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Home, the card and the detail page agree when the most recently played edition's file has gone missing. */
class MissingEditionApiTest extends ApiTestBase {

    private Edition available;
    private Edition gone;
    private Film blade;

    @BeforeEach
    void data() {
        blade = film(null, "Blade Runner", 1982, NOW.minus(Duration.ofDays(30)));
        available = edition(blade, null, "Final Cut", 117 * MIN, null);
        gone = edition(blade, null, "Theatrical Cut", 117 * MIN, null);
        file(available, "final.m4v");
        file(gone, "theatrical.m4v");
        save(blade);
        saveState(state(available, 30 * MIN, false, NOW.minus(Duration.ofDays(3)), null));
        saveState(state(gone, 10 * MIN, false, NOW.minus(Duration.ofHours(1)), null));
        gone.originalFile().setPresence(Presence.MISSING);
    }

    @Test
    void theCardAndHomeAdvertiseTheAvailableEditionWithItsOwnPosition() throws Exception {
        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hero.progress.editionId").value(available.getId()))
            .andExpect(jsonPath("$.hero.progress.positionMs").value(30 * MIN));
        mvc.perform(get("/api/films/{id}", blade.getId()))
            .andExpect(jsonPath("$.progress.editionId").value(available.getId()))
            .andExpect(jsonPath("$.editions", hasSize(1)))
            .andExpect(jsonPath("$.editions[0].id").value(available.getId()))
            .andExpect(jsonPath("$.editions[0].positionMs").value(30 * MIN));
    }

    @Test
    void withNoAvailablePlayedEditionTheCardHasNoProgressButTheHistoryIsKept() throws Exception {
        available.originalFile().setPlayability(Playability.UNREADABLE);
        gone.originalFile().setPresence(Presence.PRESENT);
        file(edition(blade, null, "Extended", 120 * MIN, null), "extended.m4v");
        save(blade);
        gone.originalFile().setPlayability(Playability.PROTECTED);

        mvc.perform(get("/api/films/{id}", blade.getId()))
            .andExpect(jsonPath("$.progress").value(nullValue()))
            .andExpect(jsonPath("$.editions", hasSize(1)));
        org.assertj.core.api.Assertions.assertThat(states.findById(gone.getId()).orElseThrow().getPositionMs())
            .isEqualTo(10 * MIN);
    }
}
