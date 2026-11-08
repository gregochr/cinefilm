package gregochr.cinefilm.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The configured library folder, and the only place a stored media path becomes a file path. Paths are stored
 * relative to the root; anything that would resolve outside it (a {@code ..} segment, an absolute path, a symlink
 * pointing out) is refused (SECURITY.md rule 3).
 */
@Component
public class LibraryRoot {

    private final Path root;

    /** Creates the root from {@code cinefilm.library.root}. */
    public LibraryRoot(@Value("${cinefilm.library.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /** The library folder as configured (absolute, normalised). */
    public Path root() {
        return root;
    }

    /**
     * Resolves a stored relative path to an existing regular file under the root.
     *
     * @param relative the path stored in the database (never null)
     * @return the file, or empty when the path is absolute, escapes the root (including through a symlink), is not a
     *     valid path, or is not an existing regular file
     */
    public Optional<Path> resolveFile(String relative) {
        try {
            Path stored = Path.of(relative);
            if (stored.isAbsolute()) {
                return Optional.empty();
            }
            Path candidate = root.resolve(stored).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return Optional.empty();
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (InvalidPathException | IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The path to store for a file found under the root, using forward slashes.
     *
     * @param file a file path under the root
     * @return the path relative to the root
     */
    public String relativize(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
