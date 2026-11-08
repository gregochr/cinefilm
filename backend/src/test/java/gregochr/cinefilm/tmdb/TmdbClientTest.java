package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Credit;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Part;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The TMDB client against recorded responses, with a fake sleeper so backoff costs no real time. */
class TmdbClientTest {

    private static final String TOKEN = TmdbFixtures.TOKEN;
    private static final String SEARCH_HEAT = "https://api.themoviedb.org/3/search/movie?query=Heat&include_adult=false"
        + "&year=1995";

    private final List<Duration> sleeps = new ArrayList<>();
    /** A clock that moves a second on every reading, so request pacing never needs to wait. */
    private final AtomicLong ticks = new AtomicLong();
    private final LongSupplier clock = () -> ticks.addAndGet(1_000_000_000L);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbClient client = new TmdbClient(builder.build(), TOKEN, sleeps::add, clock);

    private static String json(String recorded) {
        return TmdbFixtures.recorded(recorded);
    }

    @Test
    void aSearchSendsTheTokenAsABearerHeaderAndNeverInTheUrl() {
        server.expect(once(), requestTo(SEARCH_HEAT)).andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(request -> {
                assertThat(request.getURI().toString()).doesNotContain(TOKEN).doesNotContain("api_key");
                return withSuccess(json("search-heat-1995.json"), MediaType.APPLICATION_JSON).createResponse(request);
            });

        List<SearchHit> hits = client.search("Heat", 1995);

        assertThat(hits).hasSize(8);
        assertThat(hits.get(0)).isEqualTo(new SearchHit(949, "Heat", "Heat", 1995, hits.get(0).popularity(),
            "/umSVjVdbVwtx5ryCA2QXL44Durm.jpg"));
        assertThat(hits.get(0).popularity()).isGreaterThan(10);
        assertThat(hits.get(1).title()).isEqualTo("Secret Heat");
        server.verify();
    }

