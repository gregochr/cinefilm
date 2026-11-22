package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One item of the candidates list the server answers ({@code GET /api/admin/credits/auto-candidates}).
 *
 * @param editionId the edition
 * @param filmId the film
 * @param mediaFileId the last part's file
 * @param path the last part's file, relative to the media root (library data: never written to any output)
 * @param offsetMs where the credits start inside that file (file time)
 * @param kind {@code CRAWL} or {@code CARD}
 * @param startMs the detected start, in edition time
 * @param markerMs the marker the pass would write, in edition time
 * @param owner whether the owner has set a marker of their own on the edition
 */
record AutoCheckCandidate(long editionId, long filmId, long mediaFileId, String path, long offsetMs, String kind,
                          long startMs, long markerMs, boolean owner) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Reads the list.
     *
     * @param file the JSON file the owner fetched
     * @return the candidates, in file order
     * @throws IOException when the file cannot be read
     * @throws tools.jackson.core.JacksonException when it is not a JSON array of candidates
     */
    static List<AutoCheckCandidate> read(Path file) throws IOException {
        return MAPPER.readValue(Files.readString(file), new TypeReference<List<AutoCheckCandidate>>() {
        });
    }

    /** Where this candidate's file time begins inside the edition: edition time minus this is file time. */
    long fileOrigin() {
        return startMs - offsetMs;
    }
}
