package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** A film's keywords from {@code /3/movie/{id}/keywords}: their cleaning, an unknown film and an outage. */
class TmdbClientKeywordsTest {

    private static final String KEYWORDS = "https://api.themoviedb.org/3/movie/562/keywords";

    private final AtomicLong ticks = new AtomicLong();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbClient client = new TmdbClient(builder.build(), TmdbFixtures.TOKEN, d -> { },
        () -> ticks.addAndGet(1_000_000_000L));

    @Test
    void theKeywordNamesComeBackInTmdbsOrderUsingTheBearerToken() {
        server.expect(once(), requestTo(KEYWORDS))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TmdbFixtures.TOKEN))
            .andRespond(withSuccess("{\"id\":562,\"keywords\":[{\"id\":207317,\"name\":\"christmas\"},"
                + "{\"id\":14564,\"name\":\"terrorist\"},{\"id\":9715,\"name\":\"skyscraper\"}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.keywords(562)).contains(List.of("christmas", "terrorist", "skyscraper"));
        server.verify();
    }

    @Test
    void namesAreTrimmedLowerCasedAndDeduplicatedAndBlankOrMissingOnesDropped() {
        server.expect(once(), requestTo(KEYWORDS))
            .andRespond(withSuccess("{\"id\":562,\"keywords\":[{\"id\":1,\"name\":\"  Christmas \"},"
                + "{\"id\":2,\"name\":\"CHRISTMAS\"},{\"id\":3,\"name\":\"   \"},{\"id\":4},"
                + "{\"id\":5,\"name\":null},{\"id\":6,\"name\":\"Based On Novel\"}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.keywords(562)).contains(List.of("christmas", "based on novel"));
    }

    @Test
    void aFilmWithNoKeywordsOrNoKeywordsListGivesAnEmptyListNotAnUnknownFilm() {
        server.expect(once(), requestTo(KEYWORDS))
            .andRespond(withSuccess("{\"id\":562,\"keywords\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(KEYWORDS))
            .andRespond(withSuccess("{\"id\":562}", MediaType.APPLICATION_JSON));

        assertThat(client.keywords(562)).contains(List.of());
        assertThat(client.keywords(562)).contains(List.of());
    }

    @Test
    void anUnknownFilmIsEmptyNotAnError() {
        server.expect(once(), requestTo(KEYWORDS)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.keywords(562)).isEmpty();
    }

    @Test
    void anOutageIsATmdbUnavailableException() {
        server.expect(once(), requestTo(KEYWORDS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.keywords(562)).isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void theLookupIsRefusedWithoutATokenAndMakesNoRequest() {
        TmdbClient off = new TmdbClient(builder.build(), "", d -> { }, System::nanoTime);

        assertThatThrownBy(() -> off.keywords(562)).isInstanceOf(TmdbUnavailableException.class);
        server.verify();
    }
}
