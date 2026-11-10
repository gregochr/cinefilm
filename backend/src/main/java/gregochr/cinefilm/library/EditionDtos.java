package gregochr.cinefilm.library;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** The Edit editions API shapes: what the panel loads and what it saves. */
public final class EditionDtos {

    private EditionDtos() {
    }

    /**
     * Everything the Edit editions panel needs about one film.
     *
     * @param files every original file of the film, with the facts the panel lists
     * @param editions the film's editions as they are now, with the default marked
     * @param suggestion how the scanner would arrange the files, and why
     */
    public record EditionsView(List<EditionFileDto> files, List<EditionRefDto> editions, SuggestionDto suggestion) {
    }

    /**
     * One file of the film.
     *
     * @param id media file id
     * @param fileName the file's name (no folders)
     * @param durationMs the container duration, or null when unknown
     * @param width picture width, or null
     * @param height picture height, or null
     * @param videoCodec the video codec, or null
     * @param sizeBytes the size on disk, or null
     * @param presence PRESENT or MISSING
     * @param editionId the edition it belongs to now
     * @param partNumber its part number now (1 for a single file)
     */
    public record EditionFileDto(Long id, String fileName, Long durationMs, Integer width, Integer height,
                                 String videoCodec, Long sizeBytes, String presence, Long editionId,
                                 int partNumber) {
    }

    /**
     * An edition as it is now.
     *
     * @param id the edition id
     * @param name its name
     * @param isDefault true for the edition the film opens on (sent as {@code default})
     */
    public record EditionRefDto(Long id, String name, @JsonProperty("default") boolean isDefault) {
    }

    /**
     * The suggested arrangement, already applied to the panel's controls.
     *
     * @param reason one line saying why, or empty when there is nothing to say
     * @param editions the suggested editions with their files
     * @param notThisFilm files suggested as not this film
     * @param defaultName the name of the edition suggested as the default
     */
    public record SuggestionDto(String reason, List<ArrangedEdition> editions, List<Long> notThisFilm,
                                String defaultName) {
    }

    /**
     * A suggested edition.
     *
     * @param id the existing edition it continues, or null for a new one
     * @param name its name
     * @param files its files in part order
     */
    public record ArrangedEdition(Long id, String name, List<ArrangedFile> files) {
    }

    /**
     * A file in an edition.
     *
     * @param fileId the media file id
     * @param part the part number, or null for a single file
     */
    public record ArrangedFile(Long fileId, Integer part) {
    }

    /**
     * The body of a save.
     *
     * @param editions the editions the film ends up with
     * @param notThisFilm files that move to a film of their own
     */
    public record SaveRequest(List<EditionRequest> editions, List<Long> notThisFilm) {
    }

    /**
     * One edition in a save.
     *
     * @param id the existing edition to keep (its history stays with it), or null for a new one
     * @param name its name (1 to 32 characters)
     * @param isDefault true for the default edition (sent as {@code default})
     * @param files its files with their part numbers
     */
    public record EditionRequest(Long id, String name, @JsonProperty("default") boolean isDefault,
                                 List<FileRequest> files) {
    }

    /**
     * A file in a saved edition.
     *
     * @param fileId the media file id
     * @param part the part (1 to 8), or null for a single file
     */
    public record FileRequest(Long fileId, Integer part) {
    }

    /**
     * What a save did.
     *
     * @param filmId the film
     * @param newFilmIds the films created for the files marked Not this film, in the order of {@code notThisFilm}
     */
    public record SaveResult(Long filmId, List<Long> newFilmIds) {
    }
}
