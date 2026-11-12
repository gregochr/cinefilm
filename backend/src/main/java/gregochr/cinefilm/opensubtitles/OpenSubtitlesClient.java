package gregochr.cinefilm.opensubtitles;

import gregochr.cinefilm.subtitle.SrtParser;
import gregochr.cinefilm.tmdb.RequestPacer;
import gregochr.cinefilm.tmdb.Sleeper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenSubtitles' REST API v1: search, and (when the account's username and password are configured) login and
 * download. The API key goes in the {@code Api-Key} header and the session token in {@code Authorization} (never in a
 * URL, never logged, never in an exception message; neither are the username and password), the {@code User-Agent}
 * names CineFilm and its version. Redirects are not followed (a 3xx is an error: the RestClient is built that way),
 * replies are read up to a size cap, requests are paced to under 5 a second (logins to one a second), and 429 and
 * 5xx are retried with backoff. The session token is kept in memory and a login is made again only after a 401.
 * A download answers with a temporary link: it is fetched only when it is {@code https} on a
 * {@code *.opensubtitles.com} host, with no key or token sent to it, and at most 2 MB of the file is read.
 */
@Component
public class OpenSubtitlesClient implements OpenSubtitlesApi {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSubtitlesClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One try and up to three retries. */
    static final int MAX_ATTEMPTS = 4;
    static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    /** 4 requests a second, under the limit of 5. */
    static final Duration PACE = Duration.ofMillis(250);
    /** Logins are limited to about one a second. */
    static final Duration LOGIN_PACE = Duration.ofSeconds(1);
    /** The most of a JSON reply that is read: a search page is some tens of kilobytes. */
    static final int MAX_BODY_BYTES = 1_000_000;
    /** The most of a subtitle file that is read from a download link. */
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static final Pattern IMDB_ID = Pattern.compile("tt\\d{1,10}");
    private static final Pattern VERSION = Pattern.compile("\\d[A-Za-z0-9.+-]*");
    /** A JWT or anything shaped like one: safe to send in a header. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,4096}");
    private static final Pattern LIMIT_MESSAGE = Pattern.compile("(?i)limit|quota");
    private static final String HOST_SUFFIX = ".opensubtitles.com";
    private static final String LINK_REFUSED = "OpenSubtitles download link refused";
    private static final int HTTPS_PORT = 443;
    private static final int MAX_HOST_CHARS = 253;
    private static final int GZIP_FIRST = 0x1f;
    private static final int GZIP_SECOND = 0x8b;
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_ACCEPTABLE = 406;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;

    private final RestClient client;
    private final String key;
    private final String username;
    private final String password;
    private final String userAgent;
    private final Sleeper sleeper;
    private final RequestPacer pacer;
    private final RequestPacer loginPacer;
    private final int maxBodyBytes;
    private final int maxFileBytes;

    /** The session token from the last login, or null; guarded by {@code this}. */
    private String sessionToken;
    /** The account's own API host from the last login when it is acceptable, or null; guarded by {@code this}. */
    private String downloadHost;

    /**
     * Creates the client for the OpenSubtitles RestClient, the API key ({@code OPENSUBTITLES_API_KEY}), the
     * account's username and password (downloads are off while either is blank), the app version and a sleeper.
     */
    @Autowired
    public OpenSubtitlesClient(@Qualifier("openSubtitlesRestClient") RestClient client,
                               @Value("${cinefilm.opensubtitles.api-key:}") String key,
                               @Value("${cinefilm.opensubtitles.username:}") String username,
                               @Value("${cinefilm.opensubtitles.password:}") String password,
                               @Value("${cinefilm.version:1}") String version, Sleeper sleeper) {
        this(client, key, username, password, version, sleeper, System::nanoTime, MAX_BODY_BYTES, MAX_FILE_BYTES);
    }

    OpenSubtitlesClient(RestClient client, String key, String username, String password, String version,
                        Sleeper sleeper, LongSupplier nanoClock, int maxBodyBytes, int maxFileBytes) {
        this.client = client;
        this.key = key == null ? "" : key.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.userAgent = "CineFilm v" + versionOf(version);
        this.sleeper = sleeper;
        this.pacer = new RequestPacer(nanoClock, sleeper, PACE);
        this.loginPacer = new RequestPacer(nanoClock, sleeper, LOGIN_PACE);
        this.maxBodyBytes = maxBodyBytes;
        this.maxFileBytes = maxFileBytes;
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
    public boolean downloadsEnabled() {
        return enabled() && !username.isEmpty() && !password.isEmpty();
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
        Answer<String> answer = retrying(() -> searchOnce(number));
        if (answer.status() / 100 == 2) {
            if (answer.body() == null) {
                throw new OpenSubtitlesUnavailableException(
                    "OpenSubtitles answered more than " + maxBodyBytes + " bytes");
            }
            return OpenSubtitlesParser.parse(answer.body(), number);
        }
        if (answer.status() == UNAUTHORIZED || answer.status() == FORBIDDEN) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles rejected the API key");
        }
        throw new OpenSubtitlesUnavailableException("OpenSubtitles answered status " + answer.status());
    }

