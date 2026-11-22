package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
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
}
