package gregochr.cinefilm.still;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Grabs the frame at a point of an edition with {@code ffmpeg} and caches it. The source path always comes from the
 * database resolved under the library root (SECURITY.md rules 3 and 4); only a number comes from the request. At most
 * two grabs run at once so they cannot crowd a starting HLS encoder reading the same share.
 */
@Service
public class StillService {

    private static final Logger LOG = LoggerFactory.getLogger(StillService.class);

    /** How long ffmpeg may run before it is killed. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** How long a request waits for one of the grab slots. */
    public static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(3);

    /** How many grabs may run at once. */
    public static final int MAX_CONCURRENT = 2;

    /** How many cached stills are kept. */
    public static final int MAX_CACHED = 300;

    private final EditionRepository editions;
    private final LibraryRoot root;
    private final CommandRunner runner;
    private final TransactionTemplate transactions;
    private final String ffmpeg;
    private final Path cacheDir;
    private final Semaphore slots;
    private final Duration acquireTimeout;
    private final int maxCached;

    /** Creates the service; the cache folder is {@code cinefilm.cache.dir}, ffmpeg is {@code cinefilm.tools.ffmpeg}. */
    @Autowired
    public StillService(EditionRepository editions, LibraryRoot root, CommandRunner runner,
                        TransactionTemplate transactions, @Value("${cinefilm.tools.ffmpeg:ffmpeg}") String ffmpeg,
                        @Value("${cinefilm.cache.dir}") String cacheDir) {
        this(editions, root, runner, transactions, ffmpeg, cacheDir, MAX_CONCURRENT, ACQUIRE_TIMEOUT, MAX_CACHED);
    }

    StillService(EditionRepository editions, LibraryRoot root, CommandRunner runner, TransactionTemplate transactions,
                 String ffmpeg, String cacheDir, int concurrent, Duration acquireTimeout, int maxCached) {
        this.editions = editions;
        this.root = root;
        this.runner = runner;
        this.transactions = transactions;
        this.ffmpeg = ffmpeg;
        this.cacheDir = Path.of(cacheDir).toAbsolutePath().normalize().resolve("stills");
        this.slots = new Semaphore(concurrent);
        this.acquireTimeout = acquireTimeout;
        this.maxCached = maxCached;
    }

    /** What the grab needs from the database. */
    private record Source(Long mediaFileId, int part, String path, long seconds, long size, long modified) {
    }

    /**
     * The JPEG of the frame at an edition time, grabbing it first if it is not cached.
     *
     * @param editionId the edition
     * @param atMs the edition time in milliseconds, 0 up to the edition's length
     * @return the cached JPEG
     * @throws BadRequestException when the time is missing or outside the edition
     * @throws NotFoundException when the edition, its file or the file on disk is unknown or not playable
     * @throws ServiceUnavailableException when ffmpeg is busy, cannot start, times out or fails
     */
    public Path still(Long editionId, Long atMs) {
        Source source = transactions.execute(status -> lookup(editionId, atMs));
        Path cached = cacheDir.resolve(source.mediaFileId() + "-" + source.seconds() + "-" + source.size() + "-"
            + source.modified() + ".jpg");
        // The source must still be there even for a cached frame: a moved or removed film is a 404, not a stale image.
        Path input = root.resolveFile(source.path())
            .orElseThrow(() -> new NotFoundException("Media file is not available"));
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        acquire();
        try {
            return grab(editionId, source, input, cached);
        } finally {
            slots.release();
        }
    }

    private void acquire() {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Frame is not available right now", e);
        }
        if (!acquired) {
            throw new ServiceUnavailableException("Frame is not available right now");
        }
    }

    private Path grab(Long editionId, Source source, Path input, Path cached) {
        Path temporary = cacheDir.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(cacheDir);
            CommandRunner.Result result = runner.run(StillCommand.of(ffmpeg, input, source.seconds(), temporary),
                TIMEOUT);
            if (!result.succeeded() || !Files.isRegularFile(temporary)) {
                LOG.warn("ffmpeg could not grab a frame for edition {} part {} at {} s (exit {}, timed out {})",
                    editionId, source.part(), source.seconds(), result.exitCode(), result.timedOut());
                throw new ServiceUnavailableException("Frame is not available right now");
            }
            Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            prune(cached);
            return cached;
        } catch (IOException e) {
            LOG.warn("Frame grab failed for edition {} part {} at {} s: {}", editionId, source.part(),
                source.seconds(), e.getMessage());
            throw new ServiceUnavailableException("Frame is not available right now", e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                LOG.debug("Could not remove temporary file {}", temporary);
            }
        }
    }

    /** Deletes the oldest cached stills (never the one just written) until at most {@code maxCached} remain. */
    private void prune(Path keep) {
        try (Stream<Path> listing = Files.list(cacheDir)) {
            List<Path> others = listing.filter(p -> p.getFileName().toString().endsWith(".jpg"))
                .filter(p -> !p.equals(keep))
                .sorted(Comparator.comparingLong(StillService::modified).reversed()).toList();
            int room = Math.max(0, maxCached - 1);
            for (Path old : others.subList(Math.min(room, others.size()), others.size())) {
                Files.deleteIfExists(old);
            }
        } catch (IOException e) {
            LOG.debug("Could not prune the still cache: {}", e.getMessage());
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private Source lookup(Long editionId, Long atMs) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        List<MediaFile> parts = edition.originalParts();
        if (parts.isEmpty()) {
            throw new NotFoundException("Edition has no original file");
        }
        EditionTimeline timeline;
        try {
            timeline = EditionTimeline.of(edition);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Frame is not available");
        }
        if (atMs == null || atMs < 0 || atMs > timeline.totalMs()) {
            throw new BadRequestException("atMs must be between 0 and the film's length");
        }
        EditionTimeline.Position position = timeline.locate(atMs);
        MediaFile part = parts.get(position.partIndex());
        if (part.getPlayability() != Playability.PLAYABLE) {
            throw new NotFoundException("Media file is not available");
        }
        long lastFrameMs = Math.max(0, part.timelineDurationMs() - 1000);
        long seconds = Math.min(position.offsetMs(), lastFrameMs) / 1000;
        return new Source(part.getId(), position.partIndex() + 1, part.getPath(), seconds,
            part.getSizeBytes() == null ? 0 : part.getSizeBytes(),
            part.getModifiedAt() == null ? 0 : part.getModifiedAt().toEpochMilli());
    }
}
