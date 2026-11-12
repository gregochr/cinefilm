package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The OpenSubtitles HTTP client and executor wiring, against a loopback server (no real network). */
class OpenSubtitlesConfigTest {

    private final OpenSubtitlesConfig config = new OpenSubtitlesConfig();

    private static HttpServer server(AtomicReference<String> received, AtomicInteger secondHits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestURI().toString());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            secondHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void theRestClientSendsRequestsToTheConfiguredBaseUrl() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = server(received, new AtomicInteger());
        try {
            RestClient client = config.openSubtitlesRestClient("http://127.0.0.1:" + server.getAddress().getPort());

            String body = client.get().uri("/api/v1/subtitles?imdb_id=1").retrieve().body(String.class);

            assertThat(body).isEqualTo("ok");
            assertThat(received.get()).isEqualTo("/api/v1/subtitles?imdb_id=1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theRestClientDoesNotFollowARedirect() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(), followed);
        try {
            RestClient client = config.openSubtitlesRestClient("http://127.0.0.1:" + server.getAddress().getPort());

            Integer status = client.get().uri("/moved").exchange((request, response) ->
                response.getStatusCode().value());

            assertThat(status).isEqualTo(302);
            assertThat(followed).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theTimeoutIsTenSeconds() {
        assertThat(OpenSubtitlesConfig.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void searchesRunOnOneNamedDaemonThread() throws Exception {
        Executor executor = config.openSubtitlesExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get().getName()).isEqualTo("opensubtitles-search");
        assertThat(ran.get().isDaemon()).isTrue();
    }
}
