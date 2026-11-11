package gregochr.cinefilm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.transcode.SessionService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Compression happens in Tomcat's connector, which MockMvc bypasses, so this talks to a real server. {@code
 * java.net.http} sends no {@code Accept-Encoding} of its own and does not decompress, so each test asks for gzip and
 * unzips by hand. Text (a rung's playlist, stylesheets, scripts) is compressed; a segment is not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CompressionTest {

    private static final String PLAYLIST = "#EXTM3U\n#EXT-X-VERSION:3\n"
        + "#EXTINF:4.000,\nsegment-00001.ts\n".repeat(200) + "#EXT-X-ENDLIST\n";

    @LocalServerPort
    private int port;

    @MockitoBean
    private SessionService sessions;

    @TempDir
    private Path temp;

    @Test
    void aStylesheetUnderAssetsIsGzippedAndUnzipsToTheExactBytes() throws Exception {
        assertGzippedAsset("/assets/compress-test.css", "text/css");
    }

    @Test
    void aScriptUnderAssetsIsGzippedAndUnzipsToTheExactBytes() throws Exception {
        assertGzippedAsset("/assets/compress-test.js", "text/javascript");
    }

    @Test
    void theSameStylesheetWithoutAcceptEncodingComesBackPlain() throws Exception {
        HttpResponse<byte[]> response = fetch("/assets/compress-test.css", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(response.body()).isEqualTo(Files.readAllBytes(resource("/assets/compress-test.css")));
    }

    @Test
    void aRungsPlaylistIsGzippedAndUnzipsToTheExactText() throws Exception {
        when(sessions.media("abc", "720p")).thenReturn(PLAYLIST);

        HttpResponse<byte[]> response = fetch("/api/sessions/abc/720p/index.m3u8", "gzip", signIn());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/vnd.apple.mpegurl");
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(new String(gunzip(response.body()), StandardCharsets.UTF_8)).isEqualTo(PLAYLIST);
        assertThat(PLAYLIST.length()).isGreaterThan(2048);
    }

    @Test
    void aSegmentStaysBinaryAndUntouched() throws Exception {
        Path segment = temp.resolve("segment-00001.ts");
        byte[] bytes = new byte[8192];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 7);
        }
        Files.write(segment, bytes);
        when(sessions.segment("abc", "720p", "segment-00001.ts")).thenReturn(segment);

        HttpResponse<byte[]> response = fetch("/api/sessions/abc/720p/segment-00001.ts", "gzip", signIn());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("video/mp2t");
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(response.body()).isEqualTo(bytes);
    }

    private void assertGzippedAsset(String path, String contentType) throws Exception {
        byte[] original = Files.readAllBytes(resource(path));
        assertThat(original.length).isGreaterThan(2048);

        HttpResponse<byte[]> response = fetch(path, "gzip", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith(contentType);
        assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(response.body().length).isLessThan(original.length);
        assertThat(gunzip(response.body())).isEqualTo(original);
    }

    private static Path resource(String path) throws Exception {
        return Path.of(CompressionTest.class.getResource("/static" + path).toURI());
    }

    private HttpResponse<byte[]> fetch(String path, String acceptEncoding, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (acceptEncoding != null) {
            request.header("Accept-Encoding", acceptEncoding);
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Signs in and returns the session cookie pair (the playlist and segment routes need it). */
    private String signIn() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/api/session"))
            .header("Content-Type", "application/json")
            .header("X-CineFilm", "1")
            .POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"sesame\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return cookie.substring(0, cookie.indexOf(';'));
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }
}
