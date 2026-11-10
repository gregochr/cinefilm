package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.config.FieldError;
import gregochr.cinefilm.config.FieldErrorsException;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link EditionRules} against the shared table of cases ({@code shared/edition-validation-cases.json}), which the
 * panel's {@code editionRules.test.js} reads too: the wording and the order of the errors must match on both sides.
 */
class EditionRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Case(String name, List<Long> fileIds, SaveRequest request, List<FieldError> errors) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() throws IOException {
        try (InputStream in = EditionRulesTest.class.getResourceAsStream("/shared/edition-validation-cases.json")) {
            JsonNode root = JSON.readTree(in);
            List<Case> cases = new ArrayList<>();
            for (JsonNode node : root.get("cases")) {
                List<Long> ids = new ArrayList<>();
                node.get("fileIds").forEach(id -> ids.add(id.asLong()));
                List<FieldError> errors = new ArrayList<>();
                node.get("errors").forEach(e -> errors.add(new FieldError(e.get("field").asString(),
                    e.get("message").asString())));
                cases.add(new Case(node.get("name").asString(),
                    ids, JSON.treeToValue(node.get("request"), SaveRequest.class), errors));
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void sharedCase(Case c) {
        assertThat(EditionRules.validate(c.request(), c.fileIds())).containsExactlyElementsOf(c.errors());
    }

    @Test
    void theTableHoldsEveryRule() throws IOException {
        List<String> messages = cases().flatMap(c -> c.errors().stream()).map(FieldError::message).toList();

        assertThat(messages).contains(EditionRules.UNPLACED, EditionRules.TWICE, EditionRules.EMPTY_EDITION,
            EditionRules.NAME_BLANK, EditionRules.NAME_LONG, EditionRules.PART_RANGE, EditionRules.SINGLE_MANY,
            EditionRules.PART_ONE, EditionRules.NO_EDITIONS, EditionRules.NO_DEFAULT);
        assertThat(messages).anyMatch(m -> m.startsWith("Another edition is already called "));
        assertThat(messages).anyMatch(m -> m.startsWith("Two files are both part "));
        assertThat(messages).anyMatch(m -> m.contains(" is missing.")).anyMatch(m -> m.contains(" are missing."));
    }

    @Test
    void nullListsCountAsEmpty() {
        assertThat(EditionRules.validate(new SaveRequest(null, null), List.of(1L)))
            .containsExactly(new FieldError("files", EditionRules.UNPLACED),
                new FieldError("editions", EditionRules.NO_EDITIONS));
    }

    @Test
    void aNullFileListIsAnEmptyEdition() {
        SaveRequest request = new SaveRequest(List.of(new EditionRequest(5L, "Original", true, null)), List.of());

        assertThat(EditionRules.validate(request, List.of()))
            .containsExactly(new FieldError("editions[0].files", EditionRules.EMPTY_EDITION));
    }

    @Test
    void theExceptionCarriesTheFirstMessageAndAllErrors() {
        List<FieldError> errors = List.of(new FieldError("a", "First"), new FieldError("b", "Second"));

        FieldErrorsException e = new FieldErrorsException(errors);

        assertThat(e).hasMessage("First");
        assertThat(e.errors()).containsExactlyElementsOf(errors);
    }
}