    @Override
    public OpenSubtitlesDownload download(long fileId) {
        if (fileId <= 0) {
            throw new IllegalArgumentException("Not a file id");
        }
        if (!downloadsEnabled()) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles downloads are off: no login is configured");
        }
        boolean hadSession = hasSession();
        String bearer = session();
        Answer<String> answer = retryingDownload(() -> downloadOnce(bearer, fileId));
        if (answer.status() == UNAUTHORIZED) {
            // The session ran out: log in again, once, and only when this call did not just log in.
            dropSession();
            if (!hadSession) {
                throw new OpenSubtitlesUnavailableException("OpenSubtitles rejected the session");
            }
            String renewed = session();
            answer = retryingDownload(() -> downloadOnce(renewed, fileId));
            if (answer.status() == UNAUTHORIZED) {
                dropSession();
                throw new OpenSubtitlesUnavailableException("OpenSubtitles rejected the session");
            }
        }
        return downloaded(answer);
    }

    /** What the download request answered, turned into the file or an exception. */
    private OpenSubtitlesDownload downloaded(Answer<String> answer) {
        if (answer.status() == NOT_ACCEPTABLE) {
            throw new OpenSubtitlesQuotaException(resetOf(answer.body()));
        }
        if (answer.status() / 100 != 2) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles download answered status " + answer.status());
        }
        if (answer.body() == null) {
            throw new OpenSubtitlesChargedException("OpenSubtitles answered more than " + maxBodyBytes + " bytes",
                false);
        }
        JsonNode root;
        try {
            root = json(answer.body(), "OpenSubtitles returned unreadable JSON");
        } catch (OpenSubtitlesUnavailableException e) {
            // A 2xx we cannot read: the download may have been charged.
            throw new OpenSubtitlesChargedException(e.getMessage(), false);
        }
        Instant reset = resetOf(root);
        JsonNode link = root.path("link");
        if (!link.isString()) {
            JsonNode message = root.path("message");
            if (message.isString() && LIMIT_MESSAGE.matcher(message.asString()).find()) {
                throw new OpenSubtitlesQuotaException(reset);
            }
            // A 2xx with no link and no limit message: the download may have been charged.
            throw new OpenSubtitlesChargedException("OpenSubtitles returned an unexpected download reply", false);
        }
        // From here on OpenSubtitles has issued a link and charged the account: every failure is a charged one.
        URI uri;
        try {
            uri = allowedLink(link.asString());
        } catch (OpenSubtitlesUnavailableException e) {
            throw new OpenSubtitlesChargedException(e.getMessage(), true);
        }
        JsonNode remaining = root.path("remaining");
        Integer left = remaining.isIntegralNumber()
            ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, remaining.asLong())) : null;
        Answer<Fetched> file;
        try {
            file = retrying(() -> fetchOnce(uri));
        } catch (OpenSubtitlesUnavailableException e) {
            throw new OpenSubtitlesChargedException(e.getMessage(), false);
        }
        if (file.status() / 100 != 2) {
            throw new OpenSubtitlesChargedException("OpenSubtitles file answered status " + file.status(), false);
        }
        if (file.body() == null) {
            throw new OpenSubtitlesChargedException("OpenSubtitles file is larger than " + maxFileBytes + " bytes",
                true);
        }
        if (file.body().problem() != null) {
            // The link was issued and the download counted: a payload that cannot be used is never a refund.
            throw new OpenSubtitlesChargedException(file.body().problem(), true);
        }
        return new OpenSubtitlesDownload(file.body().parsed(), left, reset);
    }

    /**
     * The download link as a URI when it may be fetched: {@code https}, a host that ends in
     * {@code .opensubtitles.com}, no user info, the default port. Anything else is refused before any request.
     */
    private static URI allowedLink(String link) {
        URI uri;
        try {
            uri = new URI(link.strip());
        } catch (URISyntaxException e) {
            throw new OpenSubtitlesUnavailableException(LINK_REFUSED);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
            && (uri.getPort() == -1 || uri.getPort() == HTTPS_PORT) && isOpenSubtitlesHost(host);
        if (!allowed) {
            throw new OpenSubtitlesUnavailableException(LINK_REFUSED);
        }
        return uri;
    }

    /** True for a plain host name below {@code opensubtitles.com} (the bare domain has an empty first label). */
    private static boolean isOpenSubtitlesHost(String host) {
        return isPlainHost(host) && host.endsWith(HOST_SUFFIX);
    }

    /** A plain lower-case host name: dot-separated labels of letters, digits and inner hyphens (no regex, no IPv6). */
    private static boolean isPlainHost(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST_CHARS) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The reset time a reply states ({@code reset_time_utc}), or null; a reply that is not JSON has none. */
    private static Instant resetOf(String body) {
        if (body == null) {
            return null;
        }
        try {
            return resetOf(JSON.readTree(body));
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Instant resetOf(JsonNode root) {
        JsonNode reset = root.path("reset_time_utc");
        if (!reset.isString()) {
            return null;
        }
        try {
            return Instant.parse(reset.asString().strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static JsonNode json(String body, String message) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new OpenSubtitlesUnavailableException(message);
        }
        if (!root.isObject()) {
            throw new OpenSubtitlesUnavailableException(message);
        }
        return root;
    }

    private synchronized boolean hasSession() {
        return sessionToken != null;
    }

    /** The session token, logging in when there is none. Logins are rate limited, so the token is kept. */
    private synchronized String session() {
        if (sessionToken == null) {
            login();
        }
        return sessionToken;
    }

    /** Forgets a token that was refused: the next download logs in again. */
    private synchronized void dropSession() {
        sessionToken = null;
    }

    private synchronized String downloadHost() {
        return downloadHost;
    }

    private void login() {
        Answer<String> answer = retrying(this::loginOnce);
        if (answer.status() == UNAUTHORIZED || answer.status() == FORBIDDEN) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles rejected the login");
        }
        if (answer.status() / 100 != 2) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles login answered status " + answer.status());
        }
        if (answer.body() == null) {
            throw new OpenSubtitlesUnavailableException(
                "OpenSubtitles answered more than " + maxBodyBytes + " bytes");
        }
        JsonNode root = json(answer.body(), "OpenSubtitles returned an unreadable login reply");
        JsonNode value = root.path("token");
        if (!value.isString() || !TOKEN.matcher(value.asString()).matches()) {
            throw new OpenSubtitlesUnavailableException("OpenSubtitles returned an unexpected login reply");
        }
        sessionToken = value.asString();
        // The account's own API host (a VIP account has another one) is used only when it is a plain host name below
        // opensubtitles.com; anything else is ignored.
        JsonNode base = root.path("base_url");
        String host = base.isString() ? base.asString().strip().toLowerCase(Locale.ROOT) : "";
        downloadHost = isOpenSubtitlesHost(host) ? host : null;
    }

    /** One HTTP answer. The body is null when none was read or it was over its cap. */
    private record Answer<T>(int status, String retryAfter, T body) {
    }

    /** One request; it may fail with a {@link RestClientException} when there was no HTTP answer at all. */
    private interface Attempt<T> {
        Answer<T> run() throws InterruptedException;
    }

    /**
     * Sends with pacing, retry and backoff (429 and 5xx, and a request that got no answer). Any other status is
     * handed back for the caller to read; a request that never gets through is an exception.
     */
    private <T> Answer<T> retrying(Attempt<T> attempt) {
        return retrying(attempt, false);
    }

    /**
     * The chargeable {@code POST /download}: only a clear rejection (429) is retried. A request that got no answer
     * (the JDK client cannot tell "refused before anything was sent" from "sent, then timed out or reset", so every
     * transport failure counts as the second) or a 5xx may already have been charged, and sending it again could charge
     * it again: those are {@link OpenSubtitlesChargedException}s (transient), never retried.
     */
    private Answer<String> retryingDownload(Attempt<String> attempt) {
        return retrying(attempt, true);
    }

    private <T> Answer<T> retrying(Attempt<T> attempt, boolean chargeable) {
        for (int attemptNumber = 1; ; attemptNumber++) {
            Answer<T> answer;
            try {
                answer = attempt.run();
            } catch (RestClientException e) {
                // The cause is dropped: its message holds the URL.
                answer = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenSubtitlesUnavailableException("Interrupted while waiting for OpenSubtitles");
            }
            boolean retryable = answer == null || answer.status() == TOO_MANY_REQUESTS
                || answer.status() >= SERVER_ERROR;
            if (!retryable) {
                return answer;
            }
            String what = answer == null ? "no response" : "status " + answer.status();
            if (chargeable && (answer == null || answer.status() != TOO_MANY_REQUESTS)) {
                throw new OpenSubtitlesChargedException("OpenSubtitles download request failed: " + what, false);
            }
            if (attemptNumber == MAX_ATTEMPTS) {
                throw new OpenSubtitlesUnavailableException(
                    "OpenSubtitles unavailable after " + MAX_ATTEMPTS + " attempts: " + what);
            }
            LOG.warn("OpenSubtitles {} (attempt {} of {}), retrying", what, attemptNumber, MAX_ATTEMPTS);
            pause(backoff(attemptNumber, answer));
        }
    }

    private Answer<String> searchOnce(long imdbNumber) throws InterruptedException {
        pacer.await();
        // Parameters in alphabetical order, as the API wants (it redirects otherwise); the id has no "tt" and no
        // leading zeros.
        return client.get()
            .uri(b -> b.path("/api/v1/subtitles").queryParam("foreign_parts_only", "only")
                .queryParam("imdb_id", imdbNumber).queryParam("languages", "en").build())
            .header("Api-Key", key).header(HttpHeaders.USER_AGENT, userAgent)
            .accept(MediaType.APPLICATION_JSON)
            .exchange((request, response) -> text(response.getStatusCode().value(),
                response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), response.getBody(), false));
    }

    private Answer<String> loginOnce() throws InterruptedException {
        loginPacer.await();
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("username", username);
        credentials.put("password", password);
        String body = JSON.writeValueAsString(credentials);
        return client.post().uri("/api/v1/login")
            .header("Api-Key", key).header(HttpHeaders.USER_AGENT, userAgent)
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body)
            .exchange((request, response) -> text(response.getStatusCode().value(),
                response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), response.getBody(), false));
    }

    private Answer<String> downloadOnce(String bearer, long fileId) throws InterruptedException {
        pacer.await();
        String host = downloadHost();
        RestClient.RequestBodySpec request = host == null ? client.post().uri("/api/v1/download")
            : client.post().uri(URI.create("https://" + host + "/api/v1/download"));
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .header("Api-Key", key).header(HttpHeaders.USER_AGENT, userAgent)
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .body("{\"file_id\":" + fileId + "}")
            .exchange((req, response) -> text(response.getStatusCode().value(),
                response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), response.getBody(), true));
    }

    /** A fetched file: read as SRT, or the reason it could not be (a gzip payload that is corrupt or too large). */
    private record Fetched(SrtParser.Parsed parsed, String problem) {
    }

    /**
     * Fetches the file behind a download link: a GET with none of the account's headers (the link host is never given
     * the key or the session) and no {@code Accept-Encoding}. At most {@code maxFileBytes} are read from the wire; a
     * gzip payload (the magic bytes or {@code Content-Encoding: gzip}) is then inflated with its own cap of the same
     * size, so neither the wire nor the inflated text can exceed it.
     */
    private Answer<Fetched> fetchOnce(URI link) throws InterruptedException {
        pacer.await();
        return client.get().uri(link).header(HttpHeaders.USER_AGENT, userAgent)
            .exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                if (status / 100 != 2) {
                    return new Answer<>(status, retryAfter, null);
                }
                byte[] bytes = response.getBody().readNBytes(maxFileBytes + 1);
                if (bytes.length > maxFileBytes) {
                    return new Answer<>(status, retryAfter, null);
                }
                String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
                boolean gzip = encoding != null && "gzip".equalsIgnoreCase(encoding.strip())
                    || bytes.length >= 2 && (bytes[0] & 0xff) == GZIP_FIRST && (bytes[1] & 0xff) == GZIP_SECOND;
                Fetched fetched = gzip ? inflated(bytes) : new Fetched(SrtParser.parse(bytes), null);
                return new Answer<>(status, retryAfter, fetched);
            });
    }

    /** Inflates a gzip payload up to {@code maxFileBytes}; a corrupt or too large one is a problem, not a throw. */
    private Fetched inflated(byte[] gzipped) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            byte[] text = in.readNBytes(maxFileBytes + 1);
            if (text.length > maxFileBytes) {
                return new Fetched(null, "OpenSubtitles file is larger than " + maxFileBytes + " bytes once unzipped");
            }
            return new Fetched(SrtParser.parse(text), null);
        } catch (IOException e) {
            return new Fetched(null, "OpenSubtitles file could not be unzipped");
        }
    }

    /** The answer with its body read as text for a 2xx (and a 406 when asked), null when over the cap. */
    private Answer<String> text(int status, String retryAfter, InputStream body, boolean readNotAcceptable)
        throws IOException {
        if (status / 100 != 2 && !(readNotAcceptable && status == NOT_ACCEPTABLE)) {
            return new Answer<>(status, retryAfter, null);
        }
        byte[] bytes = body.readNBytes(maxBodyBytes + 1);
        return new Answer<>(status, retryAfter,
            bytes.length > maxBodyBytes ? null : new String(bytes, StandardCharsets.UTF_8));
    }

    private static Duration backoff(int attempt, Answer<?> answer) {
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
