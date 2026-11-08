package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Talks to a real server to check the session cookie attributes MockMvc cannot show. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionCookieTest {

    @LocalServerPort
    private int port;

    @Test
    void signInSetsAnHttpOnlySameSiteLaxCookieThatLastsThirtyDays() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/session"))
            .header("Content-Type", "application/json")
            .header("X-CineFilm", "1")
            .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"sesame\"}")).build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        String cookie = cookies.get(0);
        assertThat(cookie).startsWith("JSESSIONID=").contains("HttpOnly").contains("SameSite=Lax")
            .contains("Max-Age=2592000").doesNotContain("Secure");

        String sessionPair = cookie.substring(0, cookie.indexOf(';'));
        HttpResponse<String> home = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/home")).header("Cookie", sessionPair).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(home.statusCode()).isEqualTo(200);
    }

    @Test
    void unauthenticatedRequestOverHttpGets401NotARedirect() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
            "http://localhost:" + port + "/api/home")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
    }
}
