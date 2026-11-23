package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a train's file as strictly as the contract says, with {@link StrictMapper}: a name no record has (at any
 * depth), a repeated name, trailing content and a value that is not text are refused. Malformed JSON, an empty body
 * and a body that is not an object are the same refusal.
 */
public final class TrainFileReader {

    private final JsonMapper strict;

    /** Creates the reader from the application's mapper (the mapper itself is not changed). */
    public TrainFileReader(JsonMapper mapper) {
        this.strict = StrictMapper.of(mapper);
    }

    /** The file in {@code body}; {@link BadRequestException} for a null body or anything that does not read. */
    public TrainFile read(String body) {
        if (body == null) {
            throw new BadRequestException(TrainFileRules.INVALID);
        }
        TrainFile file;
        try {
            file = strict.readValue(body, TrainFile.class);
        } catch (JacksonException e) {
            throw new BadRequestException(TrainFileRules.INVALID);
        }
        if (file == null) {
            throw new BadRequestException(TrainFileRules.INVALID);
        }
        return file;
    }
}
