package gregochr.cinefilm.transcode;

import java.io.IOException;
import java.nio.file.Path;

/** Reads a file's size and modification time; an interface so tests control what "changed" means. */
public interface FileStamps {

    /**
     * A file's identity for change detection.
     *
     * @param size bytes
     * @param mtimeMillis last-modified time, milliseconds since the epoch
     */
    record Stamp(long size, long mtimeMillis) {
    }

    /** Reads the stamp. */
    Stamp read(Path file) throws IOException;
}
