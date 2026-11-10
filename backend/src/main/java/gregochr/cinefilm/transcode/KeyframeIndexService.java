package gregochr.cinefilm.transcode;

import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * Keeps one {@link MediaKeyframes} row per media file: reads the keyframes (see {@link KeyframeIndexer}), turns them
 * into a {@link CutList}, and stores it with the file's size and modification time so a changed file is re-indexed.
 * Safe to call from several threads; one file is indexed by one thread at a time.
 */
@Service
public class KeyframeIndexService {

    private final LibraryRoot root;
    private final KeyframeIndexer indexer;
    private final MediaKeyframesRepository rows;
    private final FileStamps stamps;
    private final Clock clock;
    /**
     * A film's stored index.
     *
     * @param cuts the segment timeline
     * @param fps the average frame rate, or null when unknown
     */
    public record Index(CutList cuts, Double fps) {
    }

    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<>();

    /** Creates the service. */
    public KeyframeIndexService(LibraryRoot root, KeyframeIndexer indexer, MediaKeyframesRepository rows,
                                FileStamps stamps, Clock clock) {
        this.root = root;
        this.indexer = indexer;
        this.rows = rows;
        this.stamps = stamps;
        this.clock = clock;
    }

    /**
     * How long a file's cut list is: the one rule every reader of an index uses (playback sessions, Film detail's
     * quality list, the background index job), so they all see the same cuts. A part of a split film ends where its
     * video ends (the container can be the audio's, a second or more longer); a film in one piece keeps the container
     * duration.
     *
     * @param file the original file
     * @param split true when the file is one of several parts of its edition
     * @return the duration the cut list covers, or null when unknown
     */
    public static Long indexDurationMs(MediaFile file, boolean split) {
        return split ? file.timelineDurationMs() : file.getDurationMs();
    }

    /** {@link #cached(MediaFile, Long)} for the duration {@link #indexDurationMs} gives. */
    public Optional<Index> cachedFor(MediaFile file, boolean split) {
        return cached(file, indexDurationMs(file, split));
    }

    /** {@link #ensureIndexed(MediaFile, Long)} for the duration {@link #indexDurationMs} gives. */
    public Index ensureIndexedFor(MediaFile file, boolean split) {
        return ensureIndexed(file, indexDurationMs(file, split));
    }

    /**
     * The stored cut list when it is still current (the file has the size and modification time it had when it was
     * indexed), without indexing anything.
     *
     * @param file an original media file (needs id, path and duration)
     * @return the cuts and frame rate, or empty when there is no row, the file changed, or the row is unusable
     */
    public Optional<Index> cached(MediaFile file) {
        return cached(file, file.getDurationMs());
    }

    /**
     * The stored cut list as {@link #cached(MediaFile)}, ending at the given duration instead of the file's container
     * duration (a part of a split film ends where its video ends).
     *
     * @param file an original media file (needs id and path)
     * @param durationMs the length the cut list covers, or null when unknown
     * @return the cuts and frame rate, or empty when there is no row, the file changed, or the row is unusable
     */
    public Optional<Index> cached(MediaFile file, Long durationMs) {
        Optional<Path> path = root.resolveFile(file.getPath());
        Optional<MediaKeyframes> row = rows.findById(file.getId());
        if (path.isEmpty() || row.isEmpty() || durationMs == null || durationMs <= 0) {
            return Optional.empty();
        }
        try {
            FileStamps.Stamp stamp = stamps.read(path.get());
            if (stamp.size() != row.get().getSourceSize() || stamp.mtimeMillis() != row.get().getSourceMtime()) {
                return Optional.empty();
            }
            return Optional.of(new Index(CutList.parse(row.get().getCuts(), durationMs),
                row.get().getFps()));
        } catch (IOException | KeyframeIndexException e) {
            return Optional.empty();
        }
    }

    /**
     * The cuts and frame rate of a file, indexing it first if it has none or has changed.
     *
     * @param file an original media file (needs id, path and duration)
     * @return the cuts and frame rate
     * @throws KeyframeIndexException when the file is missing, has no duration, or its keyframes cannot be read
     */
    public Index ensureIndexed(MediaFile file) {
        return ensureIndexed(file, file.getDurationMs());
    }

    /**
     * The cuts and frame rate of a file as {@link #ensureIndexed(MediaFile)}, ending at the given duration instead of
     * the file's container duration (a part of a split film ends where its video ends).
     *
     * @param file an original media file (needs id and path)
     * @param durationMs the length the cut list covers
     * @return the cuts and frame rate
     * @throws KeyframeIndexException when the file is missing, has no duration, or its keyframes cannot be read
     */
    public Index ensureIndexed(MediaFile file, Long durationMs) {
        synchronized (locks.computeIfAbsent(file.getId(), id -> new Object())) {
            Optional<Index> current = cached(file, durationMs);
            if (current.isPresent()) {
                return current.get();
            }
            return index(file, durationMs);
        }
    }

    private Index index(MediaFile file, Long durationMs) {
        Path path = root.resolveFile(file.getPath()).orElseThrow(() -> new KeyframeIndexException(
            "The file is missing or outside the library"));
        if (durationMs == null || durationMs <= 0) {
            throw new KeyframeIndexException("The file has no known duration");
        }
        FileStamps.Stamp stamp;
        try {
            stamp = stamps.read(path);
        } catch (IOException e) {
            throw new KeyframeIndexException("Cannot read the file's size and time: " + e.getMessage(), e);
        }
        KeyframeIndex found = indexer.index(path);
        CutList cuts = CutList.fromKeyframes(found.keyframesMs(), durationMs);
        String text = cuts.toText();
        if (text.length() > MediaKeyframes.MAX_CUTS_LENGTH) {
            throw new KeyframeIndexException("The film has too many segments to store");
        }
        rows.save(new MediaKeyframes(file.getId(), text, cuts.maxGapMs(), cuts.copyOk(), stamp.size(),
            stamp.mtimeMillis(), clock.instant(), found.fps()));
        return new Index(cuts, found.fps());
    }
}
