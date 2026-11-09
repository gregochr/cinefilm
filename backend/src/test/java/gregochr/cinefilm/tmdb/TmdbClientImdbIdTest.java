package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The IMDb id: read from a movie's details, and fetched from {@code external_ids} for films stored without one. */
class TmdbClientImdbIdTest {

    private final AtomicLong ticks = new AtomicLong();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbClient client = new TmdbClient(builder.build(), TmdbFixtures.TOKEN, d -> { },
        () -> ticks.addAndGet(1_000_000_000L));

    private static final String EXTERNAL_IDS = "https://api.themoviedb.org/3/movie/949/external_ids";

    @Test
    void aMovieCarriesItsImdbId() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/949?append_to_response=credits"))
            .andRespond(withSuccess(TmdbFixtures.recorded("movie-949.json"), MediaType.APPLICATION_JSON));

        assertThat(client.movie(949).orElseThrow().imdbId()).isEqualTo("tt0113277");
    }

    @Test
    void aMovieWithABlankOrMissingImdbIdHasNone() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/1?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":1,\"imdb_id\":\"\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/2?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":2,\"imdb_id\":null}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/3?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":3}", MediaType.APPLICATION_JSON));

        assertThat(client.movie(1).orElseThrow().imdbId()).isNull();
        assertThat(client.movie(2).orElseThrow().imdbId()).isNull();
        assertThat(client.movie(3).orElseThrow().imdbId()).isNull();
    }

    @Test
    void theExternalIdsEndpointGivesTheImdbIdUsingTheBearerToken() {
        server.expect(once(), requestTo(EXTERNAL_IDS))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TmdbFixtures.TOKEN))
            .andRespond(withSuccess("{\"id\":949,\"imdb_id\":\"tt0113277\",\"wikidata_id\":\"Q1\"}",
                MediaType.APPLICATION_JSON));

        assertThat(client.imdbId(949)).contains("tt0113277");
        server.verify();
    }

    @Test
    void aBlankNullOrMissingImdbIdInExternalIdsIsEmpty() {
        server.expect(once(), requestTo(EXTERNAL_IDS))
            .andRespond(withSuccess("{\"id\":949,\"imdb_id\":\"\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(EXTERNAL_IDS))
            .andRespond(withSuccess("{\"id\":949,\"imdb_id\":null}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(EXTERNAL_IDS))
            .andRespond(withSuccess("{\"id\":949}", MediaType.APPLICATION_JSON));

        assertThat(client.imdbId(949)).isEmpty();
        assertThat(client.imdbId(949)).isEmpty();
        assertThat(client.imdbId(949)).isEmpty();
    }

    @Test
    void anUnknownFilmIsEmptyNotAnError() {
        server.expect(once(), requestTo(EXTERNAL_IDS)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.imdbId(949)).isEmpty();
    }

    @Test
    void anOutageIsATmdbUnavailableException() {
        server.expect(once(), requestTo(EXTERNAL_IDS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.imdbId(949)).isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void theLookupIsRefusedWithoutATokenAndMakesNoRequest() {
        TmdbClient off = new TmdbClient(builder.build(), "", d -> { }, System::nanoTime);

        assertThatThrownBy(() -> off.imdbId(949)).isInstanceOf(TmdbUnavailableException.class);
        server.verify();
    }
}
