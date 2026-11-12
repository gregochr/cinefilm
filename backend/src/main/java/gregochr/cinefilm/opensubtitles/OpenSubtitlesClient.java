package gregochr.cinefilm.opensubtitles;

import gregochr.cinefilm.tmdb.RequestPacer;
import gregochr.cinefilm.tmdb.Sleeper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
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

/**
 * OpenSubtitles' REST API v1, search only: no login and no download. The API key goes in the {@code Api-Key} header
 * (never in a URL, never logged, never in an exception message), the {@code User-Agent} names CineFilm and its
 * version. Redirects are not followed (a 3xx is an error: the RestClient is built that way), the reply is read up to
 * a size cap, requests are paced to under 5 a second, and 429 and 5xx are retried with backoff.
 */
@Component
public class OpenSubtitlesClient implements OpenSubtitlesApi {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSubtitlesClient.class);

    /** One try and up to three retries. */
    static final int MAX_ATTEMPTS = 4;
    static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    /** 4 requests a second, under the limit of 5. */
    static final Duration PACE = Duration.ofMillis(250);
    /** The most of a reply that is read: a search page is some tens of kilobytes. */
    static final int MAX_BODY_BYTES = 1_000_000;

    private static final Pattern IMDB_ID = Pattern.compile("tt\\d{1,10}");
    private static final Pattern VERSION = Pattern.compile("\\d[A-Za-z0-9.+-]*");
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;

    private final RestClient client;
    private final String key;
    private final String userAgent;
    private final Sleeper sleeper;
    private final RequestPacer pacer;
    private final int maxBodyBytes;

    /**
     * Creates the client for the OpenSubtitles RestClient, the API key ({@code OPENSUBTITLES_API_KEY}), the app
     * version and a sleeper.
     */
    @Autowired
    public OpenSubtitlesClient(@Qualifier("openSubtitlesRestClient") RestClient client,
                               @Value("${cinefilm.opensubtitles.api-key:}") String key,
                               @Value("${cinefilm.version:1}") String version, Sleeper sleeper) {
        this(client, key, version, sleeper, System::nanoTime, MAX_BODY_BYTES);
    }

    OpenSubtitlesClient(RestClient client, String key, String version, Sleeper sleeper, LongSupplier nanoClock,
                        int maxBodyBytes) {
        this.client = client;
        this.key = key == null ? "" : key.trim();
        this.userAgent = "CineFilm v" + versionOf(version);
        this.sleeper = sleeper;
        this.pacer = new RequestPacer(nanoClock, sleeper, PACE);
        this.maxBodyBytes = maxBodyBytes;
    }

    /** The version without a leading {@code v}; {@code 1} when it is not a version (a header value is never raw). */
    private static String versionOf(String version) {
        String bare = version == null ? "" : version.trim();
        if (bare.startsWith("v") || bare.startsWith("V")) {
            bare = bare.substring(1);
        }
        return VERSION.matcher(bare).matches() ? bare : "1";
    }

    @Override
    public boolean enabled() {
        return !key.isEmpty();
    }

    @Override
    public OpenSubtitlesSearch searchForced(String imdbId) {
        if (imdbId == null || !IMDB_ID.matcher(imdbId).matches()) {
            throw new IllegalArgumentException("Not an IMDb id");
        }
        long number = Long.parseLong(imdbId.substring(2));
        if (number == 0) {
            throw new IllegalArgumentException("Not an IMDb id");
        }
        if (!enabled()) {
            throw new OpenSubtitlesUnavailableException("No OpenSubtitles API key is configured");
        }
        return OpenSubtitlesParser.parse(get(number), number);
    }

    /** One HTTP answer; the body is read only for a 2xx, and is null when it was over the cap. */
    private record Answer(int status, String retryAfter, String body) {
    }

    /** GET with pacing, retry and backoff; the body of a 200. Every other end is an exception. */
    private String get(long imdbNumber) {
        for (int attempt = 1; ; attempt++) {
            Answer answer = attempt(imdbNumber);
            if (answer != null && answer.status() / 100 == 2) {
                if (answer.body() == null) {
                    throw new OpenSubtitlesUnavailableException(
                        "OpenSubtitles answered more than " + maxBodyBytes + " bytes");
                }
                return answer.body();
            }
            if (answer != null && (answer.status() == UNAUTHORIZED || answer.status() == FORBIDDEN)) {
                throw new OpenSubtitlesUnavailableException("OpenSubtitles rejected the API key");
            }
            boolean retryable = answer == null || answer.status() == TOO_MANY_REQUESTS
                || answer.status() >= SERVER_ERROR;
            String what = answer == null ? "no response" : "status " + answer.status();
            if (!retryable) {
                throw new OpenSubtitlesUnavailableException("OpenSubtitles answered " + what);
            }
            if (attempt == MAX_ATTEMPTS) {
                throw new OpenSubtitlesUnavailableException(
                    "OpenSubtitles unavailable after " + MAX_ATTEMPTS + " attempts: " + what);
            }
            LOG.warn("OpenSubtitles {} (attempt {} of {}), retrying", what, attempt, MAX_ATTEMPTS);
            pause(backoff(attempt, answer));
        }
    }

    /** One request; null when there was no HTTP answer at all (the cause is dropped: its message holds the URL). */
    private Answer attempt(long imdbNumber) {
        try {
            pacer.await();
            // Parameters in alphabetical order, as the API wants (it redirects otherwise); the id has no "tt" and no
            // leading zeros.
            return client.get()
                .uri(b -> b.path("/api/v1/subtitles").queryParam("foreign_parts_only", "only")
                    .queryParam("imdb_id", imdbNumber).queryParam("languages", "en").build())
                .header("Api-Key", key).header(HttpHeaders.USER_AGENT, userAgent)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    String body = status / 100 == 2 ? read(response.getBody()) : "";
                    return new Answer(status, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), body);
                });
        } catch (RestClientException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenSubtitlesUnavailableException("Interrupted while waiting for OpenSubtitles");
        }
    }

    /** The body as text, or null when it is longer than the cap (reading stops one byte past it). */
    private String read(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(maxBodyBytes + 1);
        return bytes.length > maxBodyBytes ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static Duration backoff(int attempt, Answer answer) {
        Duration exponential = BASE_BACKOFF.multipliedBy(1L << (attempt - 1));
        if (answer != null && answer.status() == TOO_MANY_REQUESTS && answer.retryAfter() != null) {
            try {
                long asked = Long.parseLong(answer.retryAfter().trim());
                return Duration.ofSeconds(Math.max(0, Math.min(asked, MAX_RETRY_AFTER.toSeconds())));
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
            throw new OpenSubtitlesUnavailableException("Interrupted while waiting to retry OpenSubtitles");
        }
    }
}
