package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a journey file as strictly as the contract says, with {@link StrictMapper}: a name no record has (at any
 * depth), a repeated name, trailing content and a scalar that is not already of the target's kind are refused.
 * Malformed JSON, an empty body and a body that is not an object are the same refusal.
 */
public final class JourneyFileReader {

    private final JsonMapper strict;

    /** Creates the reader from the application's mapper (the mapper itself is not changed). */
    public JourneyFileReader(JsonMapper mapper) {
        this.strict = StrictMapper.of(mapper);
    }

    /** The file in {@code body}; {@link BadRequestException} for a null body or anything that does not read. */
    public JourneyFile read(String body) {
        if (body == null) {
            throw new BadRequestException(JourneyFileRules.INVALID);
        }
        JourneyFile file;
        try {
            file = strict.readValue(body, JourneyFile.class);
        } catch (JacksonException e) {
            throw new BadRequestException(JourneyFileRules.INVALID);
        }
        if (file == null) {
            throw new BadRequestException(JourneyFileRules.INVALID);
        }
        return file;
    }
}
