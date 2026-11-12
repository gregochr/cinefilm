package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.opensubtitles.OsFixtures.item;
import static gregochr.cinefilm.opensubtitles.OsFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.tmdb.Sleeper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The OpenSubtitles client against mocked HTTP: no real network, and a fake sleeper so nothing really waits. */
class OpenSubtitlesClientTest {

    private static final String KEY = "secret-key-123";
    private static final String TAKEN_URL =
        "https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only&imdb_id=936501&languages=en";
    private static final String ONE_RESULT = page(item(10));

    private final List<Duration> sleeps = new ArrayList<>();
    /** A clock that moves a second on every reading, so request pacing never needs to wait. */
    private final AtomicLong ticks = new AtomicLong();
    private final LongSupplier clock = () -> ticks.addAndGet(1_000_000_000L);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.opensubtitles.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenSubtitlesClient client = clientWith(sleeps::add, clock, "0.4.0",
        OpenSubtitlesClient.MAX_BODY_BYTES);
    private final Logger clientLog = (Logger) LoggerFactory.getLogger(OpenSubtitlesClient.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private OpenSubtitlesClient clientWith(Sleeper sleeper, LongSupplier nanos, String version, int maxBytes) {
        return new OpenSubtitlesClient(builder.build(), KEY, "", "", version, sleeper, nanos, maxBytes,
            OpenSubtitlesClient.MAX_FILE_BYTES);
    }

    @BeforeEach
    void captureLog() {
        logged.start();
        clientLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        clientLog.detachAppender(logged);
    }

    private static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }

    @Test
    void aSearchSendsTheExactUrlWithTheParametersInAlphabeticalOrderAndTheHeaders() {
        server.expect(once(), requestTo(TAKEN_URL)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Api-Key", KEY))
            .andExpect(header(HttpHeaders.USER_AGENT, "CineFilm v0.4.0"))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(withSuccess(ONE_RESULT, json()));

        OpenSubtitlesSearch search = client.searchForced("tt0936501");

        assertThat(search.count()).isEqualTo(1);
        assertThat(search.best().id()).isEqualTo(10);
        server.verify();
    }

    @Test
    void theKeyIsNeverInTheUrl() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(request -> {
            assertThat(request.getURI().toString()).doesNotContain(KEY).doesNotContainIgnoringCase("apikey")
                .doesNotContainIgnoringCase("api_key").doesNotContainIgnoringCase("api-key");
            return withSuccess(ONE_RESULT, json()).createResponse(request);
        });

        client.searchForced("tt0936501");

        server.verify();
    }

    @Test
    void theImdbIdIsSentWithoutTheTtAndWithoutLeadingZeros() {
        server.expect(once(), requestTo(
            "https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only&imdb_id=123&languages=en"))
            .andRespond(withSuccess(page(), json()));
        server.expect(once(), requestTo("https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only"
            + "&imdb_id=9999999999&languages=en")).andRespond(withSuccess(page(), json()));
        server.expect(once(), requestTo(
            "https://api.opensubtitles.com/api/v1/subtitles?foreign_parts_only=only&imdb_id=1&languages=en"))
            .andRespond(withSuccess(page(), json()));

        client.searchForced("tt0000123");
        client.searchForced("tt9999999999");
        client.searchForced("tt1");

        server.verify();
    }

    @Test
    void anIdThatIsNotTtAndOneToTenDigitsIsRejectedWithoutAnyRequest() {
        for (String bad : new String[] {null, "", "tt", "936501", "tt093650x", "tt0936501\n", " tt0936501",
            "tt12345678901", "TT0936501", "tt-1", "tt1&languages=fr", "tt１２", "tt0000000", "imdb:tt1"}) {
            assertThatThrownBy(() -> client.searchForced(bad)).as("%s", bad)
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Not an IMDb id");
        }
        server.verify();
    }

