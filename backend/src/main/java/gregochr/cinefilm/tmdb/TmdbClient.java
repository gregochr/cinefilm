package gregochr.cinefilm.tmdb;

import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionRef;
import gregochr.cinefilm.tmdb.TmdbModels.Credit;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.Part;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TMDB v3 over HTTP with the owner's Read Access Token as {@code Authorization: Bearer} (never in a URL, never
 * logged). Paced to 20 requests per second, retries 429 and 5xx with backoff, and caches films and collections by id.
 */
@Component
public class TmdbClient implements TmdbApi {

    private static final Logger LOG = LoggerFactory.getLogger(TmdbClient.class);
    static final int MAX_ATTEMPTS = 4;
    static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final int TOP_CAST = 10;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final String token;
    private final Sleeper sleeper;
    private final RequestPacer pacer;
    private final Map<Long, Optional<MovieDetails>> movies = new ConcurrentHashMap<>();
    private final Map<Long, Optional<CollectionDetails>> collections = new ConcurrentHashMap<>();

    /** Creates the client for the TMDB RestClient, the read token ({@code TMDB_READ_TOKEN}) and a sleeper. */
    @Autowired
    public TmdbClient(@Qualifier("tmdbRestClient") RestClient client,
                      @Value("${cinefilm.tmdb.token:}") String token, Sleeper sleeper) {
        this(client, token, sleeper, System::nanoTime);
    }

    TmdbClient(RestClient client, String token, Sleeper sleeper, java.util.function.LongSupplier nanoClock) {
        this.client = client;
        this.token = token == null ? "" : token.trim();
        this.sleeper = sleeper;
        this.pacer = new RequestPacer(nanoClock, sleeper);
    }

    @Override
    public boolean enabled() {
        return !token.isEmpty();
    }

    @Override
    public List<SearchHit> search(String title, Integer year) {
        JsonNode body = get(b -> {
            UriBuilder builder = b.path("/3/search/movie").queryParam("query", "{q}")
                .queryParam("include_adult", "false");
            if (year != null) {
                builder.queryParam("year", year);
            }
            return builder.build(title);
        }).orElse(null);
        List<SearchHit> hits = new ArrayList<>();
        if (body != null) {
            for (JsonNode r : body.path("results")) {
                hits.add(new SearchHit(r.path("id").asLong(), text(r, "title"), text(r, "original_title"),
                    year(r), r.path("popularity").asDouble(0), text(r, "poster_path")));
            }
        }
        return hits;
    }

    @Override
    public Optional<MovieDetails> movie(long id) {
        Optional<MovieDetails> cached = movies.get(id);
        if (cached != null) {
            return cached;
        }
        Optional<MovieDetails> fetched = get(b -> b.path("/3/movie/{id}")
            .queryParam("append_to_response", "credits").build(id)).map(TmdbClient::movieOf);
        movies.put(id, fetched);
        return fetched;
    }

    @Override
    public Optional<CollectionDetails> collection(long id) {
        Optional<CollectionDetails> cached = collections.get(id);
        if (cached != null) {
            return cached;
        }
        Optional<CollectionDetails> fetched = get(b -> b.path("/3/collection/{id}").build(id))
            .map(TmdbClient::collectionOf);
        collections.put(id, fetched);
        return fetched;
    }

    private static MovieDetails movieOf(JsonNode m) {
        List<String> genres = new ArrayList<>();
        for (JsonNode g : m.path("genres")) {
            genres.add(text(g, "name"));
        }
        List<Credit> directors = new ArrayList<>();
        for (JsonNode c : m.path("credits").path("crew")) {
            if ("Director".equals(text(c, "job"))) {
                directors.add(credit(c, null));
            }
        }
        List<Credit> cast = new ArrayList<>();
        for (JsonNode c : m.path("credits").path("cast")) {
            if (cast.size() < TOP_CAST) {
                cast.add(credit(c, text(c, "character")));
            }
        }
        JsonNode belongs = m.path("belongs_to_collection");
        CollectionRef collection = belongs.isObject()
            ? new CollectionRef(belongs.path("id").asLong(), text(belongs, "name"), text(belongs, "poster_path"))
            : null;
        int runtime = m.path("runtime").asInt(0);
        return new MovieDetails(m.path("id").asLong(), text(m, "title"), text(m, "original_title"), year(m),
            text(m, "overview"), runtime > 0 ? runtime : null, List.copyOf(genres), text(m, "poster_path"),
            text(m, "backdrop_path"), m.path("popularity").asDouble(0), List.copyOf(directors),
            List.copyOf(cast), collection);
    }

