package gregochr.cinefilm.summary;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The summary through the whole application on H2, outside the base class's rollback transaction. */
class AdminSummaryApiTest extends ApiTestBase {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theRepeatableReadTransactionOfTheSummaryRunsOnH2() throws Exception {
        mvc.perform(get("/api/admin/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.library.films").value(0))
            .andExpect(jsonPath("$.playback.films").value(0))
            .andExpect(jsonPath("$.playingNow").isEmpty());
    }
}
