package gregochr.cinefilm.stream;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the file to stream for an edition. The path always comes from the database, never from a request. */
@Service
public class StreamService {

    private final EditionRepository editions;

    public StreamService(EditionRepository editions) {
        this.editions = editions;
    }

    /** The readable original file of the edition; 404 if the edition, its file or the file on disk is missing. */
    @Transactional(readOnly = true)
    public Path originalPath(Long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        MediaFile original = edition.originalFile();
        if (original == null) {
            throw new NotFoundException("Edition has no original file");
        }
        try {
            Path path = Path.of(original.getPath());
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new NotFoundException("Media file is not available");
            }
            return path;
        } catch (InvalidPathException e) {
            throw new NotFoundException("Media file is not available");
        }
    }
}
