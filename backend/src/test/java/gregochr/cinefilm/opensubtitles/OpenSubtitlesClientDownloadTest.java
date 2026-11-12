package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.tmdb.Sleeper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The OpenSubtitles client's login and download against mocked HTTP (nothing here touches the network): the login
 * request, the kept token and the single re-login, the account's API host rule, the quota signals, and every guard on
 * the download link (host allowlist, https only, no redirects, size cap while reading, no credentials sent to it).
 */
class OpenSubtitlesClientDownloadTest {

    private static final String KEY = "secret-key-123";
    private static final String USER = "owner-name";
    private static final String PASSWORD = "pa55-secret-word";
    private static final String TOKEN = "header.payload-secret.signature";
    private static final String LOGIN_URL = "https://api.opensubtitles.com/api/v1/login";
    private static final String DOWNLOAD_URL = "https://api.opensubtitles.com/api/v1/download";
    private static final String LINK = "https://dl.opensubtitles.com/file/abc.srt";
    private static final String SRT = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n"
        + "2\n00:00:20,000 --> 00:00:22,500\nWorld\n\n";

    private final List<Duration> sleeps = new ArrayList<>();
    /** A clock that moves a second on every reading, so request pacing never needs to wait. */
    private final AtomicLong ticks = new AtomicLong();
    private final LongSupplier clock = () -> ticks.addAndGet(1_000_000_000L);
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.opensubtitles.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenSubtitlesClient client = clientWith(sleeps::add, clock, 1_000_000, 100);
    private final Logger clientLog = (Logger) LoggerFactory.getLogger(OpenSubtitlesClient.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private OpenSubtitlesClient clientWith(Sleeper sleeper, LongSupplier nanos, int maxBody, int maxFile) {
        return new OpenSubtitlesClient(builder.build(), KEY, USER, PASSWORD, "0.4.0", sleeper, nanos, maxBody,
            maxFile);
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

    private static String login(String token, String baseUrl) {
        return "{\"user\":{\"allowed_downloads\":100,\"remaining_downloads\":100},\"base_url\":"
            + (baseUrl == null ? "null" : "\"" + baseUrl + "\"") + ",\"token\":\"" + token + "\",\"status\":200}";
    }

    private static String download(String link) {
        return "{\"link\":\"" + link + "\",\"file_name\":\"a.srt\",\"requests\":3,\"remaining\":87,"
            + "\"message\":\"Your quota will be renewed in 23 hours\",\"reset_time\":\"23 hours\","
            + "\"reset_time_utc\":\"2026-11-13T00:00:00.000Z\"}";
    }

    /** A login that succeeds with the default host, then a download that answers with the usual link. */
    private void expectLogin() {
        server.expect(once(), requestTo(LOGIN_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(login(TOKEN, "api.opensubtitles.com"), json()));
    }

    private void expectDownload(String body) {
        server.expect(once(), requestTo(DOWNLOAD_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, json()));
    }

    private void expectFile(String text) {
        server.expect(once(), requestTo(LINK)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(text, MediaType.TEXT_PLAIN));
    }

    @Test
    void theLoginIsAPostWithTheKeyTheUserAgentAndTheCredentialsAsJson() {
        server.expect(once(), requestTo(LOGIN_URL)).andExpect(method(HttpMethod.POST))
            .andExpect(header("Api-Key", KEY)).andExpect(header(HttpHeaders.USER_AGENT, "CineFilm v0.4.0"))
            .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
            .andExpect(content().string("{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andRespond(withSuccess(login(TOKEN, "api.opensubtitles.com"), json()));
        expectDownload(download(LINK));
        expectFile(SRT);

        client.download(7);

        server.verify();
    }

    @Test
    void theDownloadIsAPostWithTheBearerTokenTheKeyAndTheFileIdAsJson() {
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andExpect(header("Api-Key", KEY))
            .andExpect(header(HttpHeaders.USER_AGENT, "CineFilm v0.4.0"))
            .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andExpect(content().string("{\"file_id\":1234567890123}"))
            .andRespond(withSuccess(download(LINK), json()));
        expectFile(SRT);

        OpenSubtitlesDownload result = client.download(1234567890123L);

        assertThat(result.parsed().cues()).hasSize(2);
        server.verify();
    }

    @Test
    void theDownloadReturnsTheParsedFileAndWhatOpenSubtitlesSaidAboutTheAllowance() {
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);

        OpenSubtitlesDownload result = client.download(7);

        assertThat(result.parsed().cues()).containsExactly(new gregochr.cinefilm.subtitle.SubtitleCue(10_000, 12_000,
            "Hello"), new gregochr.cinefilm.subtitle.SubtitleCue(20_000, 22_500, "World"));
        assertThat(result.parsed().report().encoding()).isEqualTo("UTF-8");
        assertThat(result.remaining()).isEqualTo(87);
        assertThat(result.resetTimeUtc()).isEqualTo(Instant.parse("2026-11-13T00:00:00Z"));
        server.verify();
    }

    @Test
    void theFileIsReadWithTheSrtCharsetRulesAWindows1252FileIsRead() {
        expectLogin();
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(
            withSuccess("1\n00:00:01,000 --> 00:00:02,000\ncafé\n\n".getBytes(java.nio.charset.Charset.forName(
                "windows-1252")), MediaType.TEXT_PLAIN));

        OpenSubtitlesDownload result = client.download(7);

        assertThat(result.parsed().cues()).extracting(c -> c.text()).containsExactly("café");
        assertThat(result.parsed().report().encoding()).isEqualTo("Windows-1252");
    }

    @Test
    void anAllowanceOrResetTimeThatIsMissingOrUnreadableIsNull() {
        expectLogin();
        expectDownload("{\"link\":\"" + LINK + "\",\"remaining\":\"many\",\"reset_time_utc\":\"tomorrow\"}");
        expectFile(SRT);
        expectDownload("{\"link\":\"" + LINK + "\"}");
        expectFile(SRT);
        expectDownload("{\"link\":\"" + LINK + "\",\"remaining\":5000000000,\"reset_time_utc\":7}");
        expectFile(SRT);

        OpenSubtitlesDownload unreadable = client.download(7);
        OpenSubtitlesDownload missing = client.download(7);
        OpenSubtitlesDownload huge = client.download(7);

        assertThat(unreadable.remaining()).isNull();
        assertThat(unreadable.resetTimeUtc()).isNull();
        assertThat(missing.remaining()).isNull();
        assertThat(missing.resetTimeUtc()).isNull();
        assertThat(huge.remaining()).isEqualTo(Integer.MAX_VALUE);
        assertThat(huge.resetTimeUtc()).isNull();
    }

    @Test
    void theTokenIsKeptInMemoryAndOnlyOneLoginIsMadeForManyDownloads() {
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);
        expectDownload(download(LINK));
        expectFile(SRT);
        expectDownload(download(LINK));
        expectFile(SRT);

        client.download(1);
        client.download(2);
        client.download(3);

        server.verify();
    }

    @Test
    void aRefusedTokenIsRenewedWithOneLoginAndTheDownloadRepeated() {
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);
        server.expect(once(), requestTo(DOWNLOAD_URL)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess(login("new.token.value", null), json()));
        server.expect(once(), requestTo(DOWNLOAD_URL))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new.token.value"))
            .andRespond(withSuccess(download(LINK), json()));
        expectFile(SRT);

        client.download(1);
        OpenSubtitlesDownload second = client.download(2);

        assertThat(second.parsed().cues()).hasSize(2);
        server.verify();
    }

    @Test
    void aSecondRefusalAfterTheRenewalIsAnOutageAndThereIsNoThirdLogin() {
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess(login("new.token.value", null), json()));
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        client.download(1);

        assertThatThrownBy(() -> client.download(2)).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .isNotInstanceOf(OpenSubtitlesQuotaException.class).hasMessage("OpenSubtitles rejected the session");

        server.verify();
    }

    @Test
    void afterARefusedSessionTheNextDownloadLogsInAgain() {
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess(login("new.token.value", null), json()));
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);
        client.download(1);
        assertThatThrownBy(() -> client.download(2)).isInstanceOf(OpenSubtitlesUnavailableException.class);

