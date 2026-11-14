package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.opensubtitles.OsFixtures.item;
import static gregochr.cinefilm.opensubtitles.OsFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The search for full English subtitles against mocked HTTP: the exact URL, and the same reply handling. */
class OpenSubtitlesClientFullSearchTest {

    private static final String KEY = "secret-key-123";
    private static final String FULL_URL = "https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=exclude"
        + "&imdb_id=936501&languages=en";

    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicLong ticks = new AtomicLong();
    private final LongSupplier clock = () -> ticks.addAndGet(1_000_000_000L);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.opensubtitles.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenSubtitlesClient client = new OpenSubtitlesClient(builder.build(), KEY, "", "", "0.4.0",
        sleeps::add, clock, OpenSubtitlesClient.MAX_BODY_BYTES, OpenSubtitlesClient.MAX_FILE_BYTES);

    @Test
    void theFullSearchSendsItsParametersInAlphabeticalOrderWithTheKeyOnlyInTheHeader() {
        server.expect(once(), requestTo(FULL_URL)).andExpect(method(HttpMethod.GET)).andExpect(header("Api-Key", KEY))
            .andRespond(request -> {
                assertThat(request.getURI().toString()).doesNotContain(KEY);
                return withSuccess(page(item(10).set("foreign_parts_only", "false")), MediaType.APPLICATION_JSON)
                    .createResponse(request);
            });

        OpenSubtitlesSearch search = client.searchFull("tt0936501");

        assertThat(search.count()).isEqualTo(1);
        assertThat(search.best().id()).isEqualTo(10);
        server.verify();
    }

    @Test
    void theFullSearchKeepsFullResultsAndTheForcedSearchStillAsksForForcedOnesAndNeitherSendsADefaultParameter() {
        server.expect(once(), requestTo(FULL_URL)).andRespond(withSuccess(
            page(item(1), item(2).set("foreign_parts_only", "false")), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
            "https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only&imdb_id=936501&languages=en"))
            .andRespond(withSuccess(page(item(1), item(2).set("foreign_parts_only", "false")),
                MediaType.APPLICATION_JSON));

        assertThat(client.searchFull("tt0936501").best().id()).isEqualTo(2);
        assertThat(client.searchForced("tt0936501").best().id()).isEqualTo(1);
        server.verify();
    }

    @Test
    void aRedirectAnsweringTheFullSearchIsAnOutageWithoutRetry() {
        server.expect(once(), requestTo(FULL_URL)).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
            .header("Location", "/api/v1/subtitles?foreign_parts_only=exclude&imdb_id=936501&languages=en"));

        assertThatThrownBy(() -> client.searchFull("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles answered status 301");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void aBadIdIsRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.searchFull("936501")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.searchFull(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.searchFull("tt0")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void aRejectedKeyIsAnOutageAndNeverNoResults() {
        server.expect(once(), requestTo(FULL_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.searchFull("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles rejected the API key");
    }

    @Test
    void withoutAKeyTheSearchIsUnavailable() {
        OpenSubtitlesClient keyless = new OpenSubtitlesClient(builder.build(), "", "", "", "0.4.0", sleeps::add, clock,
            OpenSubtitlesClient.MAX_BODY_BYTES, OpenSubtitlesClient.MAX_FILE_BYTES);

        assertThatThrownBy(() -> keyless.searchFull("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class);
    }
}
