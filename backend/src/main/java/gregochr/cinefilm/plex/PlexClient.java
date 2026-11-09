package gregochr.cinefilm.plex;

import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexImage;
import gregochr.cinefilm.tmdb.Sleeper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
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
 * Plex's HTTP API, read-only. Every request is a GET to the configured Plex address and nowhere else; the token goes in
 * the {@code X-Plex-Token} header and never in a URL; redirects are not followed; answers are size-capped; at most four
 * requests are in flight; 5xx answers and network errors are retried with backoff. Logs and error messages name the
 * call ("plex sections"), never an address or the token.
 */
@Component
public class PlexClient implements PlexApi {

    private static final Logger LOG = LoggerFactory.getLogger(PlexClient.class);
    static final String TOKEN_HEADER = "X-Plex-Token";
    static final String START_HEADER = "X-Plex-Container-Start";
    static final String SIZE_HEADER = "X-Plex-Container-Size";
    static final int PAGE_SIZE = 200;
    static final int MAX_IN_FLIGHT = 4;
    static final int MAX_RETRIES = 3;
    static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    static final int JSON_CAP = 50 * 1024 * 1024;
    static final int IMAGE_CAP = 10 * 1024 * 1024;
    private static final MediaType[] JSON_TYPES = {MediaType.APPLICATION_JSON};
    private static final MediaType[] IMAGE_TYPES = {MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final boolean configured;
    private final String token;
    private final Sleeper sleeper;
    private final Semaphore inFlight;
    private final int jsonCap;
    private final int imageCap;

    /** Creates the client for the Plex RestClient, the address and token from the environment, and a sleeper. */
    @Autowired
    public PlexClient(@Qualifier("plexRestClient") RestClient client, @Value("${cinefilm.plex.url:}") String url,
                      @Value("${cinefilm.plex.token:}") String token, Sleeper sleeper) {
        this(client, url, token, sleeper, MAX_IN_FLIGHT, JSON_CAP, IMAGE_CAP);
    }

    PlexClient(RestClient client, String url, String token, Sleeper sleeper, int maxInFlight, int jsonCap,
               int imageCap) {
        this.client = client;
        this.token = token == null ? "" : token.strip();
        this.configured = url != null && !url.isBlank() && !this.token.isEmpty();
        this.sleeper = sleeper;
        this.inFlight = new Semaphore(maxInFlight);
        this.jsonCap = jsonCap;
        this.imageCap = imageCap;
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public boolean reachable() {
        if (!configured) {
            return false;
        }
        try {
            fetch("plex sections", b -> b.path("/library/sections").build(), List.of(), JSON_TYPES, jsonCap, 0);
            return true;
        } catch (PlexException e) {
            return false;
        }
    }

    @Override
    public List<String> movieSections() {
        JsonNode body = json("plex sections", b -> b.path("/library/sections").build(), List.of());
        List<String> ids = new ArrayList<>();
        for (JsonNode directory : body.path("MediaContainer").path("Directory")) {
            String key = text(directory, "key");
            if ("movie".equals(text(directory, "type")) && key != null) {
                ids.add(key);
            }
        }
        return ids;
    }

    @Override
    public List<PlexFilm> films(String sectionId) {
        List<PlexFilm> films = new ArrayList<>();
        for (JsonNode item : pages("plex films", sectionId, "/library/sections/{id}/all", true)) {
            films.add(film(item));
        }
        return films;
    }

    @Override
    public List<String> collections(String sectionId) {
        List<String> titles = new ArrayList<>();
        for (JsonNode item : pages("plex collections", sectionId, "/library/sections/{id}/collections", false)) {
            String title = text(item, "title");
            if (title != null) {
                titles.add(title);
            }
        }
        return titles;
    }

    @Override
    public PlexImage image(String path, int width, int height) {
        Answer answer = fetch("plex image", b -> b.path("/photo/:/transcode").queryParam("width", width)
            .queryParam("height", height).queryParam("minSize", 1).queryParam("upscale", 0)
            .queryParam("url", "{url}").build(path), List.of(), IMAGE_TYPES, imageCap, MAX_RETRIES);
        return new PlexImage(answer.body(), answer.contentType());
    }

    /** Reads a section's list a page at a time until {@code totalSize} items have been read. */
    private List<JsonNode> pages(String call, String sectionId, String template, boolean withGuids) {
        List<JsonNode> items = new ArrayList<>();
        int start = 0;
        while (true) {
            int from = start;
            JsonNode container = json(call, b -> {
                UriBuilder builder = b.path(template);
                if (withGuids) {
                    builder.queryParam("includeGuids", 1);
                }
                return builder.build(sectionId);
            }, List.of(new Header(START_HEADER, String.valueOf(from)),
                new Header(SIZE_HEADER, String.valueOf(PAGE_SIZE)))).path("MediaContainer");
            List<JsonNode> page = new ArrayList<>();
            container.path("Metadata").forEach(page::add);
            items.addAll(page);
            start += page.size();
            long total = container.path("totalSize").asLong(-1);
            boolean more = total >= 0 ? start < total : page.size() == PAGE_SIZE;
            if (page.isEmpty() || !more) {
                return items;
            }
        }
    }

    private static PlexFilm film(JsonNode item) {
        List<String> guids = new ArrayList<>();
        for (JsonNode guid : item.path("Guid")) {
            String id = text(guid, "id");
            if (id != null) {
                guids.add(id);
            }
        }
        List<String> files = new ArrayList<>();
        for (JsonNode media : item.path("Media")) {
            for (JsonNode part : media.path("Part")) {
                String file = text(part, "file");
                if (file != null) {
                    files.add(file);
                }
            }
        }
        int year = item.path("year").asInt(0);
        return new PlexFilm(text(item, "ratingKey"), text(item, "title"), text(item, "titleSort"),
            year > 0 ? year : null, text(item, "summary"), text(item, "editionTitle"), seconds(item, "addedAt"),
            seconds(item, "updatedAt"), text(item, "thumb"), text(item, "art"), PlexRules.tmdbId(guids),
            item.path("viewCount").asInt(0), item.path("viewOffset").asLong(0), seconds(item, "lastViewedAt"),
            List.copyOf(files));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString()) {
            return null;
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    /** A Plex timestamp (epoch seconds) as an instant, or null when it is missing or not positive. */
    private static Instant seconds(JsonNode node, String name) {
        long seconds = node.path(name).asLong(0);
        return seconds > 0 ? Instant.ofEpochSecond(seconds) : null;
    }

    private JsonNode json(String call, Function<UriBuilder, URI> uri, List<Header> headers) {
        Answer answer = fetch(call, uri, headers, JSON_TYPES, jsonCap, MAX_RETRIES);
        try {
            return JSON.readTree(answer.body());
        } catch (JacksonException e) {
            throw new PlexException("Plex returned unreadable JSON for " + call, e);
        }
    }

    /** A request header besides the token and Accept. */
    private record Header(String name, String value) {
    }

    /** One successful answer. */
    private record Answer(String contentType, byte[] body) {
    }

    /** What one attempt got: a status and body, or null when the request did not get an answer. */
    private record Attempt(int status, String contentType, byte[] body) {
    }

    private Answer fetch(String call, Function<UriBuilder, URI> uri, List<Header> headers, MediaType[] accept, int cap,
                         int retries) {
        for (int attempt = 0; ; attempt++) {
            Attempt result = attempt(call, uri, headers, accept, cap);
            if (result != null && result.status() / 100 == 2) {
                return new Answer(result.contentType(), result.body());
            }
            boolean retryable = result == null || result.status() >= 500;
            String what = result == null ? "no response" : "status " + result.status();
            if (!retryable) {
                throw new PlexException("Plex answered " + what + " to " + call, null);
            }
            if (attempt >= retries) {
                throw new PlexException(result == null ? "Plex is not reachable" : "Plex answered " + what
                    + " to " + call, null);
            }
            LOG.warn("{} failed ({}), retrying ({} of {})", call, what, attempt + 1, retries);
            pause(BASE_BACKOFF.multipliedBy(1L << attempt), call);
        }
    }

    private Attempt attempt(String call, Function<UriBuilder, URI> uri, List<Header> headers, MediaType[] accept,
                            int cap) {
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlexException("Interrupted while waiting to call Plex for " + call, e);
        }
        try {
            RestClient.RequestHeadersSpec<?> request = client.get().uri(b -> uri.apply(b))
                .header(TOKEN_HEADER, token).accept(accept);
            for (Header header : headers) {
                request = request.header(header.name(), header.value());
            }
            return request.exchange((req, response) -> {
                byte[] body = response.getBody().readNBytes(cap + 1);
                if (body.length > cap) {
                    throw new PlexException("Plex sent more than " + cap + " bytes for " + call, null);
                }
                return new Attempt(response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE), body);
            });
        } catch (RestClientException e) {
            return null;
        } finally {
            inFlight.release();
        }
    }

    private void pause(Duration duration, String call) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlexException("Interrupted while waiting to retry " + call, e);
        }
    }
}
