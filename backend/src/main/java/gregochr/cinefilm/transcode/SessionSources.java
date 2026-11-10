package gregochr.cinefilm.transcode;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.PartsMatch;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads, in one short read-only transaction, what a session needs from the database: every playable original file
 * (part) of an edition (each path resolved under the library root, never taken from a request) and its audio
 * streams.
 */
@Component
public class SessionSources {

    /**
     * An audio stream of the file.
     *
     * @param id the {@code audio_stream} row id the API takes
     * @param position the rank of its file stream index among all the file's audio stream indexes (ascending), which
     *     is what {@code -map 0:a:N} counts; database id order and list order do not matter
     * @param isDefault true for the stream flagged as the default
     * @param language the stream's language name, or null
     */
    public record AudioChoice(long id, int position, boolean isDefault, String language) {

        /** A stream whose language is not known. */
        public AudioChoice(long id, int position, boolean isDefault) {
            this(id, position, isDefault, null);
        }
    }

    /**
     * One playable original file of an edition: a whole film, or one part of a split one.
     *
     * @param file the media file row (basic columns only are read from it afterwards)
     * @param path the file on disk, absolute
     * @param audio the file's audio streams in order
     * @param timelineMs how long the file is on the edition's timeline: its video duration for a part of a split
     *     film (a part starts where the previous part's picture ends), the container duration for a single file;
     *     0 when unknown
     */
    public record Part(MediaFile file, Path path, List<AudioChoice> audio, long timelineMs) {
    }

    /**
     * An edition's playable original.
     *
     * @param editionId the edition
     * @param filmTitle the film's title
     * @param parts the files in play order; one for a film in one piece
     * @param partsMatch true when the parts are alike enough to be joined without re-encoding (see
     *     {@code PartsMatch}); true for a single file
     */
    public record Source(long editionId, String filmTitle, List<Part> parts, boolean partsMatch) {

        /** A film in one piece. */
        public Source(long editionId, String filmTitle, MediaFile file, Path path, List<AudioChoice> audio) {
            this(editionId, filmTitle,
                List.of(new Part(file, path, audio, file.getDurationMs() == null ? 0 : file.getDurationMs())), true);
        }

        /** The first (or only) file. */
        public MediaFile file() {
            return parts.get(0).file();
        }

        /** The first (or only) file on disk. */
        public Path path() {
            return parts.get(0).path();
        }

        /** The first (or only) file's audio streams. */
        public List<AudioChoice> audio() {
            return parts.get(0).audio();
        }
    }

    private final EditionRepository editions;
    private final LibraryRoot root;

    /** Creates the reader over the edition repository and the library root. */
    public SessionSources(EditionRepository editions, LibraryRoot root) {
        this.editions = editions;
        this.root = root;
    }

    /**
     * The playable originals of an edition, in play order.
     *
     * @param editionId the edition id
     * @return the source
     * @throws NotFoundException when the edition, any of its original files, or a file on disk is missing or not
     *     playable
     */
    @Transactional(readOnly = true)
    public Source find(long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        List<MediaFile> originals = edition.originalParts();
        if (originals.isEmpty()) {
            throw new NotFoundException("Edition has no original file");
        }
        boolean multiPart = originals.size() > 1;
        List<Part> parts = new ArrayList<>();
        for (MediaFile original : originals) {
            if (original.getPlayability() != Playability.PLAYABLE) {
                throw new NotFoundException("Media file is not available");
            }
            Path path = root.resolveFile(original.getPath()).filter(Files::isReadable)
                .orElseThrow(() -> new NotFoundException("Media file is not available"));
            Long duration = KeyframeIndexService.indexDurationMs(original, multiPart);
            parts.add(new Part(original, path, audio(original), duration == null ? 0 : duration));
        }
        return new Source(editionId, edition.getFilm().getTitle(), List.copyOf(parts),
            PartsMatch.check(originals).matches());
    }

    private static List<AudioChoice> audio(MediaFile file) {
        List<AudioStream> streams = file.getAudioStreams();
        List<Integer> fileOrder = streams.stream().map(AudioStream::getIndex).sorted().toList();
        return streams.stream()
            .map(a -> new AudioChoice(a.getId(), fileOrder.indexOf(a.getIndex()), a.isDefault(), a.getLanguage()))
            .toList();
    }
}
