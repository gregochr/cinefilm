package gregochr.cinefilm.transcode;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads, in one short read-only transaction, what a session needs from the database: the playable original file of
 * an edition (its path resolved under the library root, never taken from a request) and its audio streams.
 */
@Component
public class SessionSources {

    /**
     * An audio stream of the file.
     *
     * @param id the {@code audio_stream} row id the API takes
     * @param position its position among the file's audio streams, which is what {@code -map 0:a:N} counts
     * @param isDefault true for the stream flagged as the default
     */
    public record AudioChoice(long id, int position, boolean isDefault) {
    }

    /**
     * An edition's playable original.
     *
     * @param editionId the edition
     * @param filmTitle the film's title
     * @param file the media file row (basic columns only are read from it afterwards)
     * @param path the file on disk, absolute
     * @param audio the file's audio streams in order
     */
    public record Source(long editionId, String filmTitle, MediaFile file, Path path, List<AudioChoice> audio) {
    }

    private final EditionRepository editions;
    private final LibraryRoot root;

    /** Creates the reader over the edition repository and the library root. */
    public SessionSources(EditionRepository editions, LibraryRoot root) {
        this.editions = editions;
        this.root = root;
    }

    /**
     * The playable original of an edition.
     *
     * @param editionId the edition id
     * @return the source
     * @throws NotFoundException when the edition, its original file, or the file on disk is missing or not playable
     */
    @Transactional(readOnly = true)
    public Source find(long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        MediaFile original = edition.originalFile();
        if (original == null) {
            throw new NotFoundException("Edition has no original file");
        }
        if (original.getPlayability() != Playability.PLAYABLE) {
            throw new NotFoundException("Media file is not available");
        }
        Path path = root.resolveFile(original.getPath()).filter(Files::isReadable)
            .orElseThrow(() -> new NotFoundException("Media file is not available"));
        List<AudioStream> streams = original.getAudioStreams();
        List<AudioChoice> audio = IntStream.range(0, streams.size())
            .mapToObj(i -> new AudioChoice(streams.get(i).getId(), i, streams.get(i).isDefault())).toList();
        return new Source(editionId, edition.getFilm().getTitle(), original, path, audio);
    }
}
