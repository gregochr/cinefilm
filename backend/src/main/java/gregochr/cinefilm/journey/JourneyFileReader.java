package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Reads a journey file as strictly as the contract says. It starts from the application's mapper and turns on what
 * Spring's setup turns off or allows: a name no record has (at any depth), a trailing token and a repeated name are
 * refused, and so is every scalar that is not already of the target's kind (a number written as text, a decimal
 * where a whole number belongs, a boolean or a text for a number, a number or a boolean for a text). Malformed JSON,
 * an empty body and a body that is not an object are the same refusal.
 */
public final class JourneyFileReader {

    private final JsonMapper strict;

    /** Creates the reader from the application's mapper (the mapper itself is not changed). */
    public JourneyFileReader(JsonMapper mapper) {
        this.strict = mapper.rebuild()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
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
