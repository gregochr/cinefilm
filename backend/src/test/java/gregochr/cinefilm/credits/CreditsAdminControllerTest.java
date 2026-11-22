package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.credits.CreditsAdminDtos.Counts;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsList;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsRow;
import gregochr.cinefilm.credits.CreditsAdminDtos.Plex;
import gregochr.cinefilm.credits.CreditsApply.PlexState;
import gregochr.cinefilm.film.CreditsMarkerSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(CreditsAdminController.class)
@Import(SecurityConfig.class)
class CreditsAdminControllerTest {

    private static final CreditsRow ROW = new CreditsRow(11, 5, "Heat", 1995, "Original", 2, 6_000_000L,
        5_000_000L, CreditsMarkerSource.PLEX, new Plex(PlexState.USABLE, 5_000_000L));
    private static final CreditsList LIST = new CreditsList(new Counts(4, 3, 2, 9), 1, 25, 4, List.of(ROW));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CreditsAdminService credits;

    @Test
    void noParametersAsksForTheFirstPageOfNeedsALookWithTwentyFiveRows() throws Exception {
        when(credits.list(CreditsFilter.NONE, 0, 25)).thenReturn(LIST);

        mvc.perform(get("/api/admin/credits").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void theParametersReachTheService() throws Exception {
        when(credits.list(CreditsFilter.OWNER, 3, 100)).thenReturn(LIST);

        mvc.perform(get("/api/admin/credits?filter=owner&page=3&size=100").with(user("owner")))
            .andExpect(status().isOk());

        verify(credits).list(CreditsFilter.OWNER, 3, 100);
    }

    @Test
    void theAnswerUsesTheFieldNamesTheScreenReads() throws Exception {
        when(credits.list(CreditsFilter.NONE, 0, 25)).thenReturn(LIST);

        mvc.perform(get("/api/admin/credits").with(user("owner")))
            .andExpect(jsonPath("$.counts.none").value(4))
            .andExpect(jsonPath("$.counts.plex").value(3))
            .andExpect(jsonPath("$.counts.owner").value(2))
            .andExpect(jsonPath("$.counts.all").value(9))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(25))
            .andExpect(jsonPath("$.items[0].editionId").value(11))
            .andExpect(jsonPath("$.items[0].filmId").value(5))
            .andExpect(jsonPath("$.items[0].title").value("Heat"))
            .andExpect(jsonPath("$.items[0].year").value(1995))
            .andExpect(jsonPath("$.items[0].editionName").value("Original"))
            .andExpect(jsonPath("$.items[0].editionCount").value(2))
            .andExpect(jsonPath("$.items[0].runtimeMs").value(6_000_000))
            .andExpect(jsonPath("$.items[0].markerMs").value(5_000_000))
            .andExpect(jsonPath("$.items[0].source").value("PLEX"))
            .andExpect(jsonPath("$.items[0].plex.state").value("USABLE"))
            .andExpect(jsonPath("$.items[0].plex.markerMs").value(5_000_000));
    }

    @Test
    void theAnswerHoldsNoFieldOutsideThePlansList() throws Exception {
        when(credits.list(CreditsFilter.NONE, 0, 25)).thenReturn(LIST);

        String body = mvc.perform(get("/api/admin/credits").with(user("owner"))).andReturn().getResponse()
            .getContentAsString();

        JsonNode json = JsonMapper.builder().build().readTree(body);
        assertThat(names(json)).containsExactlyInAnyOrder("counts", "page", "size", "total", "items");
        assertThat(names(json.get("counts"))).containsExactlyInAnyOrder("none", "plex", "owner", "all");
        assertThat(names(json.get("items").get(0))).containsExactlyInAnyOrder("editionId", "filmId", "title", "year",
            "editionName", "editionCount", "runtimeMs", "markerMs", "source", "plex");
        assertThat(names(json.get("items").get(0).get("plex"))).containsExactlyInAnyOrder("state", "markerMs");
    }

    private static List<String> names(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"filter=", "filter=None", "filter=auto", "filter=all,plex", "page=-1", "page=", "page=x",
        "page=1.5", "page=99999999999", "page=21474837&size=100", "size=0", "size=101", "size=-5", "size=", "size=ten"})
    void aParameterThatIsPresentButInvalidIs400(String query) throws Exception {
        mvc.perform(get("/api/admin/credits?" + query).with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));