        assertThat(client.download(3).parsed().cues()).hasSize(2);

        server.verify();
    }

    @Test
    void aRefusalRightAfterAFreshLoginIsNotRepeatedWithAnotherLogin() {
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.download(1)).isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles rejected the session");

        server.verify();
    }

    @Test
    void theAccountsOwnApiHostIsUsedForDownloadsOnlyWhenItIsAHostBelowOpenSubtitlesCom() {
        server.expect(once(), requestTo(LOGIN_URL))
            .andRespond(withSuccess(login(TOKEN, "vip-api.opensubtitles.com"), json()));
        server.expect(once(), requestTo("https://vip-api.opensubtitles.com/api/v1/download"))
            .andRespond(withSuccess(download(LINK), json()));
        expectFile(SRT);

        client.download(1);

        server.verify();
    }

    @Test
    void anyOtherBaseUrlIsIgnoredAndTheConfiguredHostIsUsed() {
        for (String bad : new String[] {"evil.com", "api.opensubtitles.com.evil.com", "opensubtitles.com",
            "https://vip-api.opensubtitles.com", "vip-api.opensubtitles.com/path", "vip-api.opensubtitles.com:8080",
            "user@vip-api.opensubtitles.com", ".opensubtitles.com", "evilopensubtitles.com", "", " ", "10.0.0.1"}) {
            server.reset();
            server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess(login(TOKEN, bad), json()));
            expectDownload(download(LINK));
            expectFile(SRT);

            clientWith(sleeps::add, clock, 1_000_000, 100).download(1);

            server.verify();
        }
    }

    @Test
    void aMissingOrNonTextBaseUrlIsIgnoredToo() {
        server.expect(once(), requestTo(LOGIN_URL))
            .andRespond(withSuccess("{\"token\":\"" + TOKEN + "\",\"base_url\":5}", json()));
        expectDownload(download(LINK));
        expectFile(SRT);

        client.download(1);

        server.verify();
    }

    @Test
    void aBaseUrlInUpperCaseIsComparedInLowerCase() {
        server.expect(once(), requestTo(LOGIN_URL))
            .andRespond(withSuccess(login(TOKEN, " VIP-API.OpenSubtitles.com "), json()));
        server.expect(once(), requestTo("https://vip-api.opensubtitles.com/api/v1/download"))
            .andRespond(withSuccess(download(LINK), json()));
        expectFile(SRT);

        client.download(1);

        server.verify();
    }

    @Test
    void a406IsTheQuotaWithTheResetTimeTheBodyGave() {
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE)
            .contentType(json()).body("{\"message\":\"You have downloaded your allowed 100 subtitles\","
                + "\"reset_time_utc\":\"2026-11-13T00:00:00.000Z\"}"));

        assertThatThrownBy(() -> client.download(1)).isInstanceOfSatisfying(OpenSubtitlesQuotaException.class,
            e -> assertThat(e.resetTimeUtc()).isEqualTo(Instant.parse("2026-11-13T00:00:00Z")))
            .hasMessage("OpenSubtitles download limit reached");
    }

    @Test
    void a406WithABodyThatIsNotJsonIsStillTheQuotaWithNoResetTime() {
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL))
            .andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE).body("<html>nope</html>"));
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE));

        assertThatThrownBy(() -> client.download(1)).isInstanceOfSatisfying(OpenSubtitlesQuotaException.class,
            e -> assertThat(e.resetTimeUtc()).isNull());
        assertThatThrownBy(() -> client.download(1)).isInstanceOfSatisfying(OpenSubtitlesQuotaException.class,
            e -> assertThat(e.resetTimeUtc()).isNull());
    }

    @Test
    void aQuotaIsAnOutageForAnythingThatDoesNotKnowAboutQuotas() {
        assertThat(new OpenSubtitlesQuotaException(null)).isInstanceOf(OpenSubtitlesUnavailableException.class);
    }

    @Test
    void aReplyWithNoLinkThatSaysTheLimitIsReachedIsTheQuota() {
        expectLogin();
        expectDownload("{\"message\":\"You have reached your daily download limit\","
            + "\"reset_time_utc\":\"2026-11-13T00:00:00Z\"}");
        expectDownload("{\"message\":\"Download QUOTA used\"}");

        assertThatThrownBy(() -> client.download(1)).isInstanceOfSatisfying(OpenSubtitlesQuotaException.class,
            e -> assertThat(e.resetTimeUtc()).isEqualTo(Instant.parse("2026-11-13T00:00:00Z")));
        assertThatThrownBy(() -> client.download(1)).isInstanceOf(OpenSubtitlesQuotaException.class);
    }

    @Test
    void aReplyWithNoLinkAndNoLimitMessageIsAnOutageNeverAQuotaOrAFile() {
        expectLogin();
        expectDownload("{\"message\":\"something else\"}");
        expectDownload("{\"link\":42}");
        expectDownload("{\"message\":7}");

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.download(1)).isInstanceOf(OpenSubtitlesUnavailableException.class)
                .isNotInstanceOf(OpenSubtitlesQuotaException.class)
                .hasMessage("OpenSubtitles returned an unexpected download reply");
        }
    }

    @Test
    void aDownloadReplyThatIsNotAJsonObjectIsAnOutage() {
        expectLogin();
        expectDownload("not json");
        expectDownload("[1,2]");

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles returned unreadable JSON");
        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles returned unreadable JSON");
    }

    @Test
    void otherStatusesOnTheDownloadRequestAreAnOutageNamingOnlyTheStatus() {
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "https://evil.example/x"));

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles download answered status 404");
        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles download answered status 403");
        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles download answered status 302");
    }

    @Test
    void aDownloadReplyOverTheSizeCapIsAnOutage() {
        OpenSubtitlesClient small = clientWith(sleeps::add, clock, 50, 100);
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess("{\"token\":\"a.b.c\"}", json()));
        expectDownload(download(LINK));
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE)
            .body("x".repeat(51)));

        assertThatThrownBy(() -> small.download(1)).hasMessage("OpenSubtitles answered more than 50 bytes");
        // A quota answer whose body is over the cap is still the quota, with no reset time.
        assertThatThrownBy(() -> small.download(1)).isInstanceOfSatisfying(OpenSubtitlesQuotaException.class,
            e -> assertThat(e.resetTimeUtc()).isNull());
    }

    @Test
    void theDownloadIsRetriedOn429WithRetryAfterAnd5xxThenGivenUp() {
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "7"));
        expectDownload(download(LINK));
        expectFile(SRT);
        server.expect(times(4), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.download(1).parsed().cues()).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
        sleeps.clear();
        assertThatThrownBy(() -> client.download(1))
            .hasMessage("OpenSubtitles unavailable after 4 attempts: status 502");
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void aLoginThatIsRefusedIsAnOutageThatNamesNeitherTheUserNorThePassword() {
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles rejected the login").hasNoCause();
        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles rejected the login");
        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles login answered status 400");
        server.verify();
    }

    @Test
    void aLoginIsRetriedOn5xxThenGivenUp() {
        server.expect(times(4), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.download(1))
            .hasMessage("OpenSubtitles unavailable after 4 attempts: status 503");

        server.verify();
    }

    @Test
    void aLoginReplyThatCannotBeUsedIsAnOutage() {
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess("not json", json()));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess("{\"status\":200}", json()));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess("{\"token\":12}", json()));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess("{\"token\":\"a b\"}", json()));
        server.expect(once(), requestTo(LOGIN_URL))
            .andRespond(withSuccess("{\"token\":\"abc\\r\\nX-Evil: 1\"}", json()));
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess("{\"token\":\"\"}", json()));

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles returned an unreadable login reply");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles returned an unexpected login reply");
        }
        server.verify();
    }

    @Test
    void aLoginReplyOverTheSizeCapIsAnOutage() {
        OpenSubtitlesClient small = clientWith(sleeps::add, clock, 20, 100);
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withSuccess(login(TOKEN, null), json()));

        assertThatThrownBy(() -> small.download(1)).hasMessage("OpenSubtitles answered more than 20 bytes");
    }

    @Test
    void loginsAreSpacedASecondApartEvenWhenRequestsAreSpacedLess() {
        AtomicLong still = new AtomicLong(5_000_000_000L);
        OpenSubtitlesClient paced = clientWith(sleeps::add, still::get, 1_000_000, 100);
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin();
        expectDownload(download(LINK));
        expectFile(SRT);
        assertThatThrownBy(() -> paced.download(1)).isInstanceOf(OpenSubtitlesUnavailableException.class);

        paced.download(2);

        assertThat(OpenSubtitlesClient.LOGIN_PACE).isEqualTo(Duration.ofSeconds(1));
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofMillis(250), Duration.ofMillis(500));
    }

    @Test
    void theLinkIsAGetWithNeitherTheKeyNorTheTokenNorTheCredentials() {
        expectLogin();
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION)).andExpect(headerDoesNotExist("Api-Key"))
            .andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
            .andExpect(header(HttpHeaders.USER_AGENT, "CineFilm v0.4.0"))
            .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
            .andRespond(withSuccess(SRT, MediaType.TEXT_PLAIN));

        client.download(1);

        server.verify();
    }

    @Test
    void aLinkOnAnyOtherHostOrSchemeIsRefusedAndNoRequestIsMadeToIt() {
        String[] refused = {"https://evil.com/x.srt", "http://dl.opensubtitles.com/x.srt",
            "https://opensubtitles.com/x.srt", "https://dl.opensubtitles.com.evil.com/x.srt",
            "https://evilopensubtitles.com/x.srt", "https://user@dl.opensubtitles.com/x.srt",
            "https://dl.opensubtitles.com:8443/x.srt", "ftp://dl.opensubtitles.com/x.srt",
            "//dl.opensubtitles.com/x.srt", "/x.srt", "not a url", "https://dl.opensubtitles.com/a b",
            "https://127.0.0.1/x.srt", "https://[::1]/x.srt", "https://dl.opensubtitles.com@evil.com/x.srt",
            "https://evil.com/.opensubtitles.com", "file:///etc/passwd", ""};
        expectLogin();
        for (String link : refused) {
            expectDownload("{\"link\":\"" + link + "\",\"remaining\":50}");
        }

        for (String link : refused) {
            assertThatThrownBy(() -> client.download(1)).as(link).isInstanceOf(OpenSubtitlesUnavailableException.class)
                .hasMessage("OpenSubtitles download link refused").hasNoCause();
        }

        server.verify();
    }

    @Test
    void aLinkOnAHostBelowOpenSubtitlesComIsFetchedWhateverItsCaseAndWithTheDefaultPort() {
        expectLogin();
        expectDownload(download("https://DL.OpenSubtitles.com:443/file/ABC.srt?x=1&y=2"));
        server.expect(once(), requestTo("https://DL.OpenSubtitles.com:443/file/ABC.srt?x=1&y=2"))
            .andRespond(withSuccess(SRT, MediaType.TEXT_PLAIN));
        expectDownload(download("  https://www.opensubtitles.com/download/9.srt  "));
        server.expect(once(), requestTo("https://www.opensubtitles.com/download/9.srt"))
            .andRespond(withSuccess(SRT, MediaType.TEXT_PLAIN));

        assertThat(client.download(1).parsed().cues()).hasSize(2);
        assertThat(client.download(2).parsed().cues()).hasSize(2);

        server.verify();
    }

    @Test
    void aRedirectFromTheLinkIsAnOutageAndIsNotFollowed() {
        expectLogin();
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(withStatus(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "https://evil.example/steal"));
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
            .header(HttpHeaders.LOCATION, "https://dl.opensubtitles.com/other"));

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles file answered status 302");
        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles file answered status 301");
        server.verify();
    }

    @Test
    void otherStatusesFromTheLinkAreAnOutage() {
        expectLogin();
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles file answered status 404");
    }

    @Test
    void theLinkIsRetriedOn5xxAndOnATransportFailureThenGivenUp() {
        expectLogin();
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(LINK)).andRespond(withException(new IOException("link " + LINK + KEY)));
        expectFile(SRT);
        expectDownload(download(LINK));
        server.expect(times(4), requestTo(LINK)).andRespond(withException(new IOException("boom")));

        assertThat(client.download(1).parsed().cues()).hasSize(2);
        assertThatThrownBy(() -> client.download(1))
            .hasMessage("OpenSubtitles unavailable after 4 attempts: no response").hasNoCause();
        server.verify();
    }

    @Test
    void aFileOverTheSizeCapIsAnOutageAndOneExactlyAtItIsRead() {
        String exactly = SRT + " ".repeat(100 - SRT.length());
        expectLogin();
        expectDownload(download(LINK));
        expectFile(exactly);
        expectDownload(download(LINK));
        expectFile(exactly + " ");

        assertThat(exactly).hasSize(100);
        assertThat(client.download(1).parsed().cues()).hasSize(2);
        assertThatThrownBy(() -> client.download(2))
            .hasMessage("OpenSubtitles file is larger than 100 bytes");
    }

    @Test
    void twoMegabytesIsTheDefaultFileCap() {
        assertThat(OpenSubtitlesClient.MAX_FILE_BYTES).isEqualTo(2 * 1024 * 1024);
    }

    /** An endless stream of text: reading it all would never finish, so only a cap on the reading can end it. */
    private static final class Endless extends InputStream {

        private long served;

        @Override
        public int read() {
            served++;
            return 'a';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            served += length;
            java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'a');
            return length;
        }
    }

    @Test
    void theSizeCapIsEnforcedWhileReadingSoAnEndlessFileIsStoppedJustPastTheCap() {
        Endless endless = new Endless();
        expectLogin();
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(request ->
            new MockClientHttpResponse(endless, HttpStatus.OK));

        assertThatThrownBy(() -> client.download(1)).hasMessage("OpenSubtitles file is larger than 100 bytes");

        // At most the cap and one byte more is asked of the wire (the read is in blocks, never the whole body).
        assertThat(endless.served).isBetween(101L, 8_192L);
    }

    @Test
    void anEmptyFileIsReadAsAFileWithNoCuesNotAsAnOutage() {
        expectLogin();
        expectDownload(download(LINK));
        expectFile("");

        OpenSubtitlesDownload result = client.download(1);

        assertThat(result.parsed().cues()).isEmpty();
    }

    @Test
    void downloadsAreOffWithoutAUsernameOrPasswordAndNoRequestIsMadeButSearchStillWorks() {
        for (String[] pair : new String[][] {{"", PASSWORD}, {USER, ""}, {"  ", PASSWORD}, {USER, null},
            {null, PASSWORD}}) {
            OpenSubtitlesClient off = new OpenSubtitlesClient(builder.build(), KEY, pair[0], pair[1], "0.4.0",
                sleeps::add, clock, 1_000_000, 100);

            assertThat(off.downloadsEnabled()).isFalse();
            assertThat(off.enabled()).isTrue();
            assertThatThrownBy(() -> off.download(1)).isInstanceOf(OpenSubtitlesUnavailableException.class)
                .hasMessage("OpenSubtitles downloads are off: no login is configured");
        }
        OpenSubtitlesClient noKey = new OpenSubtitlesClient(builder.build(), "", USER, PASSWORD, "0.4.0",
            sleeps::add, clock, 1_000_000, 100);
        assertThat(noKey.downloadsEnabled()).isFalse();
        assertThat(client.downloadsEnabled()).isTrue();
        server.verify();
    }

    @Test
    void aFileIdThatIsNotPositiveIsRejectedWithoutAnyRequest() {
        assertThatThrownBy(() -> client.download(0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Not a file id");
        assertThatThrownBy(() -> client.download(-5)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void theUsernamePasswordTokenAndKeyNeverAppearInAMessageACauseOrALogLine() {
        List<Throwable> failures = new ArrayList<>();
        // refused login, refused session, link refused, 5xx everywhere, transport failures, quota
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(times(4), requestTo(LOGIN_URL)).andRespond(withException(new IOException(PASSWORD + KEY)));
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin();
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE));
        expectDownload(download("https://evil.com/" + TOKEN));
        expectDownload(download(LINK));
        server.expect(times(4), requestTo(LINK)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        for (int i = 0; i < 6; i++) {
            try {
                client.download(1);
            } catch (OpenSubtitlesUnavailableException e) {
                failures.add(e);
            }
        }

        assertThat(failures).hasSize(6);
        assertThat(failures).allSatisfy(e -> {
            assertThat(e.getMessage()).doesNotContain(USER).doesNotContain(PASSWORD).doesNotContain(TOKEN)
                .doesNotContain(KEY).doesNotContain("opensubtitles.com").doesNotContain("evil.com");
            assertThat(e.getCause()).isNull();
        });
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage).isNotEmpty().allSatisfy(message ->
            assertThat(message).doesNotContain(USER).doesNotContain(PASSWORD).doesNotContain(TOKEN)
                .doesNotContain(KEY).doesNotContain("opensubtitles.com"));
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private static void assertCharged(Throwable thrown, boolean permanent) {
        assertThat(thrown).isInstanceOfSatisfying(OpenSubtitlesChargedException.class,
            e -> assertThat(e.permanent()).isEqualTo(permanent));
    }

    @Test
    void everyFailureAfterTheLinkWasIssuedIsAChargedOneAndPermanentOnlyWhenRetryingCouldNeverHelp() {
        expectLogin();
        expectDownload(download("https://evil.com/x.srt"));
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(withStatus(HttpStatus.FOUND));
        expectDownload(download(LINK));
        server.expect(once(), requestTo(LINK)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        expectDownload(download(LINK));
        server.expect(times(4), requestTo(LINK)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectDownload(download(LINK));
        server.expect(times(4), requestTo(LINK)).andRespond(withException(new IOException("boom")));
        expectDownload(download(LINK));
        expectFile("x".repeat(101));

        // link refused, too big: never worth another try
        assertCharged(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)), true);
        // a redirect, a 404, retries that ran out, a transport failure: worth trying again later
        assertCharged(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)), false);
        assertCharged(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)), false);
        assertCharged(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)), false);
        assertCharged(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)), false);
        assertCharged(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)), true);
    }

    @Test
    void aFailureBeforeOrDuringTheDownloadRequestIsNeverAChargedOne() {
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectLogin();
        server.expect(times(4), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        expectDownload("{\"message\":\"something else\"}");
        expectDownload("not json");
        server.expect(once(), requestTo(DOWNLOAD_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        for (int i = 0; i < 5; i++) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> client.download(1)))
                .isInstanceOf(OpenSubtitlesUnavailableException.class)
                .isNotInstanceOf(OpenSubtitlesChargedException.class);
        }
    }
}
