package gregochr.cinefilm.ratings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gregochr.cinefilm.ratings.OmdbResult.Outcome;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The OMDb client against mocked HTTP: no real network. */
class OmdbClientTest {

    private static final String KEY = "secret-key-123";
    private static final String HEAT_URL = "https://www.omdbapi.com/?i=tt0113277&apikey=" + KEY + "&tomatoes=true";
    private static final String HEAT = "{\"imdbRating\":\"8.3\",\"imdbVotes\":\"712,345\",\"Ratings\":"
        + "[{\"Source\":\"Rotten Tomatoes\",\"Value\":\"84%\"}],"
        + "\"tomatoURL\":\"https://www.rottentomatoes.com/m/heat\",\"Response\":\"True\"}";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://www.omdbapi.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OmdbClient client = new OmdbClient(builder.build(), KEY);

    @Test
    void aLookupAsksForTheImdbIdWithTomatoesAndParsesTheReply() {
        server.expect(once(), requestTo(HEAT_URL)).andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(withSuccess(HEAT, MediaType.APPLICATION_JSON));

        OmdbResult result = client.lookup("tt0113277");

        assertThat(result.outcome()).isEqualTo(Outcome.FOUND);
        assertThat(result.ratings()).isEqualTo(new OmdbRatings(new BigDecimal("8.3"), 712345, 84,
            "https://www.rottentomatoes.com/m/heat"));
        server.verify();
    }

    @Test
    void anUnknownFilmIsNotFound() {
        server.expect(once(), requestTo(HEAT_URL))
            .andRespond(withSuccess("{\"Response\":\"False\",\"Error\":\"Incorrect IMDb ID.\"}",
                MediaType.APPLICATION_JSON));

        assertThat(client.lookup("tt0113277").outcome()).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void theRequestLimitErrorAndARefusedKeyAndTooManyRequestsAllMeanStopForToday() {
        server.expect(once(), requestTo(HEAT_URL))
            .andRespond(withSuccess("{\"Response\":\"False\",\"Error\":\"Request limit reached!\"}",
                MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(HEAT_URL))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"Response\":\"False\",\"Error\":\"Invalid API key!\"}"));
        server.expect(once(), requestTo(HEAT_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.lookup("tt0113277").outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        assertThat(client.lookup("tt0113277").outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        assertThat(client.lookup("tt0113277").outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        server.verify();
    }

    @Test
    void aServerErrorIsAnOutage() {
        server.expect(once(), requestTo(HEAT_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.lookup("tt0113277")).isInstanceOf(OmdbUnavailableException.class)
            .hasMessage("OMDb answered status 502");
    }

    @Test
    void aTransportFailureIsAnOutageWhoseMessageAndCauseNeverCarryTheKey() {
        server.expect(once(), requestTo(HEAT_URL)).andRespond(request -> {
            throw new IOException("connection reset " + request.getURI());
        });

        assertThatThrownBy(() -> client.lookup("tt0113277")).isInstanceOf(OmdbUnavailableException.class)
            .hasMessage("OMDb could not be reached").hasNoCause()
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
    }

    @Test
    void anImdbIdIsEncodedIntoTheQueryNotSplicedIn() {
        server.expect(once(), requestTo("https://www.omdbapi.com/?i=tt1%262&apikey=" + KEY + "&tomatoes=true"))
            .andRespond(withSuccess("{\"Response\":\"False\"}", MediaType.APPLICATION_JSON));

        assertThat(client.lookup("tt1&2").outcome()).isEqualTo(Outcome.NOT_FOUND);
        server.verify();
    }

    @Test
    void aBlankOrMissingKeyDisablesTheClientAndALookupThrowsWithoutAnyRequest() {
        for (String blank : new String[] {"", "   ", null}) {
            OmdbClient off = new OmdbClient(builder.build(), blank);

            assertThat(off.enabled()).isFalse();
            assertThatThrownBy(() -> off.lookup("tt0113277")).isInstanceOf(OmdbUnavailableException.class)
                .hasMessage("No OMDb API key is configured");
        }
        server.verify();
    }

    @Test
    void aConfiguredKeyEnablesTheClientAndIsTrimmed() {
        assertThat(client.enabled()).isTrue();
        server.expect(once(), requestTo(HEAT_URL)).andRespond(withSuccess(HEAT, MediaType.APPLICATION_JSON));

        assertThat(new OmdbClient(builder.build(), "  " + KEY + "\n").lookup("tt0113277").outcome())
            .isEqualTo(Outcome.FOUND);
    }
}
