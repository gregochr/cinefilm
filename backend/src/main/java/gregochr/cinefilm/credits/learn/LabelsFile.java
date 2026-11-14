package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the {@code labels-<date>.json} that the labels tool writes ({@link LabelsJob}): a JSON array with one
 * {@link LabelRecord} per media file. The measurement tool uses it only to find each film's side and whether it is
 * excluded.
 */
final class LabelsFile {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LabelsFile() {
    }

    /**
     * Reads the file.
     *
     * @param file the labels file
     * @return the labels by media file id, in id order
     * @throws IOException when the file cannot be read
     * @throws tools.jackson.core.JacksonException when it is not a JSON array of labels
     * @throws IllegalArgumentException when it holds a null entry or a media file twice
     */
    static Map<Long, LabelRecord> read(Path file) throws IOException {
        List<LabelRecord> records = MAPPER.readValue(Files.readAllBytes(file), new TypeReference<List<LabelRecord>>() {
        });
        if (records == null) {
            throw new IllegalArgumentException("labels file is not a JSON array");
        }
        Map<Long, LabelRecord> byId = new TreeMap<>();
        for (LabelRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("labels file has a null entry");
            }
            if (byId.put(record.mediaFileId(), record) != null) {
                throw new IllegalArgumentException("labels file repeats a media file");
            }
        }
        return byId;
    }
}