    @Test
    void theYearParameterIsLeftOutWhenThereIsNoYearAndTheTitleIsEncoded() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/search/movie?query=Alien%20%26%20Co%3F"
                + "&include_adult=false"))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.search("Alien & Co?", null)).isEmpty();
        server.verify();
    }

    @Test
    void aMovieIsReadWithGenresDirectorsAndTheTopTenCast() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/949?append_to_response=credits"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andRespond(withSuccess(json("movie-949.json"), MediaType.APPLICATION_JSON));

        MovieDetails heat = client.movie(949).orElseThrow();

        assertThat(heat.id()).isEqualTo(949);
        assertThat(heat.title()).isEqualTo("Heat");
        assertThat(heat.year()).isEqualTo(1995);
        assertThat(heat.runtimeMinutes()).isEqualTo(170);
        assertThat(heat.genres()).containsExactly("Crime", "Drama", "Action");
        assertThat(heat.posterPath()).isEqualTo("/umSVjVdbVwtx5ryCA2QXL44Durm.jpg");
        assertThat(heat.backdropPath()).isNotBlank();
        assertThat(heat.overview()).isNotBlank();
        assertThat(heat.directors()).extracting(Credit::name).containsExactly("Michael Mann");
        assertThat(heat.cast()).hasSize(10);
        assertThat(heat.cast().get(0).name()).isEqualTo("Al Pacino");
        assertThat(heat.cast().get(0).character()).isEqualTo("Lt. Vincent Hanna");
        assertThat(heat.cast().get(1).name()).isEqualTo("Robert De Niro");
        assertThat(heat.collection()).isEqualTo(new TmdbModels.CollectionRef(1048282, "Heat Collection",
            "/e7H6NLnhioMqOizYAqHmZyYxNID.jpg"));
    }

    @Test
    void aMovieWithNoCollectionOrRuntimeHasNullsNotZeros() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/7?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":7,\"title\":\"Odd\",\"release_date\":\"\",\"runtime\":0,"
                + "\"belongs_to_collection\":null,\"genres\":[],\"credits\":{}}", MediaType.APPLICATION_JSON));

        MovieDetails odd = client.movie(7).orElseThrow();

        assertThat(odd.collection()).isNull();
        assertThat(odd.runtimeMinutes()).isNull();
        assertThat(odd.year()).isNull();
        assertThat(odd.overview()).isNull();
        assertThat(odd.posterPath()).isNull();
        assertThat(odd.directors()).isEmpty();
        assertThat(odd.cast()).isEmpty();
    }

    @Test
    void blankNullAndNonTextValuesAreAbsentAndAFourDigitDateIsAYear() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/3?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":3,\"title\":\"  \",\"original_title\":null,\"overview\":5,"
                + "\"poster_path\":{\"a\":1},\"release_date\":\"1995\",\"genres\":[]}", MediaType.APPLICATION_JSON));

        MovieDetails odd = client.movie(3).orElseThrow();

        assertThat(odd.title()).isNull();
        assertThat(odd.originalTitle()).isNull();
        assertThat(odd.overview()).isNull();
        assertThat(odd.posterPath()).isNull();
        assertThat(odd.year()).isEqualTo(1995);
    }

    @Test
    void aCollectionListsItsPartsOldestFirst() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/collection/8091"))
            .andRespond(withSuccess(json("collection-8091.json"), MediaType.APPLICATION_JSON));

        CollectionDetails alien = client.collection(8091).orElseThrow();

        assertThat(alien.id()).isEqualTo(8091);
        assertThat(alien.name()).isEqualTo("Alien Collection");
        assertThat(alien.parts()).extracting(Part::title)
            .containsExactly("Alien", "Aliens", "Alien³", "Alien Resurrection");
        assertThat(alien.parts()).extracting(Part::year).containsExactly(1979, 1986, 1992, 1997);
    }

    @Test
    void collectionPartsWithoutAReleaseDateComeLastThenById() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/collection/1"))
            .andRespond(withSuccess("{\"id\":1,\"name\":\"C\",\"parts\":[{\"id\":9,\"title\":\"Undated B\"},"
                + "{\"id\":3,\"title\":\"Undated A\",\"release_date\":\"\"},"
                + "{\"id\":5,\"title\":\"Dated\",\"release_date\":\"2001-05-05\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.collection(1).orElseThrow().parts()).extracting(Part::title)
            .containsExactly("Dated", "Undated A", "Undated B");
    }

    @Test
    void aFilmIsFetchedOnceAndThenServedFromTheCache() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/949?append_to_response=credits"))
            .andRespond(withSuccess(json("movie-949.json"), MediaType.APPLICATION_JSON));

        MovieDetails first = client.movie(949).orElseThrow();
        MovieDetails second = client.movie(949).orElseThrow();

        assertThat(second).isSameAs(first);
        server.verify();
    }

    @Test
    void aCollectionIsFetchedOnceAndThenServedFromTheCache() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/collection/8091"))
            .andRespond(withSuccess(json("collection-8091.json"), MediaType.APPLICATION_JSON));

        assertThat(client.collection(8091)).isSameAs(client.collection(8091));
        server.verify();
    }

    @Test
    void anUnknownFilmIsEmptyAndTheAnswerIsCachedToo() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/1?append_to_response=credits"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.movie(1)).isEmpty();
        assertThat(client.movie(1)).isEmpty();
        server.verify();
    }

    @Test
    void anUnknownCollectionIsEmpty() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/collection/1"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.collection(1)).isEmpty();
    }

    @Test
    void aSearchThatAnswersNotFoundHasNoHits() {
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.search("Heat", 1995)).isEmpty();
    }

    @Test
    void a429IsRetriedAfterTheDelayTmdbAsksFor() {
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "3"));
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andRespond(withSuccess(json("search-heat-1995.json"), MediaType.APPLICATION_JSON));

        assertThat(client.search("Heat", 1995)).hasSize(8);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
        server.verify();
    }

    @Test
    void aRetryAfterBeyondThirtySecondsIsCapped() {
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "3600"));
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        client.search("Heat", 1995);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void aRetryAfterOfExactlyThirtySecondsIsKept() {
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        client.search("Heat", 1995);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void a429WithoutAUsableRetryAfterBacksOffExponentially() {
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "soon"));
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        client.search("Heat", 1995);

        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    @Test
    void serverErrorsAreRetriedWithDoublingBackoffUntilItWorks() {
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess(json("search-heat-1995.json"), MediaType.APPLICATION_JSON));

        assertThat(client.search("Heat", 1995)).hasSize(8);
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000), Duration.ofMillis(2000));
        server.verify();
    }

    @Test
    void anOutageGivesUpAfterFourAttemptsWithATokenFreeMessage() {
        server.expect(manyTimes(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB unavailable after 4 attempts: status 502");
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void aNetworkFailureIsRetriedLikeAServerError() {
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(request -> {
            throw new IOException("connection reset");
        });
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.search("Heat", 1995)).isEmpty();
        assertThat(sleeps).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void aNetworkFailureThatNeverClearsIsAnOutage() {
        server.expect(manyTimes(), requestTo(SEARCH_HEAT)).andRespond(request -> {
            throw new IOException("connection reset");
        });

        assertThatThrownBy(() -> client.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB unavailable after 4 attempts: no response");
    }

    @Test
    void aRejectedTokenFailsAtOnceWithoutRetryingOrLeakingIt() {
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB answered status 401")
            .message().doesNotContain(TOKEN);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void unreadableJsonIsAnOutage() {
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("TMDB returned unreadable JSON");
    }

    @Test
    void withoutATokenNothingIsSentAndTheClientIsDisabled() {
        TmdbClient anonymous = new TmdbClient(builder.build(), "  ", sleeps::add, clock);

        assertThat(anonymous.enabled()).isFalse();
        assertThatThrownBy(() -> anonymous.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
            .hasMessage("No TMDB read token is configured");
        assertThat(new TmdbClient(builder.build(), null, sleeps::add, clock).enabled()).isFalse();
        assertThat(client.enabled()).isTrue();
        server.verify();
    }

    @Test
    void theTokenIsTrimmedBeforeUse() {
        TmdbClient padded = new TmdbClient(builder.build(), "  " + TOKEN + "\n", sleeps::add, clock);
        server.expect(once(), requestTo(SEARCH_HEAT))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        padded.search("Heat", 1995);

        server.verify();
    }

    @Test
    void requestsAreSpacedFiftyMillisecondsApartToStayUnderTwentyPerSecond() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        TmdbClient paced = new TmdbClient(builder.build(), TOKEN, sleeps::add, nanos::get);
        server.expect(manyTimes(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        paced.search("Heat", 1995);
        paced.search("Heat", 1995);

        assertThat(sleeps).containsExactly(Duration.ofMillis(50));
    }

    @Test
    void aThreadInterruptedWhileBackingOffStopsWithAnOutageAndKeepsTheFlag() {
        TmdbClient interrupted = new TmdbClient(builder.build(), TOKEN, d -> {
            throw new InterruptedException();
        }, clock);
        server.expect(once(), requestTo(SEARCH_HEAT)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        try {
            assertThatThrownBy(() -> interrupted.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("Interrupted while waiting to retry TMDB");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aThreadInterruptedWhilePacingStopsWithAnOutage() {
        AtomicLong nanos = new AtomicLong(0);
        TmdbClient interrupted = new TmdbClient(builder.build(), TOKEN, d -> {
            throw new InterruptedException();
        }, nanos::get);
        server.expect(manyTimes(), requestTo(SEARCH_HEAT))
            .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        interrupted.search("Heat", 1995);

        try {
            assertThatThrownBy(() -> interrupted.search("Heat", 1995)).isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("Interrupted while waiting for TMDB");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theRecordedFixtureServerAnswersTheSameWayAsTheRealClientSeesIt() {
        TmdbFixtures fixtures = new TmdbFixtures();
        TmdbClient fromFixtures = fixtures.client();

        assertThat(fromFixtures.search("Heat", 1995).get(0).id()).isEqualTo(949);
        assertThat(fromFixtures.movie(949).orElseThrow().runtimeMinutes()).isEqualTo(170);
        assertThat(fromFixtures.movie(424242)).isEmpty();
        assertThat(fromFixtures.search("Never Recorded Film", null)).isEmpty();
        assertThat(fixtures.requests()).containsExactly("/3/search/movie?query=Heat&include_adult=false&year=1995",
            "/3/movie/949?append_to_response=credits", "/3/movie/424242?append_to_response=credits",
            "/3/search/movie?query=Never Recorded Film&include_adult=false");
    }

    @Test
    void onlyDirectorsAreTakenFromTheCrewAndOddReleaseDatesAreIgnored() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/1?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":1,\"title\":\"A\",\"release_date\":\"12\",\"credits\":{\"crew\":["
                + "{\"id\":5,\"name\":\"P\",\"job\":\"Producer\"},"
                + "{\"id\":6,\"name\":\"D\",\"job\":\"Director\"}]}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/2?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":2,\"title\":\"B\",\"release_date\":\"abcd-01-01\"}",
                MediaType.APPLICATION_JSON));

        MovieDetails first = client.movie(1).orElseThrow();

        assertThat(first.directors()).extracting(Credit::name).containsExactly("D");
        assertThat(first.year()).isNull();
        assertThat(client.movie(2).orElseThrow().year()).isNull();
    }
}
