package gregochr.cinefilm.journey;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.journey.JourneyTrainService.Stored;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(JourneyTrainController.class)
@Import(SecurityConfig.class)
class JourneyTrainControllerTest {

    private static final String JSON = "application/json";
    private static final String URL = "/api/admin/journeys/5/train";
    private static final String BODY = "{\"uid\":\"X12345\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JourneyTrainService trains;

    private static MockHttpServletRequestBuilder give(String body) {
        return put(URL).with(user("owner")).header("X-CineFilm", "1").contentType(JSON).content(body);
    }

    private static MockHttpServletRequestBuilder takeAway() {
        return delete(URL).with(user("owner")).header("X-CineFilm", "1");
    }

    @Test
    void theBodyReachesTheServiceAsSentAndTheAnswerIs200WithItsShape() throws Exception {
        when(trains.give(5L, BODY)).thenReturn(new Stored("X12345", "2030-03-04", "9Z99", 8));

        mvc.perform(give(BODY))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.uid").value("X12345"))
            .andExpect(jsonPath("$.date").value("2030-03-04"))
            .andExpect(jsonPath("$.headcode").value("9Z99"))
            .andExpect(jsonPath("$.calls").value(8));

        verify(trains).give(5L, BODY);
    }

    @Test
    void aBodyOfExactlyTwoHundredThousandCharactersReachesTheServiceWhole() throws Exception {
        String body = "a".repeat(200_000);
        when(trains.give(5L, body)).thenReturn(new Stored("X12345", "2030-03-04", "9Z99", 8));

        mvc.perform(give(body)).andExpect(status().isOk());

        verify(trains).give(5L, body);
    }

    @Test
    void aBodyOneCharacterOverTheLimitReachesTheServiceAsNullAndIsA400() throws Exception {
        when(trains.give(5L, null)).thenThrow(new BadRequestException("Invalid train file"));

        mvc.perform(give("a".repeat(200_001)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid train file"));

        verify(trains).give(5L, null);
    }

    @Test
    void theDeleteAnswers204WithNoBodyAndCallsTheService() throws Exception {
        mvc.perform(takeAway()).andExpect(status().isNoContent()).andExpect(content().string(""));

        verify(trains).takeAway(5L);
    }

    @Test
    void theServicesRefusalsKeepTheirStatusesAndMessagesAsJson() throws Exception {
        when(trains.give(5L, BODY)).thenThrow(new NotFoundException("No such journey"),
            new BadRequestException("The train did not run during this journey"),
            new ConflictException("This train is stored with other end points"));

        mvc.perform(give(BODY)).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("No such journey"));
        mvc.perform(give(BODY)).andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("The train did not run during this journey"));
        mvc.perform(give(BODY)).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("This train is stored with other end points"));
    }

    @Test
    void anUnknownJourneyOnTheDeleteIsA404AsJson() throws Exception {
        doThrow(new NotFoundException("No such journey")).when(trains).takeAway(5L);

        mvc.perform(takeAway()).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("No such journey"));
    }

    @Test
    void aClashOrALockFailureOnThePutIsA409TryAgainAsJson() throws Exception {
        when(trains.give(5L, BODY)).thenThrow(new DataIntegrityViolationException("duplicate key"),
            new PessimisticLockingFailureException("deadlock"));

        mvc.perform(give(BODY)).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
        mvc.perform(give(BODY)).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
    }

    @Test
    void aClashOrALockFailureOnTheDeleteIsA409TryAgainAsJson() throws Exception {
        doThrow(new DataIntegrityViolationException("foreign key")).doThrow(new PessimisticLockingFailureException(
            "deadlock")).when(trains).takeAway(5L);

        mvc.perform(takeAway()).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
        mvc.perform(takeAway()).andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
    }

    @Test
    void anIdThatIsNotANumberIsA400OnBothRoutes() throws Exception {
        mvc.perform(put("/api/admin/journeys/abc/train").with(user("owner")).header("X-CineFilm", "1")
                .contentType(JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(delete("/api/admin/journeys/abc/train").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIsA401OnBothRoutes() throws Exception {
        mvc.perform(put(URL).header("X-CineFilm", "1").contentType(JSON).content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(delete(URL).header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theMissingCsrfHeaderIsA403OnBothRoutes() throws Exception {
        mvc.perform(put(URL).with(user("owner")).contentType(JSON).content(BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        mvc.perform(delete(URL).with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
