package gregochr.cinefilm.transcode;

import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.ScanFinished;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The background keyframe index: after a library scan (and once at start-up) it indexes every present, playable
 * original that has no current index, one file at a time on a low-priority thread. A file that fails is logged and
 * skipped. Only one run at a time; asking while one runs does nothing.
 */
@Component
public class IndexJob {

    private static final Logger LOG = LoggerFactory.getLogger(IndexJob.class);

    /**
     * How far the index has got.
     *
     * @param indexed present, playable originals with a stored index
     * @param total present, playable originals
     */
    public record Progress(long indexed, long total) {
    }

    private final MediaFileRepository files;
    private final MediaKeyframesRepository rows;
    private final KeyframeIndexService service;
    private final Executor executor;
    private final boolean onStartup;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Creates the job; the executor is {@code indexExecutor}, {@code cinefilm.transcode.index-on-startup} too. */
    public IndexJob(MediaFileRepository files, MediaKeyframesRepository rows, KeyframeIndexService service,
                    @Qualifier("indexExecutor") Executor executor,
                    @Value("${cinefilm.transcode.index-on-startup:true}") boolean onStartup) {
        this.files = files;
        this.rows = rows;
        this.service = service;
        this.executor = executor;
        this.onStartup = onStartup;
    }

    /** Indexes after a library scan finished (new files may have arrived). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        start();
    }

    /** Indexes once at start-up so files scanned before this feature existed get their index. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (onStartup) {
            start();
        }
    }

    /** True while a run is in progress. */
    public boolean isRunning() {
        return running.get();
    }

    /** How many of the library's playable originals are indexed. */
    public Progress progress() {
        return new Progress(rows.countIndexedPlayable(), files.countPresent(Playability.PLAYABLE));
    }

    /**
     * Starts a run in the background unless one is already running.
     *
     * @return true when a run was started
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    run();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    /** One pass over the library, in path order. */
    void run() {
        List<MediaFile> originals = files.findOriginals(Presence.PRESENT, Playability.PLAYABLE);
        int failed = 0;
        Map<Long, Long> perEdition = originals.stream()
            .collect(Collectors.groupingBy(f -> f.getEdition().getId(), Collectors.counting()));
        for (MediaFile file : originals) {
            try {
                service.ensureIndexedFor(file, perEdition.get(file.getEdition().getId()) > 1);
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("Keyframe index failed for {}: {}", file.getPath(), e.getMessage());
            }
        }
        LOG.info("Keyframe index pass finished: {} files, {} failed", originals.size(), failed);
    }
}
