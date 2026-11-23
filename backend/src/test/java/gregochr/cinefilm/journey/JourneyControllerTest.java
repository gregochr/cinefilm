package gregochr.cinefilm.journey;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.journey.JourneyImportService.Imported;
import gregochr.cinefilm.journey.JourneyImportService.Result;
import gregochr.cinefilm.journey.JourneyService.DeviceView;
import gregochr.cinefilm.journey.JourneyService.Entry;
import gregochr.cinefilm.journey.JourneyService.Listing;
import gregochr.cinefilm.journey.JourneyService.TrainView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(JourneyController.class)
@Import(SecurityConfig.class)
class JourneyControllerTest {

    private static final String JSON = "application/json";
    private static final String URL = "/api/admin/journeys";
    private static final String BODY = "{\"runStartedMs\":1790000000000}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JourneyImportService imports;

    @MockitoBean
    private JourneyService journeys;

    private static MockHttpServletRequestBuilder signedIn(String body) {
        return post(URL).with(user("owner")).header("X-CineFilm", "1").contentType(JSON).content(body);
    }

    @Test
    void theBodyReachesTheServiceAsSentAndTheAnswerIs201WithTheJourneysShape() throws Exception {
        Instant from = Instant.parse("2026-09-21T10:00:30Z");
        Instant to = Instant.parse("2026-09-21T10:02:00Z");
        when(imports.importFile(BODY)).thenReturn(new Result(List.of(new Imported(100L, 0, "Unit-001", 4, from, to, 2),
            new Imported(101L, 1, null, 2, to, to, 0))));

        mvc.perform(signedIn(BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.journeys.length()").value(2))
            .andExpect(jsonPath("$.journeys[0].id").value(100))
            .andExpect(jsonPath("$.journeys[0].segment").value(0))
            .andExpect(jsonPath("$.journeys[0].trainUnit").value("Unit-001"))
            .andExpect(jsonPath("$.journeys[0].samples").value(4))
            .andExpect(jsonPath("$.journeys[0].from").value("2026-09-21T10:00:30Z"))
            .andExpect(jsonPath("$.journeys[0].to").value("2026-09-21T10:02:00Z"))
            .andExpect(jsonPath("$.journeys[0].linked").value(2))
            .andExpect(jsonPath("$.journeys[1].segment").value(1))
            .andExpect(jsonPath("$.journeys[1].linked").value(0));

        verify(imports).importFile(BODY);
    }

    @Test
    void aBodyOfExactlyTheLimitReachesTheServiceWhole() throws Exception {
        String body = "a".repeat(JourneyImportService.MAX_BODY_LENGTH);
        when(imports.importFile(body)).thenReturn(new Result(List.of()));

        mvc.perform(signedIn(body)).andExpect(status().isCreated());

        verify(imports).importFile(body);
    }

    @Test
    void aBodyOneCharacterOverTheLimitReachesTheServiceAsNullAndIsA400() throws Exception {
        when(imports.importFile(null)).thenThrow(new BadRequestException("Invalid journey file"));

        mvc.perform(signedIn("a".repeat(JourneyImportService.MAX_BODY_LENGTH + 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid journey file"));

        verify(imports).importFile(null);
    }

    @Test
    void nonAsciiTextArrivesIntact() throws Exception {
        String body = "{\"sourceFile\":\"Café 北京\"}";
        when(imports.importFile(body)).thenReturn(new Result(List.of()));

        mvc.perform(post(URL).with(user("owner")).header("X-CineFilm", "1").contentType(JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isCreated());

        verify(imports).importFile(body);
    }

    @Test
    void aRefusalFromTheServiceIsA400WithItsMessage() throws Exception {
        when(imports.importFile(BODY)).thenThrow(new BadRequestException("Invalid journey file"));

        mvc.perform(signedIn(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Invalid journey file"));
    }

    @Test
    void aFileAlreadyImportedIsA409AsJson() throws Exception {
        when(imports.importFile(BODY)).thenThrow(new ConflictException("Already imported"));

        mvc.perform(signedIn(BODY))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Already imported"));
    }

    @Test
    void aClashOnAUniqueKeyIsA409TryAgainAsJson() throws Exception {
        when(imports.importFile(BODY)).thenThrow(new DataIntegrityViolationException("duplicate key"));

        mvc.perform(signedIn(BODY))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(post(URL).header("X-CineFilm", "1").contentType(JSON).content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theMissingCsrfHeaderIsA403() throws Exception {
        mvc.perform(post(URL).with(user("owner")).contentType(JSON).content(BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    private static MockHttpServletRequestBuilder deleteOne() {
        return delete(URL + "/5").with(user("owner")).header("X-CineFilm", "1");
    }

    @Test
    void theListAnswers200WithTheWholeOfAnEntryAndTheNullsWritten() throws Exception {
        when(journeys.list()).thenReturn(new Listing(List.of(
            new Entry(11L, 1_790_000_000_000L, 2, "Unit-001", "standard", "TestOS 1.2", 3, "test-log.jsonl",
                Instant.parse("2026-09-22T08:00:00Z"),
                new DeviceView("Testbook1,1", "Test Chip One", 8, 8, 17_179_869_184L), 4,
                Instant.parse("2026-09-21T14:13:50Z"), Instant.parse("2026-09-21T14:15:20Z"), 2,
                new TrainView("X12345", "2030-03-04", "9Z99")),
            new Entry(12L, 1_780_000_000_000L, 0, null, null, null, 3, null, Instant.parse("2026-09-22T08:00:00Z"),
                null, 0, null, null, 0, null))));

        mvc.perform(get(URL).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(content().json("""
                {"journeys":[
                  {"id":11,"runStartedMs":1790000000000,"segment":2,"trainUnit":"Unit-001","travelClass":"standard",
                   "osVersion":"TestOS 1.2","loggerVersion":3,"sourceFile":"test-log.jsonl",
                   "importedAt":"2026-09-22T08:00:00Z",
                   "device":{"model":"Testbook1,1","cpu":"Test Chip One","cores":8,"threads":8,
                             "memoryBytes":17179869184},
                   "samples":4,"from":"2026-09-21T14:13:50Z","to":"2026-09-21T14:15:20Z","linked":2,
                   "train":{"uid":"X12345","date":"2030-03-04","headcode":"9Z99"}},
                  {"id":12,"runStartedMs":1780000000000,"segment":0,"trainUnit":null,"travelClass":null,
                   "osVersion":null,"loggerVersion":3,"sourceFile":null,"importedAt":"2026-09-22T08:00:00Z",
                   "device":null,"samples":0,"from":null,"to":null,"linked":0,"train":null}]}
                """, true));
    }

    @Test
    void theListSignedOutIsA401() throws Exception {
        mvc.perform(get(URL))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void aDeleteAnswers204WithNoBodyAndAsksTheServiceForThatJourney() throws Exception {
        mvc.perform(deleteOne())
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(journeys).delete(5L);
    }

    @Test
    void aDeleteSignedOutIsA401() throws Exception {
        mvc.perform(delete(URL + "/5").header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void aDeleteWithoutTheCsrfHeaderIsA403() throws Exception {
        mvc.perform(delete(URL + "/5").with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void aDeleteOfAnUnknownJourneyIsA404AsJson() throws Exception {
        doThrow(new NotFoundException("No such journey")).when(journeys).delete(5L);

        mvc.perform(deleteOne())
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("No such journey"));
    }

    @Test
    void aDeleteThatClashesOnAKeyIsA409TryAgainAsJson() throws Exception {
        doThrow(new DataIntegrityViolationException("foreign key")).when(journeys).delete(5L);

        mvc.perform(deleteOne())
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
    }

    @Test
    void aDeleteThatTheDatabaseEndsOnALockIsA409TryAgainAsJson() throws Exception {
        doThrow(new PessimisticLockingFailureException("deadlock")).when(journeys).delete(5L);

        mvc.perform(deleteOne())
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(jsonPath("$.error").value("Try again"));
    }
}
