package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the credits deny file ({@code cinefilm.credits.auto.denylist}) and parses it. An empty path, a missing or
 * unreadable file and a malformed one are all the same answer, empty, so the app boots before the file exists.
 */
@Component
public class CreditsDenyFileReader {

    private final String path;

    /** Creates the reader; an empty path counts as unreadable. */
    public CreditsDenyFileReader(@Value("${cinefilm.credits.auto.denylist:}") String path) {
        this.path = path;
    }

    /**
     * Reads the file now.
     *
     * @return the parsed file, or empty when the path is empty, the file cannot be read as UTF-8 or it is malformed
     */
    public Optional<Parsed> read() {
        if (path.isBlank()) {
            return Optional.empty();
        }
        try {
            return CreditsDenyFile.parse(Files.readAllLines(Path.of(path)));
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }
}