        verifyNoInteractions(credits);
    }

    @Test
    void thePageAtTheLastOffsetAnIntCanHoldIsAccepted() throws Exception {
        when(credits.list(CreditsFilter.NONE, 21_474_836, 100)).thenReturn(LIST);

        mvc.perform(get("/api/admin/credits?page=21474836&size=100").with(user("owner")))
            .andExpect(status().isOk());
    }

    @Test
    void aRepeatedParameterTakesItsFirstValue() throws Exception {
        when(credits.list(CreditsFilter.PLEX, 0, 25)).thenReturn(LIST);

        mvc.perform(get("/api/admin/credits?filter=plex&filter=bogus").with(user("owner")))
            .andExpect(status().isOk());
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/admin/credits"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    // ---- PUT and DELETE /api/admin/editions/{id}/credits ----

    private static final String PATH = "/api/admin/editions/11/credits";

    private static MockHttpServletRequestBuilder putBody(String body) {
        return put(PATH).with(user("owner")).header("X-CineFilm", "1").contentType("application/json").content(body);
    }

    @Test
    void putSetsTheTimeInMillisecondsAndAnswersTheRow() throws Exception {
        when(credits.set(11, 6_720_000L)).thenReturn(ROW);

        mvc.perform(putBody("{\"markerMs\":6720000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editionId").value(11))
            .andExpect(jsonPath("$.markerMs").value(5_000_000))
            .andExpect(jsonPath("$.plex.state").value("USABLE"));

        verify(credits).set(11, 6_720_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"markerMs\":null}", "{\"markerMs\":6720000.4}", "{\"markerMs\":\"6720000\"}",
        "{\"markerMs\":-1000}", "{\"markerMs\":6720500}", "{\"markerMs\":true}", "{\"markerMs\":{}}",
        "{\"markerMs\":99999999999999999999}", "{\"markerMs\":9223372036854775807}", "null"})
    void aTimeThatIsNotAWholeNumberOfSecondsReachesTheServiceAsMissingAndIs400(String body) throws Exception {
        when(credits.set(11, null)).thenThrow(new BadRequestException("Invalid request"));

        mvc.perform(putBody(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));

        verify(credits).set(11, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "\"6720000\"", "6720000"})
    void anUnreadableBodyIs400WithoutReachingTheService(String body) throws Exception {
        mvc.perform(putBody(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));

        verifyNoInteractions(credits);
    }

    @Test
    void aMissingBodyReachesTheServiceAsAMissingTime() throws Exception {
        when(credits.set(11, null)).thenThrow(new BadRequestException("Invalid request"));

        mvc.perform(put(PATH).with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void aNonNumericEditionIdIs400() throws Exception {
        mvc.perform(delete("/api/admin/editions/abc/credits").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void theServicesRefusalsKeepTheirTextsAndStatuses() throws Exception {
        when(credits.set(11, 1_000L)).thenThrow(new BadRequestException("This film's length isn't known"));
        when(credits.set(11, 2_000L))
            .thenThrow(new BadRequestException("That time is outside where end credits can start"));
        when(credits.set(11, 3_000L)).thenThrow(new NotFoundException("No such edition"));
        when(credits.set(11, 4_000L)).thenThrow(new ConflictException("A scan is running"));

        mvc.perform(putBody("{\"markerMs\":1000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("This film's length isn't known"));
        mvc.perform(putBody("{\"markerMs\":2000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("That time is outside where end credits can start"));
        mvc.perform(putBody("{\"markerMs\":3000}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
        mvc.perform(putBody("{\"markerMs\":4000}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running"));
    }

    @Test
    void deleteClearsAndAnswersTheRow() throws Exception {
        when(credits.clear(11)).thenReturn(ROW);

        mvc.perform(delete(PATH).with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editionId").value(11))
            .andExpect(jsonPath("$.source").value("PLEX"));

        verify(credits).clear(11);
    }

    @Test
    void deleteKeepsTheServicesNotFoundAndBusyTexts() throws Exception {
        when(credits.clear(12)).thenThrow(new NotFoundException("No such edition"));
        when(credits.clear(13))
            .thenThrow(new ConflictException("Plex credits are being imported; try again in a moment"));

        mvc.perform(delete("/api/admin/editions/12/credits").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
        mvc.perform(delete("/api/admin/editions/13/credits").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Plex credits are being imported; try again in a moment"));
    }

    @Test
    void bothWritesSignedOutAre401() throws Exception {
        mvc.perform(put(PATH).header("X-CineFilm", "1").contentType("application/json")
                .content("{\"markerMs\":6720000}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(delete(PATH).header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void bothWritesWithoutTheCsrfHeaderAreRefused() throws Exception {
        mvc.perform(put(PATH).with(user("owner")).contentType("application/json").content("{\"markerMs\":6720000}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        mvc.perform(delete(PATH).with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
