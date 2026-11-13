package gregochr.cinefilm.plex;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wiring for the Plex import: an HTTP client that follows no redirects, and one background thread for imports. */
@Configuration
public class PlexConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /** The JDK client: connect timeout, and redirects are never followed (Plex only ever answers directly). */
    static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * The RestClient for Plex. The token is added per request by {@link PlexClient}, never here, and the base URL is
     * the only host it can reach.
     *
     * @param baseUrl Plex's address ({@code cinefilm.plex.url}, blank when the import is off)
     * @return the client
     */
    @Bean
    public RestClient plexRestClient(@Value("${cinefilm.plex.url:}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl.strip()).requestFactory(factory).build();
    }

    /** A single daemon thread for the credits import, apart from the Plex import's so neither waits for the other. */
    @Bean
    public Executor plexCreditsExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "plex-credits-import");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A single daemon thread: only one import ever runs at a time. */
    @Bean
    public Executor plexImportExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "plex-import");
            thread.setDaemon(true);
            return thread;
        });
    }
}
