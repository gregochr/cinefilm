package gregochr.cinefilm.tmdb;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * A TMDB stand-in that answers from the responses recorded from the live API in {@code src/test/resources/tmdb}
 * (searches trimmed to eight results, credits to the director and twelve cast). A search that was not recorded
 * answers with no results; an unrecorded film or collection is a 404. Records every request so a test can see
 * exactly which searches and lookups were made.
 */
final class TmdbFixtures {

    static final String TOKEN = "test-read-access-token";

    private static final Pattern MOVIE = Pattern.compile("/3/movie/(\\d+)");
    private static final Pattern COLLECTION = Pattern.compile("/3/collection/(\\d+)");

    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    /** Every request made, as {@code path?query} with the query decoded. */
    List<String> requests() {
        return List.copyOf(requests);
    }

    /** Only the search requests, as {@code query[ (year)]}. */
    List<String> searches() {
        return requests().stream().filter(r -> r.startsWith("/3/search/movie"))
            .map(r -> r.replace("/3/search/movie?query=", "").replace("&include_adult=false", "")
                .replace("&year=", " ")).toList();
    }

    /** A TMDB client wired to these fixtures with a real (instant) clock and a recording sleeper. */
    TmdbClient client() {
        return new TmdbClient(restClient(), TOKEN, duration -> { }, System::nanoTime);
    }

    /** A RestClient answering from the fixtures. */
    RestClient restClient() {
        ClientHttpRequestFactory factory = (uri, method) -> new MockClientHttpRequest(method, uri) {
            @Override
            protected ClientHttpResponse executeInternal() {
                return answer(method, getURI());
            }
        };
        return RestClient.builder().baseUrl("https://api.themoviedb.org").requestFactory(factory).build();
    }

    private ClientHttpResponse answer(HttpMethod method, URI uri) {
        String query = uri.getRawQuery() == null ? "" : URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
        requests.add(uri.getPath() + (query.isEmpty() ? "" : "?" + query));
        String path = uri.getPath();
        if (path.equals("/3/search/movie")) {
            return json(load(searchName(query)), "{\"page\":1,\"results\":[],\"total_results\":0}");
        }
        Matcher movie = MOVIE.matcher(path);
        if (movie.matches()) {
            return json(load("movie-" + movie.group(1) + ".json"), null);
        }
        Matcher collection = COLLECTION.matcher(path);
        if (collection.matches()) {
            return json(load("collection-" + collection.group(1) + ".json"), null);
        }
        return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
    }

    private static String searchName(String query) {
        String title = null;
        String year = null;
        for (String pair : query.split("&")) {
            if (pair.startsWith("query=")) {
                title = pair.substring("query=".length());
            } else if (pair.startsWith("year=")) {
                year = pair.substring("year=".length());
            }
        }
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return "search-" + slug + (year == null ? "" : "-" + year) + ".json";
    }

    private static String load(String name) {
        try (InputStream in = TmdbFixtures.class.getResourceAsStream("/tmdb/" + name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ClientHttpResponse json(String body, String whenMissing) {
        String text = body != null ? body : whenMissing;
        if (text == null) {
            return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
        }
        MockClientHttpResponse response = new MockClientHttpResponse(text.getBytes(StandardCharsets.UTF_8),
            HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    /** The text of a recorded response, for tests that serve it through MockRestServiceServer. */
    static String recorded(String name) {
        String text = load(name);
        if (text == null) {
            throw new IllegalArgumentException("No recorded response " + name);
        }
        return text;
    }
}
