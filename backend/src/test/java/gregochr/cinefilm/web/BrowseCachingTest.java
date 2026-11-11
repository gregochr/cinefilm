package gregochr.cinefilm.web;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Talks to a real Tomcat to check what MockMvc cannot show: a browse answer over the 2 KB compression minimum is
 * gzipped and still carries its weak ETag, and a repeat with that tag is a 304 with no body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrowseCachingTest {

    private static final int FILMS = 40;

    @LocalServerPort
    private int port;

    @Autowired
    private FilmRepository films;

    // Does not decompress for us: Content-Encoding and the compressed bytes are what is under test.
    private final HttpClient client = HttpClient.newHttpClient();
    private String session;

    @BeforeEach
    void signedInWithAFullLibrary() throws Exception {
        for (int i = 1; i <= FILMS; i++) {
            Film film = film(null, "A deliberately long film title number " + i, 1950 + i,
                NOW.minus(Duration.ofDays(i)), "Drama");
            edition(film, null, "Theatrical Cut", 100 * MIN, null);
            films.save(film);
        }
        HttpResponse<String> signIn = client.send(HttpRequest.newBuilder(url("/api/session"))
            .header("Content-Type", "application/json")
            .header("X-CineFilm", "1")
            .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"sesame\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(signIn.statusCode()).isEqualTo(200);
        String cookie = signIn.headers().firstValue("Set-Cookie").orElseThrow();
        session = cookie.substring(0, cookie.indexOf(';'));
    }

    @AfterEach
    void emptyLibrary() {
        films.deleteAll();
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<byte[]> getFilms(String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url("/api/films"))
            .header("Cookie", session)
            .header("Accept-Encoding", "gzip");
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String gunzip(byte[] compressed) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aLibraryListOverTwoKilobytesIsGzippedAndCarriesItsWeakEtag() throws Exception {
        HttpResponse<byte[]> response = getFilms(null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(response.headers().firstValue("ETag").orElseThrow()).matches("W/\"[0-9a-f]{16}\"");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache, private");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        String json = gunzip(response.body());
        assertThat(json.length()).isGreaterThan(2048);
        assertThat(json).contains("\"total\":" + FILMS).doesNotContain("backdropPath");
    }

    @Test
    void aRepeatWithTheEtagIsA304WithNoBody() throws Exception {
        String etag = getFilms(null).headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> repeat = getFilms(etag);

        assertThat(repeat.statusCode()).isEqualTo(304);
        assertThat(repeat.body()).isEmpty();
        assertThat(repeat.headers().firstValue("ETag")).contains(etag);
        assertThat(repeat.headers().firstValue("Cache-Control")).contains("no-cache, private");
    }

    @Test
    void theSameDataGivesTheSameEtagWhetherOrNotTheClientAcceptsGzip() throws Exception {
        String compressed = getFilms(null).headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> plain = client.send(HttpRequest.newBuilder(url("/api/films"))
            .header("Cookie", session).build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(plain.headers().firstValue("ETag")).contains(compressed);
    }
}
