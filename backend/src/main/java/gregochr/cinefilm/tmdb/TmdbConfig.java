package gregochr.cinefilm.tmdb;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wiring for the TMDB client: the HTTP client with timeouts, and the real sleeper. */
@Configuration
public class TmdbConfig {

    /**
     * The RestClient for TMDB's API. The token is added per request by {@link TmdbClient}, never here.
     *
     * @param baseUrl TMDB's API origin ({@code cinefilm.tmdb.base-url})
     * @return the client
     */
    @Bean
    public RestClient tmdbRestClient(@Value("${cinefilm.tmdb.base-url}") String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** The real sleeper. */
    @Bean
    public Sleeper sleeper() {
        return Sleeper.THREAD;
    }
}
