package gregochr.cinefilm.opensubtitles;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wiring for the OpenSubtitles search: the HTTP client (no redirects, timeouts) and one background thread. */
@Configuration
public class OpenSubtitlesConfig {

    /** How long to wait to connect, and for the answer. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * The RestClient for OpenSubtitles. Redirects are never followed (a 3xx is an error), and the API key is added per
     * request by {@link OpenSubtitlesClient}, never here.
     *
     * @param baseUrl OpenSubtitles' origin ({@code cinefilm.opensubtitles.base-url})
     * @return the client
     */
    @Bean
    public RestClient openSubtitlesRestClient(@Value("${cinefilm.opensubtitles.base-url}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** A single daemon thread: only one OpenSubtitles search run ever goes at a time. */
    @Bean
    public Executor openSubtitlesExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "opensubtitles-search");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A single daemon thread: only one check of the owner's subtitle files against OpenSubtitles runs at a time. */
    @Bean
    public Executor sidecarCheckExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subtitle-check");
            thread.setDaemon(true);
            return thread;
        });
    }
}
