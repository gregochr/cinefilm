package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.TrainFileSamples.example;
import static gregochr.cinefilm.journey.TrainFileSamples.firstCall;
import static gregochr.cinefilm.journey.TrainFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.TrainFile.Call;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The strict reading of a train's file: what the reader takes and every kind of thing it refuses. */
class TrainFileReaderTest {

    private static final Function<ObjectNode, ObjectNode> FILE = root -> root;
    private static final Function<ObjectNode, ObjectNode> CALL = TrainFileSamples::firstCall;

    private final TrainFileReader reader = new TrainFileReader(TrainFileSamples.JSON);

    /** A mapper as forgiving as Spring Boot's: unknown names ignored, scalars coerced. */
    private static JsonMapper forgiving() {
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    }

    private void assertRefused(String body) {
        assertThatThrownBy(() -> reader.read(body)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid train file");
    }

    static Stream<Arguments> owners() {
        return Stream.of(Arguments.of("at the top", FILE), Arguments.of("in a call", CALL));
    }

    @Test
    void theExampleReadsIntoTheRecordsValueForValue() {
        TrainFile file = reader.read(text(example()));

        assertThat(file.uid()).isEqualTo("X12345");
        assertThat(file.date()).isEqualTo("2030-03-04");
        assertThat(file.headcode()).isEqualTo("9Z99");
        assertThat(file.operator()).isEqualTo("Example Rail");
        assertThat(file.source()).isEqualTo("https://example.invalid/run");
        assertThat(file.calls()).hasSize(8);
        assertThat(file.calls().getFirst())
            .isEqualTo(new Call("Alpha Junction", null, "22:00", null, null, "22:01:30", null));
        assertThat(file.calls().get(2))
            .isEqualTo(new Call("Gamma Loop", null, null, "22:50:30", null, null, "22:53"));
        assertThat(file.calls().get(6))
            .isEqualTo(new Call("Eta Works", null, null, "00:20", null, null, "00:22:30"));
        assertThat(file.calls().get(1))
            .isEqualTo(new Call("Beta Parkway", "22:30", "22:32", null, "22:31:30", "22:34", null));
        assertThat(file.calls().get(3))
            .isEqualTo(new Call("Delta Cross", null, null, "23:10", "23:12", "23:13:30", null));
        assertThat(file.calls().get(4))
            .isEqualTo(new Call("Epsilon Green", "23:40", "23:41", null, null, null, null));
        assertThat(file.calls().get(5))
            .isEqualTo(new Call("Zeta Sidings", null, null, "23:59:30", null, null, "00:02"));
        assertThat(file.calls().getLast())
            .isEqualTo(new Call("Theta Central", "00:45", null, null, "00:47:30", null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("owners")
    void aNameTheRecordsDoNotHaveIsRefused(String where, Function<ObjectNode, ObjectNode> owner) {
        ObjectNode tree = example();
        owner.apply(tree).put("unit", "Unit-001");

        assertRefused(text(tree));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("owners")
    void aNameTheRecordsDoNotHaveIsRefusedEvenByAMapperThatIgnoresThem(String where,
            Function<ObjectNode, ObjectNode> owner) {
        ObjectNode tree = example();
        owner.apply(tree).put("unit", "Unit-001");
        String body = text(tree);

        assertThat(forgiving().readValue(body, TrainFile.class)).isNotNull();
        assertThatThrownBy(() -> new TrainFileReader(forgiving()).read(body))
            .isExactlyInstanceOf(BadRequestException.class).hasMessage("Invalid train file");
    }

    @Test
    void aNumberWhereTextBelongsIsRefused() {
        ObjectNode top = example();
        top.put("uid", 12345);
        ObjectNode call = example();
        firstCall(call).put("plannedDeparture", 2200);
        ObjectNode decimal = example();
        firstCall(decimal).put("location", 12.5);

        for (ObjectNode tree : new ObjectNode[] {top, call, decimal}) {
            assertRefused(text(tree));
        }
    }

    @Test
    void aBooleanWhereTextBelongsIsRefused() {
        ObjectNode top = example();
        top.put("operator", true);
        ObjectNode call = example();
        firstCall(call).put("actualDeparture", false);

        assertRefused(text(top));
        assertRefused(text(call));
    }

    @Test
    void aListOrAnObjectWhereTextBelongsIsRefused() {
        ObjectNode list = example();
        list.putArray("headcode").add("9Z99");
        ObjectNode object = example();
        firstCall(object).putObject("location").put("name", "Alpha Junction");
        ObjectNode calls = example();
        calls.put("calls", "none");
        ObjectNode call = example();
        TrainFileSamples.calls(call).set(0, call.get("uid"));

        for (ObjectNode tree : new ObjectNode[] {list, object, calls, call}) {
            assertRefused(text(tree));
        }
    }

    @Test
    void aRepeatedNameIsRefused() {
        assertRefused("{\"uid\":\"X12345\",\"uid\":\"X12346\"}");
        assertRefused(text(example()).replace("\"location\":\"Alpha Junction\"",
            "\"location\":\"Alpha Junction\",\"location\":\"Beta Parkway\""));
    }

    @Test
    void contentAfterTheObjectIsRefused() {
        assertRefused(text(example()) + " x");
        assertRefused(text(example()) + text(example()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{\"uid\":", "not json", "[]", "[{}]", "\"text\"", "12", "true", "null"})
    void aBodyThatIsNotOneJsonObjectIsRefused(String body) {
        assertRefused(body);
    }

    @Test
    void aNullBodyIsRefused() {
        assertRefused(null);
    }

    @Test
    void anEmptyObjectReadsAsAFileWithEverythingNullForTheRulesToRefuse() {
        assertThat(reader.read("{}")).isEqualTo(new TrainFile(null, null, null, null, null, null));
    }

    @Test
    void aCallWithNothingInItReadsAsNullsForTheRulesToRefuse() {
        TrainFile file = reader.read("{\"calls\":[{}]}");

        assertThat(file.calls()).isEqualTo(List.of(new Call(null, null, null, null, null, null, null)));
    }
}
