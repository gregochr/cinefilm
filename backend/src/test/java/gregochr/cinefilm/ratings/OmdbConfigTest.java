package gregochr.cinefilm.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OmdbConfigTest {

    private final OmdbConfig config = new OmdbConfig();

    @Test
    void theRestClientIsBuiltForTheConfiguredBaseUrl() {
        assertThat(config.omdbRestClient("https://www.omdbapi.com")).isNotNull();
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
