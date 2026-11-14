package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** How much disk the credits dataset job has left, behind a class so tests can set it. */
@Component
public class DiskSpace {

    /**
     * The usable bytes of the disk a directory is on; a directory that does not exist yet is measured by its nearest
     * existing parent.
     *
     * @param dir the directory
     * @return the usable bytes
     * @throws IOException when the disk cannot be read
     */
    public long usableBytes(Path dir) throws IOException {
        Path existing = dir.toAbsolutePath();
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        return Files.getFileStore(existing).getUsableSpace();
    }
}