    @Test
    void theFiltersAreAppliedToTheReply() {
        String body = page(
            item(1).set("ai_translated", "true"),
            item(2).set("machine_translated", "true"),
            item(3).set("foreign_parts_only", "false"),
            item(4).imdb("111"),
            item(5).set("fps", "25.0"),
            item(6).set("fps", "23.976"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(body, json()));

        OpenSubtitlesSearch search = client.searchForced("tt0936501");

        assertThat(search.count()).isEqualTo(2);
        assertThat(search.best().id()).isEqualTo(6);
    }

    @Test
    void anEmptyAndAMalformedAndAFieldlessReplyAreHandled() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess("{\"data\":[]}", json()));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess("not json", json()));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(page("{}", "{\"id\":\"3\"}"), json()));

        OpenSubtitlesSearch none = client.searchForced("tt0936501");
        assertThat(none.count()).isZero();
        assertThat(none.best()).isNull();
        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles returned unreadable JSON");
        assertThat(client.searchForced("tt0936501").count()).isZero();
    }

    @Test
    void aRedirectIsAnErrorAndIsNeverFollowedOrRetried() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "https://evil.example/steal"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
            .header(HttpHeaders.LOCATION, "/api/v1/subtitles?imdb_id=936501"));

        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles answered status 302");
        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles answered status 301");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void aRefusedKeyIsAnErrorThatSaysSoWithoutTheKeyAndIsNotRetried() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .contentType(json()).body("{\"message\":\"Invalid API key " + KEY + "\"}"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles rejected the API key").hasNoCause();
        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles rejected the API key");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void anotherClientErrorIsAnErrorAndIsNotRetried() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE));

        assertThatThrownBy(() -> client.searchForced("tt0936501")).hasMessage("OpenSubtitles answered status 404");
        assertThatThrownBy(() -> client.searchForced("tt0936501")).hasMessage("OpenSubtitles answered status 406");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void tooManyRequestsIsRetriedWithRetryAfterAndThenAnOutage() {
        server.expect(times(4), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "7"));

        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles unavailable after 4 attempts: status 429").hasNoCause();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(7), Duration.ofSeconds(7));
        server.verify();
    }

    @Test
    void retryAfterIsCappedAtThirtySeconds() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "3600"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "30"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "31"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(ONE_RESULT, json()));

        client.searchForced("tt0936501");

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @Test
    void anUnreadableMissingOrNegativeRetryAfterFallsBackToBackoffOrZero() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "-5"));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(ONE_RESULT, json()));

        client.searchForced("tt0936501");

        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ZERO);
    }

    @Test
    void aServerErrorIsRetriedWithExponentialBackoffAndThenAnOutage() {
        server.expect(times(4), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "7"));

        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles unavailable after 4 attempts: status 503");

        // Retry-After only counts for a 429.
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void aServerErrorThenAnAnswerIsAnAnswer() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(ONE_RESULT, json()));

        assertThat(client.searchForced("tt0936501").count()).isEqualTo(1);

        assertThat(sleeps).containsExactly(Duration.ofMillis(500));
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("OpenSubtitles status 502 (attempt 1 of 4), retrying");
    }

    @Test
    void aTransportFailureIsRetriedAndThenAnOutageWhoseMessageAndCauseNeverCarryTheKeyOrTheUrl() {
        server.expect(times(4), requestTo(TAKEN_URL)).andRespond(request -> {
            throw new IOException("connection reset " + request.getURI() + " " + KEY);
        });

        assertThatThrownBy(() -> client.searchForced("tt0936501")).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles unavailable after 4 attempts: no response").hasNoCause()
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("opensubtitles.com"));
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .allSatisfy(message -> assertThat(message).doesNotContain(KEY).doesNotContain("http"));
    }

    @Test
    void theKeyNeverAppearsInAnyLogLineOrExceptionMessageWhateverHappens() {
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .body("{\"message\":\"bad key " + KEY + "\"}"));

        assertThatThrownBy(() -> client.searchForced("tt0936501"))
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .allSatisfy(message -> assertThat(message).doesNotContain(KEY));
    }

    @Test
    void aReplyOverTheSizeCapIsAnErrorAndOneExactlyAtItIsRead() {
        OpenSubtitlesClient small = clientWith(sleeps::add, clock, "0.4.0", 100);
        String exactly = "{\"data\":[]}" + " ".repeat(100 - "{\"data\":[]}".length());
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(exactly, json()));
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(exactly + " ", json()));

        assertThat(exactly).hasSize(100);
        assertThat(small.searchForced("tt0936501").count()).isZero();
        assertThatThrownBy(() -> small.searchForced("tt0936501"))
            .isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles answered more than 100 bytes");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void aMegabyteIsTheDefaultCap() {
        assertThat(OpenSubtitlesClient.MAX_BODY_BYTES).isEqualTo(1_000_000);
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess("x".repeat(1_000_001), json()));

        assertThatThrownBy(() -> client.searchForced("tt0936501"))
            .hasMessage("OpenSubtitles answered more than 1000000 bytes");
    }

    @Test
    void requestsAreSpacedAQuarterOfASecondApart() {
        AtomicLong still = new AtomicLong(5_000_000_000L);
        OpenSubtitlesClient paced = clientWith(sleeps::add, still::get, "0.4.0", OpenSubtitlesClient.MAX_BODY_BYTES);
        server.expect(times(2), requestTo(TAKEN_URL)).andRespond(withSuccess(page(), json()));

        paced.searchForced("tt0936501");
        paced.searchForced("tt0936501");

        assertThat(OpenSubtitlesClient.PACE).isEqualTo(Duration.ofMillis(250));
        assertThat(sleeps).containsExactly(Duration.ofMillis(250));
    }

    @Test
    void aBlankOrMissingKeyDisablesTheClientAndASearchThrowsWithoutAnyRequest() {
        for (String blank : new String[] {"", "   ", null}) {
            OpenSubtitlesClient off = new OpenSubtitlesClient(builder.build(), blank, "", "", "0.4.0", sleeps::add,
                clock, OpenSubtitlesClient.MAX_BODY_BYTES, OpenSubtitlesClient.MAX_FILE_BYTES);

            assertThat(off.enabled()).isFalse();
            assertThatThrownBy(() -> off.searchForced("tt0936501"))
                .isInstanceOf(OpenSubtitlesUnavailableException.class)
                .hasMessage("No OpenSubtitles API key is configured");
        }
        server.verify();
    }

    @Test
    void aConfiguredKeyEnablesTheClientAndIsTrimmed() {
        assertThat(client.enabled()).isTrue();
        server.expect(once(), requestTo(TAKEN_URL)).andExpect(header("Api-Key", KEY))
            .andRespond(withSuccess(ONE_RESULT, json()));

        new OpenSubtitlesClient(builder.build(), "  " + KEY + "\n", "", "", "0.4.0", sleeps::add, clock,
            OpenSubtitlesClient.MAX_BODY_BYTES, OpenSubtitlesClient.MAX_FILE_BYTES).searchForced("tt0936501");

        server.verify();
    }

    @Test
    void theUserAgentNamesCineFilmAndTheVersionWithoutALeadingV() {
        for (String[] pair : new String[][] {{"0.4.0", "CineFilm v0.4.0"}, {"v0.4.0", "CineFilm v0.4.0"},
            {"V1.2.3-rc1+5", "CineFilm v1.2.3-rc1+5"}, {"  2.0  ", "CineFilm v2.0"}, {"1", "CineFilm v1"},
            {"dev", "CineFilm v1"}, {"", "CineFilm v1"}, {null, "CineFilm v1"}, {"v", "CineFilm v1"},
            {"1.0\r\nX-Evil: 1", "CineFilm v1"}, {"1.0 beta", "CineFilm v1"}}) {
            server.reset();
            server.expect(once(), requestTo(TAKEN_URL)).andExpect(header(HttpHeaders.USER_AGENT, pair[1]))
                .andRespond(withSuccess(page(), json()));

            clientWith(sleeps::add, clock, pair[0], OpenSubtitlesClient.MAX_BODY_BYTES).searchForced("tt0936501");

            server.verify();
        }
    }

    @Test
    void anInterruptedWaitBetweenRetriesStopsTheSearchAndKeepsTheInterrupt() {
        OpenSubtitlesClient interrupted = clientWith(duration -> {
            throw new InterruptedException("stop");
        }, clock, "0.4.0", OpenSubtitlesClient.MAX_BODY_BYTES);
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        try {
            assertThatThrownBy(() -> interrupted.searchForced("tt0936501"))
                .isInstanceOf(OpenSubtitlesUnavailableException.class)
                .hasMessage("Interrupted while waiting to retry OpenSubtitles").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptedWaitForTheNextSlotStopsTheSearchAndKeepsTheInterrupt() {
        AtomicLong still = new AtomicLong(5_000_000_000L);
        OpenSubtitlesClient interrupted = clientWith(duration -> {
            throw new InterruptedException("stop");
        }, still::get, "0.4.0", OpenSubtitlesClient.MAX_BODY_BYTES);
        server.expect(once(), requestTo(TAKEN_URL)).andRespond(withSuccess(page(), json()));
        interrupted.searchForced("tt0936501");

        try {
            assertThatThrownBy(() -> interrupted.searchForced("tt0936501"))
                .isInstanceOf(OpenSubtitlesUnavailableException.class)
                .hasMessage("Interrupted while waiting for OpenSubtitles").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
