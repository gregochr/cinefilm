package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The downloads a day the account allows, read from the login reply: a whole number from 1 to 10000 is taken, anything
 * else is unknown and never fails the login; the login is made once and kept, and a failed login is "unknown".
 */
class OpenSubtitlesClientAllowanceTest {

    private static final String LOGIN_URL = "https://api.opensubtitles.com/api/v1/login";
    private static final String TOKEN = "header.payload-secret.signature";

    private final AtomicLong ticks = new AtomicLong();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.opensubtitles.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenSubtitlesClient client = new OpenSubtitlesClient(builder.build(), "key", "owner", "pa55",
        "0.4.0", duration -> { }, () -> ticks.addAndGet(1_000_000_000L), 1_000_000, 100);
    private final Logger clientLog = (Logger) LoggerFactory.getLogger(OpenSubtitlesClient.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logged.start();
        clientLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        clientLog.detachAppender(logged);
    }

    private void loginAnswers(String userJson) {
        String user = userJson == null ? "" : "\"user\":" + userJson + ",";
        server.expect(once(), requestTo(LOGIN_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{" + user + "\"base_url\":\"api.opensubtitles.com\",\"token\":\"" + TOKEN
                + "\",\"status\":200}", MediaType.APPLICATION_JSON));
    }

    @Test
    void aFreeAccountsAllowanceOfTwentyIsReadFromTheLoginReplyAndLoggedOnce() {
        loginAnswers("{\"allowed_downloads\":20,\"level\":\"Sub leecher\",\"vip\":false}");

        assertThat(client.allowedDownloads()).isEqualTo(OptionalInt.of(20));
        assertThat(client.allowedDownloads()).isEqualTo(OptionalInt.of(20));

        server.verify();
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("OpenSubtitles allows 20 downloads a day for this account");
    }

    @Test
    void theBoundariesOfOneAndTenThousandAreTaken() {
        loginAnswers("{\"allowed_downloads\":1}");
        assertThat(client.allowedDownloads()).isEqualTo(OptionalInt.of(1));
        server.verify();
    }

    @Test
    void tenThousandIsTheLargestAllowanceBelieved() {
        loginAnswers("{\"allowed_downloads\":10000}");
        assertThat(client.allowedDownloads()).isEqualTo(OptionalInt.of(10_000));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"allowed_downloads\":0}", "{\"allowed_downloads\":-5}",
        "{\"allowed_downloads\":\"20\"}", "{\"allowed_downloads\":10001}", "{\"allowed_downloads\":99999999999}",
        "{\"allowed_downloads\":20.5}", "{\"allowed_downloads\":null}", "{\"allowed_downloads\":true}",
        "\"not an object\"", "null"})
    void anythingElseIsUnknownAndTheLoginStillWorks(String user) {
        loginAnswers(user);

        assertThat(client.allowedDownloads()).isEmpty();

        // The login was made and its token kept: asking again makes no second login (the server expects one).
        assertThat(client.allowedDownloads()).isEmpty();
        server.verify();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void aLoginReplyWithNoUserObjectIsUnknown() {
        loginAnswers(null);

        assertThat(client.allowedDownloads()).isEmpty();
        server.verify();
    }

    @Test
    void aFailedLoginIsUnknownAndNeverThrows() {
        server.expect(once(), requestTo(LOGIN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.allowedDownloads()).isEmpty();
        server.verify();
    }

    @Test
    void withNoUsernameThereIsNoLoginAndNoAllowance() {
        OpenSubtitlesClient off = new OpenSubtitlesClient(builder.build(), "key", "", "", "0.4.0", duration -> { },
            () -> ticks.addAndGet(1_000_000_000L), 1_000_000, 100);

        assertThat(off.allowedDownloads()).isEmpty();
        server.verify();
    }
}
