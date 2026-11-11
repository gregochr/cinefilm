package gregochr.cinefilm.plex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resized copies of stored artwork, made on first request and kept under {@code <cache dir>/art/<width>/<name>}. The
 * path is built only from the validated stored name and one of four constant widths (SECURITY.md rules 3 and 10). At
 * most two resizes run at once; the folder is capped at 128 MiB, oldest files removed first, one prune at a time (a
 * writer that finds one running waits and prunes in turn if the total is still over the cap).
 */
@Service
public class ResizedArtwork {

    private static final Logger LOG = LoggerFactory.getLogger(ResizedArtwork.class);

    /** How many resizes may run at once. */
    public static final int MAX_CONCURRENT = 2;

    /** How long a request waits for one of the resize slots. */
    public static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(10);

    /** The most bytes the resized copies may take. */
    public static final long MAX_BYTES = 128L * 1024 * 1024;

    private static final long STALE_TEMPORARY_MS = Duration.ofHours(1).toMillis();

    /** What to serve. */
    public enum Outcome {
        /** The resized copy, in {@link Result#bytes()}. */
        RESIZED,
        /** The stored image is not wider than the width: serve the stored file. */
        NOT_WIDER,
        /** The resized copy would not be smaller than the stored file: serve the stored file. */
        NOT_SMALLER,
        /** The stored image cannot be resized: serve the stored file, cached for a day. */
        UNREADABLE,
        /** No resize slot came free in time: serve the stored file, not cached. */
        BUSY
    }

    /** The outcome and, for {@link Outcome#RESIZED}, the bytes. */
    public record Result(Outcome outcome, byte[] bytes) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private final ArtworkResizer resizer;
    private final Path artDir;
    private final long maxBytes;
    private final Semaphore slots;
    private final Duration acquireTimeout;
    private final AtomicLong total = new AtomicLong();
    private final ReentrantLock pruneLock = new ReentrantLock();
    private boolean seeded;

    /** Creates the service; the cache folder is {@code cinefilm.cache.dir}. */
    @Autowired
    public ResizedArtwork(@Value("${cinefilm.cache.dir}") String cacheDir) {
        this(new ArtworkResizer(), cacheDir, MAX_BYTES, MAX_CONCURRENT, ACQUIRE_TIMEOUT);
    }

    ResizedArtwork(ArtworkResizer resizer, String cacheDir, long maxBytes, int concurrent, Duration acquireTimeout) {
        this.resizer = resizer;
        this.artDir = Path.of(cacheDir).toAbsolutePath().normalize().resolve("art");
        this.maxBytes = maxBytes;
        this.slots = new Semaphore(concurrent);
        this.acquireTimeout = acquireTimeout;
    }

    /**
     * The width a request value asks for.
     *
     * @param raw the {@code w} parameter, possibly null
     * @return 342, 500, 780 or 1280 when the value is exactly that string, else empty. The int is a constant, never
     *     parsed from the request.
     */
    public static OptionalInt parseWidth(String raw) {
        if (raw == null) {
            return OptionalInt.empty();
        }
        return switch (raw) {
            case "342" -> OptionalInt.of(342);
            case "500" -> OptionalInt.of(500);
            case "780" -> OptionalInt.of(780);
            case "1280" -> OptionalInt.of(1280);
            default -> OptionalInt.empty();
        };
    }

    /**
     * The copy of a stored image at a width, made and cached if it is not yet.
     *
     * @param name the validated stored name (the extension says the format)
     * @param stored the stored file
     * @param width one of the widths {@link #parseWidth} returns
     * @return what to serve
     */
    public Result resized(String name, Path stored, int width) {
        Path cached = artDir.resolve(Integer.toString(width)).resolve(name);
        byte[] hit = readCached(cached);
        if (hit != null) {
            return new Result(Outcome.RESIZED, hit);
        }
        switch (resizer.probe(stored, name, width)) {
            case CANNOT_RESIZE -> {
                return Result.of(Outcome.UNREADABLE);
            }
            case NOT_WIDER -> {
                return Result.of(Outcome.NOT_WIDER);
            }
            default -> {
                // Within the limits and wider than the width: resize below, under a slot.
            }
        }
        if (!acquire()) {
            return Result.of(Outcome.BUSY);
        }
        Optional<byte[]> resized;
        try {
            resized = resizer.resize(stored, name, width);
        } finally {
            slots.release();
        }
        if (resized.isEmpty()) {
            return Result.of(Outcome.UNREADABLE);
        }
        byte[] bytes = resized.get();
        long storedSize;
        try {
            storedSize = Files.size(stored);
        } catch (IOException e) {
            LOG.warn("Could not size stored artwork: {}", e.getClass().getSimpleName());
            return Result.of(Outcome.UNREADABLE);
        }
        if (bytes.length >= storedSize) {
            return Result.of(Outcome.NOT_SMALLER);
        }
        cache(bytes, cached);
        return new Result(Outcome.RESIZED, bytes);
    }

