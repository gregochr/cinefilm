package gregochr.cinefilm.ratings;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OMDb over HTTP. OMDb takes the API key as a query parameter, so the request URL is secret: it is never logged, and
 * a transport failure is reported without its cause (whose message would contain the URL).
 */
@Component
public class OmdbClient implements OmdbApi {

    private static final int UNAUTHORIZED = 401;
    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient client;
    private final String key;

    /** Creates the client for the OMDb RestClient and the API key ({@code OMDB_API_KEY}). */
    public OmdbClient(@Qualifier("omdbRestClient") RestClient client,
                      @Value("${cinefilm.omdb.api-key:}") String key) {
        this.client = client;
        this.key = key == null ? "" : key.trim();
    }

    @Override
    public boolean enabled() {
        return !key.isEmpty();
    }

    @Override
    public OmdbResult lookup(String imdbId) {
        if (!enabled()) {
            throw new OmdbUnavailableException("No OMDb API key is configured");
        }
        Answer answer;
        try {
            answer = client.get()
                .uri(b -> b.path("/").queryParam("i", "{i}").queryParam("apikey", "{k}")
                    .queryParam("tomatoes", "true").build(imdbId, key))
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Answer(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            throw new OmdbUnavailableException("OMDb could not be reached");
        }
        if (answer.status() == UNAUTHORIZED || answer.status() == TOO_MANY_REQUESTS) {
            return OmdbResult.limitReached();
        }
        if (answer.status() / 100 != 2) {
            throw new OmdbUnavailableException("OMDb answered status " + answer.status());
        }
        return OmdbParser.parse(answer.body());
    }

    /** One HTTP answer. */
    private record Answer(int status, String body) {
    }
}
