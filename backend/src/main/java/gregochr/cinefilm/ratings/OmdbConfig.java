package gregochr.cinefilm.ratings;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wiring for ratings: the OMDb HTTP client with timeouts, and one background thread for refreshes. */
@Configuration
public class OmdbConfig {

    /**
     * The RestClient for OMDb. The API key is added per request by {@link OmdbClient}, never here.
     *
     * @param baseUrl OMDb's origin ({@code cinefilm.omdb.base-url})
     * @return the client
     */
    @Bean
    public RestClient omdbRestClient(@Value("${cinefilm.omdb.base-url}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** A single daemon thread: only one ratings refresh ever runs at a time. */
    @Bean
    public Executor ratingsExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ratings-refresh");
            thread.setDaemon(true);
            return thread;
        });
    }
}
