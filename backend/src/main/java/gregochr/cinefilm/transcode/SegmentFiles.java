package gregochr.cinefilm.transcode;

import java.nio.file.Path;
import java.util.List;

/** The few file operations the session manager needs, behind an interface so tests need no disk. */
interface SegmentFiles {

    /** True when the file exists. */
    boolean exists(Path file);

    /** Deletes one file; a missing file is fine. */
    void delete(Path file);

    /**
     * Deletes every {@code seg-<n>.ts} in the directory with {@code n} from {@code firstIndex} to {@code lastIndex}
     * inclusive (the run's own part: the segments of another part of the film stay).
     */
    void deleteRange(Path rungDir, int firstIndex, int lastIndex);

    /** Deletes a directory and everything under it; a missing directory is fine. */
    void deleteTree(Path dir);

    /** The sub-directories of a directory (empty when it does not exist). */
    List<Path> directories(Path dir);

    /** The total size in bytes of the files under a directory (0 when it does not exist). */
    long size(Path dir);
}
