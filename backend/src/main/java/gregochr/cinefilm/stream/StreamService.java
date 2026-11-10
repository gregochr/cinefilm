package gregochr.cinefilm.stream;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the file to stream for an edition. The path always comes from the database and is resolved under the
 * library root; a path that escapes it (a {@code ..} segment, an absolute path, a symlink out) is never served.
 */
@Service
public class StreamService {

    private final EditionRepository editions;
    private final LibraryRoot root;

    /** Creates the service over the edition repository and the library root. */
    public StreamService(EditionRepository editions, LibraryRoot root) {
        this.editions = editions;
        this.root = root;
    }

    /**
     * The readable original file of the edition; 404 if the edition, its file or the file on disk is missing. An
     * edition split across several files has no single file to stream, so it answers 409: it plays only as one
     * continuous adaptive-streaming session.
     */
    @Transactional(readOnly = true)
    public Path originalPath(Long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        MediaFile original = edition.originalFile();
        if (original == null) {
            throw new NotFoundException("Edition has no original file");
        }
        if (edition.partCount() > 1) {
            throw new ConflictException("This edition is split across several files and cannot be direct-played");
        }
        if (original.getPlayability() != Playability.PLAYABLE) {
            throw new NotFoundException("Media file is not available");
        }
        return root.resolveFile(original.getPath()).filter(Files::isReadable)
            .orElseThrow(() -> new NotFoundException("Media file is not available"));
    }
}