    /** The cached bytes, or null when there is no usable cached file (a file pruned meanwhile counts as a miss). */
    private byte[] readCached(Path cached) {
        if (!Files.isRegularFile(cached)) {
            return null;
        }
        try {
            return read(cached);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOG.warn("Could not read cached artwork: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** Reads a cached file; a seam so a test can remove the file between the existence check and the read. */
    byte[] read(Path file) throws IOException {
        return Files.readAllBytes(file);
    }

    private boolean acquire() {
        try {
            return slots.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Writes the copy through a temporary file in the same folder, then prunes if the folder is over its cap. */
    private void cache(byte[] bytes, Path cached) {
        Path temporary = null;
        try {
            seedTotal();
            Path folder = cached.getParent();
            Files.createDirectories(folder);
            if (Files.exists(cached)) {
                // A concurrent request just cached the same bytes: nothing to write or count. The window between this
                // check and the move is tiny, and a residual over-count only brings a prune forward (it deletes
                // nothing while the real total is at most 90% of the cap, and its delta restores the real figure).
                return;
            }
            temporary = Files.createTempFile(folder, "art-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            total.addAndGet(bytes.length);
        } catch (IOException e) {
            LOG.warn("Could not cache resized artwork: {}", e.getClass().getSimpleName());
            return;
        } finally {
            deleteQuietly(temporary);
        }
        if (total.get() > maxBytes) {
            // One prune at a time, and no writer is skipped: each one waits its turn and prunes only if the total is
            // still over the cap by then.
            pruneLock.lock();
            try {
                if (total.get() > maxBytes) {
                    prune(cached);
                }
            } catch (IOException e) {
                LOG.warn("Could not prune resized artwork: {}", e.getClass().getSimpleName());
            } finally {
                pruneLock.unlock();
            }
        }
    }

    /** The running total of bytes, for tests. */
    long trackedBytes() {
        return total.get();
    }

    /** True when a writer is waiting for the prune lock, for tests. */
    boolean pruneQueued() {
        return pruneLock.hasQueuedThreads();
    }

    /** A seam for tests: called after the prune has walked the folder and before it deletes anything. */
    void afterWalk() {
        // Nothing in production.
    }

    /** Sets the running total from one walk of the folder, the first time a copy is written. */
    private synchronized void seedTotal() throws IOException {
        if (!seeded) {
            total.set(walk().stream().mapToLong(Entry::size).sum());
            seeded = true;
        }
    }

    private record Entry(Path path, long size, long modified) {
    }

    private List<Entry> walk() throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (Files.isDirectory(artDir)) {
            Files.walkFileTree(artDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    entries.add(new Entry(file, attributes.size(), attributes.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return entries;
    }

    /**
     * Deletes stale temporary files, then the oldest copies (never {@code keep}) until the folder is at most 90% of the
     * cap, and brings the running total to the real figure.
     */
    void prune(Path keep) throws IOException {
        long now = System.currentTimeMillis();
        long before = total.get();
        long remaining = 0;
        List<Entry> copies = new ArrayList<>();
        List<Entry> listing = walk();
        afterWalk();
        for (Entry entry : listing) {
            boolean temporary = entry.path().getFileName().toString().endsWith(".tmp");
            boolean gone = temporary && now - entry.modified() > STALE_TEMPORARY_MS && delete(entry.path());
            if (!gone) {
                remaining += entry.size();
                if (!temporary) {
                    copies.add(entry);
                }
            }
        }
        long target = maxBytes / 10 * 9;
        copies.sort(Comparator.comparingLong(Entry::modified));
        for (Entry entry : copies) {
            if (remaining <= target) {
                break;
            }
            // A copy that could not be deleted still takes space: carry on to the next-oldest.
            if (!entry.path().equals(keep) && delete(entry.path())) {
                remaining -= entry.size();
            }
        }
        // A delta, so a write that landed after the walk stays counted; a file both walked and added after `before` is
        // counted twice, which only brings the next prune forward.
        total.addAndGet(remaining - before);
    }

    /** Deletes a file for the prune; a seam for tests. True when the file is gone, false when the delete failed. */
    boolean delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not delete a resized copy: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Deletes a file if there is one; a failure (or no file, as {@code null}) is not worth failing a request for. */
    static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.debug("Could not delete a resized copy: {}", e.getClass().getSimpleName());
        }
    }
}
