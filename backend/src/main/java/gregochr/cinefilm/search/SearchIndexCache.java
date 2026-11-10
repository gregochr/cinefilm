package gregochr.cinefilm.search;

import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Memoises the serialised search index and its weak ETag ({@code W/"…"}, the first 16 hex characters of the SHA-256
 * of the bytes; weak because Tomcat does not compress a response that carries a strong one). Invalidating only marks
 * the memo stale; the next request rebuilds it. The ETag is a hash of the content, so a missed invalidation can only
 * cost freshness, never correctness.
 */
@Component
public class SearchIndexCache {

    private static final int ETAG_HEX_CHARS = 16;

    /** The serialised index and the weak ETag that goes with it. */
    public record Snapshot(byte[] body, String etag) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Snapshot that && Arrays.equals(body, that.body) && Objects.equals(etag, that.etag);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(body) + Objects.hashCode(etag);
        }

        @Override
        public String toString() {
            return "Snapshot[body=" + body.length + " bytes, etag=" + etag + "]";
        }
    }

    /** A snapshot and the generation that was current when its build began. */
    private record Held(Snapshot snapshot, long generation) {
    }

    private final SearchIndexService service;
    private final JsonMapper mapper;
    private final Object lock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private volatile Held current;

    /** Creates the cache over the index builder and the application's JSON mapper. */
    public SearchIndexCache(SearchIndexService service, JsonMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** The current index, built now if an invalidation has made the memo stale. */
    public Snapshot get() {
        Held held = current;
        return held != null && held.generation() == generation.get() ? held.snapshot() : rebuild();
    }

    /** Builds under the lock; a caller that waited behind another build takes its result if still current. */
    private Snapshot rebuild() {
        synchronized (lock) {
            return Optional.ofNullable(current)
                .filter(this::isCurrent)
                .map(Held::snapshot)
                .orElseGet(this::build);
        }
    }

    /** Whether no invalidation has happened since this memo's build began (a waiter's re-check; see rebuild). */
    private boolean isCurrent(Held held) {
        return Long.valueOf(held.generation()).equals(generation.get());
    }

    private Snapshot build() {
        // The generation is read before building and stored with the result, unconditionally: an invalidation that
        // lands at any point from here on leaves the stored generation behind, so the next get() rebuilds.
        long before = generation.get();
        byte[] body = mapper.writeValueAsBytes(service.build());
        Snapshot built = new Snapshot(body, etag("SHA-256", body));
        current = new Held(built, before);
        return built;
    }

    /** Marks the memo stale; the next request rebuilds it. */
    public void invalidate() {
        generation.incrementAndGet();
    }

    /** A scan finished. */
    @EventListener
    void onScanFinished(ScanFinished event) {
        invalidate();
    }

    /** A Plex import finished. */
    @EventListener
    void onPlexImportFinished(PlexImportFinished event) {
        invalidate();
    }

    /** A playback write or a manual regrouping committed (or, outside a transaction, happened). */
    @TransactionalEventListener(fallbackExecution = true)
    void onLibraryChanged(LibraryChanged event) {
        invalidate();
    }

    /** The weak ETag for these bytes: the first 16 hex characters of their digest, quoted, with a {@code W/}. */
    static String etag(String algorithm, byte[] body) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(body));
            return "W/\"" + hex.substring(0, ETAG_HEX_CHARS) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
