package gregochr.cinefilm.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OmdbConfigTest {

    private final OmdbConfig config = new OmdbConfig();

    @Test
    void theRestClientSendsRequestsToTheConfiguredBaseUrl() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestURI().toString());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            RestClient client = config.omdbRestClient("http://127.0.0.1:" + server.getAddress().getPort());

            String body = client.get().uri("/?i=tt1").retrieve().body(String.class);

            assertThat(body).isEqualTo("ok");
            assertThat(received.get()).isEqualTo("/?i=tt1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshesRunOnOneNamedDaemonThread() throws Exception {
        Executor executor = config.ratingsExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get().getName()).isEqualTo("ratings-refresh");
        assertThat(ran.get().isDaemon()).isTrue();
    }
}
