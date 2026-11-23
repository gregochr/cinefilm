package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyFileSamples.example;
import static gregochr.cinefilm.journey.JourneyFileSamples.firstModem;
import static gregochr.cinefilm.journey.JourneyFileSamples.firstSample;
import static gregochr.cinefilm.journey.JourneyFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
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

/** The strict reading of a journey file: what the reader takes and every kind of thing it refuses. */
class JourneyFileReaderTest {

    private static final Function<ObjectNode, ObjectNode> FILE = root -> root;
    private static final Function<ObjectNode, ObjectNode> DEVICE = root -> (ObjectNode) root.get("device");

    private final JourneyFileReader reader = new JourneyFileReader(JourneyFileSamples.JSON);

    /** A mapper as forgiving as Spring Boot's: unknown names ignored, scalars coerced. */
    private static JsonMapper forgiving() {
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    }

    private void assertRefused(String body) {
        assertThatThrownBy(() -> reader.read(body)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");
    }

    static Stream<Arguments> unknownNames() {
        return Stream.of(
            Arguments.of("at the top", FILE),
            Arguments.of("in the device", DEVICE),
            Arguments.of("in a sample", (Function<ObjectNode, ObjectNode>) JourneyFileSamples::firstSample),
            Arguments.of("in a modem", (Function<ObjectNode, ObjectNode>) JourneyFileSamples::firstModem));
    }

    @Test
    void theExampleReadsWithEveryValueOfItsFirstRound() {
        JourneyFile file = reader.read(text(example()));

        assertThat(file.runStartedMs()).isEqualTo(1_790_000_000_000L);
        assertThat(file.device().model()).isEqualTo("Testbook1,1");
        assertThat(file.samples()).hasSize(6);
        assertThat(file.samples().getFirst()).hasNoNullFieldsOrProperties();
        assertThat(file.samples().getFirst().modems().getFirst()).hasNoNullFieldsOrProperties();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownNames")
    void aNameTheRecordsDoNotHaveIsRefused(String where, Function<ObjectNode, ObjectNode> owner) {
        ObjectNode tree = example();
        owner.apply(tree).put("macAddress", "00:00:00:00:00:00");

        assertRefused(text(tree));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownNames")
    void aNameTheRecordsDoNotHaveIsRefusedEvenByAMapperThatIgnoresThem(String where,
            Function<ObjectNode, ObjectNode> owner) {
        ObjectNode tree = example();
        owner.apply(tree).put("macAddress", "00:00:00:00:00:00");
        String body = text(tree);

        assertThat(forgiving().readValue(body, JourneyFile.class)).isNotNull();
        assertThatThrownBy(() -> new JourneyFileReader(forgiving()).read(body))
            .isExactlyInstanceOf(BadRequestException.class).hasMessage("Invalid journey file");
    }

    @Test
    void aNumberWrittenAsTextIsRefusedForAWholeNumberAndForADecimal() {
        ObjectNode whole = example();
        firstSample(whole).put("round", "1");
        ObjectNode decimal = example();
        firstSample(decimal).put("latitude", "12.5");
        ObjectNode big = example();
        big.put("runStartedMs", "1790000000000");

        assertRefused(text(whole));
        assertRefused(text(decimal));
        assertRefused(text(big));
    }

    @Test
    void aDecimalWhereAWholeNumberBelongsIsRefusedEvenWhenItIsAWholeValue() {
        ObjectNode fraction = example();
        firstSample(fraction).put("satellites", 9.5);
        ObjectNode whole = example();
        firstSample(whole).put("satellites", 9.0);
        ObjectNode device = example();
        ((ObjectNode) device.get("device")).put("cores", 8.0);

        assertRefused(text(fraction));
        assertRefused(text(whole));
        assertRefused(text(device));
    }

    @Test
    void aBooleanOrATextWhereANumberBelongsIsRefused() {
        ObjectNode bool = example();
        firstSample(bool).put("satellites", true);
        ObjectNode word = example();
        firstSample(word).put("satellites", "many");
        ObjectNode empty = example();
        firstSample(empty).put("satellites", "");
        ObjectNode boolDecimal = example();
        firstSample(boolDecimal).put("latitude", false);
        ObjectNode wordDecimal = example();
        firstSample(wordDecimal).put("latitude", "north");
        ObjectNode modem = example();
        firstModem(modem).put("rssi", true);

        for (ObjectNode tree : new ObjectNode[] {bool, word, empty, boolDecimal, wordDecimal, modem}) {
            assertRefused(text(tree));
        }
    }

    @Test
    void aNumberOrABooleanWhereATextBelongsIsRefused() {
        ObjectNode number = example();
        firstSample(number).put("trainUnit", 12);
        ObjectNode decimal = example();
        firstModem(decimal).put("operatorId", 12.5);
        ObjectNode bool = example();
        bool.put("sourceFile", true);
        ObjectNode device = example();
        ((ObjectNode) device.get("device")).put("model", 11);

        for (ObjectNode tree : new ObjectNode[] {number, decimal, bool, device}) {
            assertRefused(text(tree));
        }
    }

    @Test
    void aWholeValueWhereADecimalBelongsIsTakenAsThatDecimal() {
        ObjectNode tree = example();
        firstSample(tree).put("latitude", 12);

        assertThat(reader.read(text(tree)).samples().getFirst().latitude()).isEqualTo(12.0);
    }

    @Test
    void aWholeNumberTooBigForItsTypeIsRefused() {
        assertRefused(text(example()).replace("17179869184", "170000000000000000000000000000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{\"runStartedMs\":", "not json", "[]", "[{}]", "\"text\"", "12", "true",
        "null", "{} {}", "{\"samples\":[]} x", "{\"runStartedMs\":1,\"runStartedMs\":2}"})
    void aBodyThatIsNotOneJsonObjectOfTheRightShapeIsRefused(String body) {
        assertRefused(body);
    }

    @Test
    void aNullBodyIsRefused() {
        assertRefused(null);
    }

    @Test
    void anEmptyObjectReadsAsAFileWithEverythingNullForTheRulesToRefuse() {
        assertThat(reader.read("{}")).isEqualTo(new JourneyFile(null, null, null, null, null, null, null));
    }
}
