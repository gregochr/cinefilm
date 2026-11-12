package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ForcedDownloadConfigTest {

    @Test
    void theExecutorRunsTasksOnOneDaemonThreadNamedForTheDownloads() throws Exception {
        Executor executor = new ForcedDownloadConfig().forcedDownloadExecutor();
        CompletableFuture<Thread> first = new CompletableFuture<>();
        CompletableFuture<Thread> second = new CompletableFuture<>();

        executor.execute(() -> first.complete(Thread.currentThread()));
        executor.execute(() -> second.complete(Thread.currentThread()));

        Thread thread = first.get(5, TimeUnit.SECONDS);
        assertThat(thread.getName()).isEqualTo("subtitle-download");
        assertThat(thread.isDaemon()).isTrue();
        assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(thread);
    }
}
