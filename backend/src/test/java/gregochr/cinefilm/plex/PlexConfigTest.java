package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlexConfigTest {

    @Test
    void theHttpClientNeverFollowsRedirectsAndConnectsWithinTenSeconds() {
        HttpClient client = PlexConfig.httpClient();

        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10));
        assertThat(PlexConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theRestClientIsBuilt() {
        assertThat(new PlexConfig().plexRestClient("http://plex.test:32400 ")).isNotNull();
        assertThat(new PlexConfig().plexRestClient("")).isNotNull();
    }

    @Test
    void importsRunOnOneNamedDaemonThread() throws Exception {
        Executor executor = new PlexConfig().plexImportExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get().getName()).isEqualTo("plex-import");
        assertThat(ran.get().isDaemon()).isTrue();
    }

    @Test
    void creditsImportsRunOnTheirOwnNamedDaemonThread() throws Exception {
        Executor executor = new PlexConfig().plexCreditsExecutor();
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get().getName()).isEqualTo("plex-credits-import");
        assertThat(ran.get().isDaemon()).isTrue();
    }
}
