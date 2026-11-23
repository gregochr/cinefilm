package gregochr.cinefilm.journey;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * The strict mapper the journey package's files are read with. It starts from the application's mapper and turns on
 * what Spring's setup turns off or allows: a name no record has (at any depth), a trailing token and a repeated name
 * are refused, and so is every scalar that is not already of the target's kind (a number written as text, a decimal
 * where a whole number belongs, a boolean or a text for a number, a number or a boolean for a text).
 */
final class StrictMapper {

    private StrictMapper() {
    }

    /** A strict copy of {@code mapper}; the mapper itself is not changed. */
    static JsonMapper of(JsonMapper mapper) {
        return mapper.rebuild()
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
}