    private static Credit credit(JsonNode c, String character) {
        return new Credit(c.path("id").asLong(), text(c, "name"), text(c, "profile_path"), character);
    }

    /** A part with the full release date it is ordered by (the model keeps only the year). */
    private record Dated(String releaseDate, Part part) {
    }

    private static CollectionDetails collectionOf(JsonNode c) {
        List<Dated> dated = new ArrayList<>();
        for (JsonNode p : c.path("parts")) {
            dated.add(new Dated(text(p, "release_date"),
                new Part(p.path("id").asLong(), text(p, "title"), year(p), text(p, "poster_path"))));
        }
        dated.sort(Comparator.comparing(Dated::releaseDate, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparingLong(d -> d.part().id()));
        return new CollectionDetails(c.path("id").asLong(), text(c, "name"), text(c, "poster_path"),
            dated.stream().map(Dated::part).toList());
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString()) {
            return null;
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    private static Integer year(JsonNode node) {
        String date = text(node, "release_date");
        if (date == null || date.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One HTTP answer. */
    private record Answer(int status, String retryAfter, String body) {
    }

    /**
     * GET with pacing, retry and backoff. Empty means TMDB answered 404.
     */
    private Optional<JsonNode> get(Function<UriBuilder, URI> uri) {
        if (!enabled()) {
            throw new TmdbUnavailableException("No TMDB read token is configured", null);
        }
        for (int attempt = 1; ; attempt++) {
            Answer answer = attempt(uri);
            if (answer != null && answer.status() / 100 == 2) {
                return parse(answer.body());
            }
            if (answer != null && answer.status() == 404) {
                return Optional.empty();
            }
            boolean retryable = answer == null || answer.status() == 429 || answer.status() >= 500;
            String what = answer == null ? "no response" : "status " + answer.status();
            if (!retryable) {
                throw new TmdbUnavailableException("TMDB answered " + what, null);
            }
            if (attempt == MAX_ATTEMPTS) {
                throw new TmdbUnavailableException("TMDB unavailable after " + MAX_ATTEMPTS + " attempts: " + what,
                    null);
            }
            LOG.warn("TMDB {} (attempt {} of {}), retrying", what, attempt, MAX_ATTEMPTS);
            pause(backoff(attempt, answer));
        }
    }

    private Answer attempt(Function<UriBuilder, URI> uri) {
        try {
            pacer.await();
            return client.get().uri(b -> uri.apply(b)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Answer(response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmdbUnavailableException("Interrupted while waiting for TMDB", e);
        }
    }

    private static Duration backoff(int attempt, Answer answer) {
        Duration exponential = BASE_BACKOFF.multipliedBy(1L << (attempt - 1));
        if (answer != null && answer.status() == 429 && answer.retryAfter() != null) {
            try {
                long asked = Long.parseLong(answer.retryAfter().trim());
                return Duration.ofSeconds(Math.min(asked, MAX_RETRY_AFTER.toSeconds()));
            } catch (NumberFormatException e) {
                return exponential;
            }
        }
        return exponential;
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmdbUnavailableException("Interrupted while waiting to retry TMDB", e);
        }
    }

    private static Optional<JsonNode> parse(String body) {
        try {
            return Optional.of(JSON.readTree(body));
        } catch (JacksonException e) {
            throw new TmdbUnavailableException("TMDB returned unreadable JSON", e);
        }
    }
}
