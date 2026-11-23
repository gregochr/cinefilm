package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyFileSamples.example;
import static gregochr.cinefilm.journey.JourneyFileSamples.firstModem;
import static gregochr.cinefilm.journey.JourneyFileSamples.firstSample;
import static gregochr.cinefilm.journey.JourneyFileSamples.read;
import static gregochr.cinefilm.journey.JourneyFileSamples.samples;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.JourneyFile.DeviceInfo;
import gregochr.cinefilm.journey.JourneyFile.Modem;
import gregochr.cinefilm.journey.JourneyFile.Sample;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class JourneyFileRulesTest {

    private static final Function<ObjectNode, ObjectNode> FILE = root -> root;
    private static final Function<ObjectNode, ObjectNode> DEVICE = root -> (ObjectNode) root.get("device");
    private static final Function<ObjectNode, ObjectNode> SAMPLE = JourneyFileSamples::firstSample;
    private static final Function<ObjectNode, ObjectNode> MODEM = JourneyFileSamples::firstModem;
    private static final List<Class<?>> RECORDS = List.of(JourneyFile.class, DeviceInfo.class, Sample.class,
        Modem.class);

    /** The file as a tree is valid and read back; then it is checked and expected to be refused. */
    private static AbstractThrowableAssert<?, ? extends Throwable> validating(ObjectNode tree) {
        JourneyFile file = read(tree);
        return assertThatThrownBy(() -> JourneyFileRules.validate(file));
    }

    private static void refused(AbstractThrowableAssert<?, ? extends Throwable> thrown) {
        thrown.isExactlyInstanceOf(BadRequestException.class).hasMessage("Invalid journey file");
    }

    private static void assertValid(ObjectNode tree) {
        JourneyFile file = read(tree);
        assertThatCode(() -> JourneyFileRules.validate(file)).doesNotThrowAnyException();
    }

    /** The example cut to its first round, so a value that moves that round's number cannot break the order. */
    private static ObjectNode single() {
        ObjectNode tree = example();
        while (samples(tree).size() > 1) {
            samples(tree).remove(1);
        }
        return tree;
    }

    static Stream<Arguments> wholes() {
        return Stream.of(
            tableOf(FILE, "file", JourneyFileRules.FILE_WHOLES),
            tableOf(DEVICE, "device", JourneyFileRules.DEVICE_WHOLES),
            tableOf(SAMPLE, "sample", JourneyFileRules.SAMPLE_WHOLES),
            tableOf(MODEM, "modem", JourneyFileRules.MODEM_WHOLES))
            .flatMap(s -> s);
    }

    private static <T> Stream<Arguments> tableOf(Function<ObjectNode, ObjectNode> owner, String label,
                                                  List<JourneyFileRules.Whole<T>> table) {
        return table.stream().map(w -> Arguments.of(label, owner, w.name(), w.low(), w.high(), w.required()));
    }

    static Stream<Arguments> optionalWholes() {
        return wholes().filter(a -> !(Boolean) a.get()[5]);
    }

    static Stream<Arguments> requiredWholes() {
        return wholes().filter(a -> (Boolean) a.get()[5]);
    }

    static Stream<Arguments> decimals() {
        return JourneyFileRules.SAMPLE_DECIMALS.stream()
            .map(d -> Arguments.of(d.name(), d.low(), d.high(), d.lowOpen()));
    }

    static Stream<Arguments> texts() {
        return Stream.of(
            JourneyFileRules.FILE_TEXTS.stream().map(t -> Arguments.of("file", FILE, t.name(), t.max())),
            JourneyFileRules.DEVICE_TEXTS.stream().map(t -> Arguments.of("device", DEVICE, t.name(), t.max())),
            JourneyFileRules.SAMPLE_TEXTS.stream().map(t -> Arguments.of("sample", SAMPLE, t.name(), t.max())),
            JourneyFileRules.MODEM_TEXTS.stream().map(t -> Arguments.of("modem", MODEM, t.name(), t.max())))
            .flatMap(s -> s);
    }

    static Stream<Arguments> optionalTexts() {
        return texts().filter(a -> !"device".equals(a.get()[0]));
    }

    static Stream<Arguments> words() {
        return Stream.of(
            JourneyFileRules.FILE_WORDS.stream().map(w -> Arguments.of(FILE, w.name(), w.allowed())),
            JourneyFileRules.SAMPLE_WORDS.stream().map(w -> Arguments.of(SAMPLE, w.name(), w.allowed())))
            .flatMap(s -> s);
    }

    private static String filler(String name, int length) {
        return String.valueOf("operatorId".equals(name) ? '7' : 'a').repeat(length);
    }

    /**
     * The contract, written out from the plan's section "The import's request" and not from the code: a bound
     * changed in {@link JourneyFileRules}' tables, or here, shows in the table-equality test below.
     */
    private record WholeBound(String name, long low, long high, boolean required) {
    }

    private record DecimalBound(String name, double low, double high, boolean lowOpen) {
    }

    private record TextBound(String name, int max, boolean required) {
    }

    private static final List<WholeBound> CONTRACT_WHOLES = List.of(
        new WholeBound("runStartedMs", 1_577_836_800_000L, 4_102_444_800_000L, true),
        new WholeBound("loggerVersion", 2, 3, true),
        new WholeBound("cores", 1, 1_024, true),
        new WholeBound("threads", 1, 4_096, true),
        new WholeBound("memoryBytes", 1, 1_125_899_906_842_624L, true),
        new WholeBound("round", 1, 10_000_000, true),
        new WholeBound("at", 1_577_836_800_000L, 4_102_444_800_000L, true),
        new WholeBound("feedAt", 1_577_836_800L, 4_102_444_800L, false),
        new WholeBound("fixMode", 0, 9, false),
        new WholeBound("fixAgeS", 0, 1_000_000, false),
        new WholeBound("satellites", 0, 200, false),
        new WholeBound("online", 0, 9, false),
        new WholeBound("usersOnline", 0, 100_000, false),
        new WholeBound("usersTotal", 0, 100_000, false),
        new WholeBound("downLimit", 0, 1_000_000_000_000L, false),
        new WholeBound("upLimit", 0, 1_000_000_000_000L, false),
        new WholeBound("dataDownUsed", 0, 1_000_000_000_000_000L, false),
        new WholeBound("dataUpUsed", 0, 1_000_000_000_000_000L, false),
        new WholeBound("capLevel", 0, 1_000, false),
        new WholeBound("userClass", 0, 1_000, false),
        new WholeBound("probeMs", 0, 600_000, false),
        new WholeBound("probeCode", 100, 599, false),
        new WholeBound("batteryPercent", 0, 100, false),
        new WholeBound("lowPower", 0, 1, false),
        new WholeBound("cpuLimit", 0, 100, false),
        new WholeBound("wifiRssi", -120, -1, false),
        new WholeBound("wifiNoise", -120, -1, false),
        new WholeBound("wifiPhy", 1, 20, false),
        new WholeBound("wifiBand", 1, 20, false),
        new WholeBound("wifiWidth", 1, 20, false),
        new WholeBound("wifiChannel", 1, 233, false),
        new WholeBound("index", 0, 100_000, true),
        new WholeBound("rssi", -200, 0, false),
        new WholeBound("cellId", 0, 1_099_511_627_776L, false));

    private static final List<DecimalBound> CONTRACT_DECIMALS = List.of(
        new DecimalBound("latitude", -90, 90, false),
        new DecimalBound("longitude", -180, 180, false),
        new DecimalBound("altitudeM", -1_000, 10_000, false),
        new DecimalBound("speedMps", 0, 200, false),
        new DecimalBound("courseDeg", 0, 360, false),
        new DecimalBound("loadAvg", 0, 100_000, false),
        new DecimalBound("swapMb", 0, 10_000_000, false),
        new DecimalBound("wifiRate", 0, 100_000, true));

    private static final List<TextBound> CONTRACT_TEXTS = List.of(
        new TextBound("sourceFile", 200, false),
        new TextBound("osVersion", 40, false),
        new TextBound("model", 100, true),
        new TextBound("cpu", 100, true),
        new TextBound("trainUnit", 100, false),
        new TextBound("deviceType", 40, false),
        new TextBound("deviceSubtype", 40, false),
        new TextBound("deviceState", 40, false),
        new TextBound("linkState", 40, false),
        new TextBound("technology", 40, false),
        new TextBound("operatorId", 10, false));

    private static final Map<String, Set<String>> CONTRACT_WORDS = Map.of(
        "travelClass", Set.of("standard", "first"), "powerSource", Set.of("ac", "battery"));

    @Test
    @SuppressWarnings("unchecked")
    void theCodesTablesAreTheContractFieldByField() {
        List<WholeBound> wholes = wholes().map(a -> new WholeBound((String) a.get()[2], (Long) a.get()[3],
            (Long) a.get()[4], (Boolean) a.get()[5])).toList();
        List<DecimalBound> decimals = JourneyFileRules.SAMPLE_DECIMALS.stream()
            .map(d -> new DecimalBound(d.name(), d.low(), d.high(), d.lowOpen())).toList();
        List<TextBound> texts = texts().map(a -> new TextBound((String) a.get()[2], (Integer) a.get()[3],
            "device".equals(a.get()[0]))).toList();
        Map<String, Set<String>> words = words().collect(Collectors.toMap(a -> (String) a.get()[1],
            a -> (Set<String>) a.get()[2]));

        assertThat(wholes).containsExactlyInAnyOrderElementsOf(CONTRACT_WHOLES);
        assertThat(decimals).containsExactlyInAnyOrderElementsOf(CONTRACT_DECIMALS);
        assertThat(texts).containsExactlyInAnyOrderElementsOf(CONTRACT_TEXTS);
        assertThat(words).isEqualTo(CONTRACT_WORDS);
        assertThat(CONTRACT_WHOLES).hasSize(34);
        assertThat(CONTRACT_DECIMALS).hasSize(8);
        assertThat(CONTRACT_TEXTS).hasSize(11);
        assertThat(JourneyFileRules.MAX_SAMPLES).isEqualTo(2_000);
        assertThat(JourneyFileRules.MAX_MODEMS).isEqualTo(16);
    }

    @Test
    void aTextIsRequiredExactlyWhereTheContractSaysAndOnlyTheDeviceTextsAre() {
        for (TextBound text : CONTRACT_TEXTS) {
            Function<ObjectNode, ObjectNode> owner = switch (text.name()) {
                case "sourceFile", "osVersion" -> FILE;
                case "model", "cpu" -> DEVICE;
                case "trainUnit" -> SAMPLE;
                default -> MODEM;
            };
            ObjectNode nulled = example();
            owner.apply(nulled).putNull(text.name());
            if (text.required()) {
                refused(validating(nulled));
            } else {
                assertValid(nulled);
            }
        }
    }

    @Test
    void theExampleIsValid() {
        assertValid(example());
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("wholes")
    void aWholeNumberTakesItsLowestAndHighestAndOneBeyondEachIsRefused(String label,
            Function<ObjectNode, ObjectNode> owner, String name, long low, long high, boolean required) {
        ObjectNode atLow = single();
        owner.apply(atLow).put(name, low);
        assertValid(atLow);
        ObjectNode atHigh = single();
        owner.apply(atHigh).put(name, high);
        assertValid(atHigh);
        ObjectNode belowLow = single();
        owner.apply(belowLow).put(name, low - 1);
        refused(validating(belowLow));
        ObjectNode aboveHigh = single();
        owner.apply(aboveHigh).put(name, high + 1);
        refused(validating(aboveHigh));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decimals")
    void aDecimalTakesItsLowestAndHighestAndOneBeyondEachIsRefused(String name, double low, double high,
                                                                    boolean lowOpen) {
        ObjectNode atLow = single();
        firstSample(atLow).put(name, lowOpen ? Math.nextUp(low) : low);
        assertValid(atLow);
        ObjectNode atHigh = single();
        firstSample(atHigh).put(name, high);
        assertValid(atHigh);
        ObjectNode belowLow = single();
        firstSample(belowLow).put(name, lowOpen ? low : Math.nextDown(low));
        refused(validating(belowLow));
        ObjectNode aboveHigh = single();
        firstSample(aboveHigh).put(name, Math.nextUp(high));
        refused(validating(aboveHigh));
    }

    @Test
    void everyFieldOfTheRecordsIsInATableSoNoneIsLeftWithoutARange() {
        List<RecordComponent> components = RECORDS.stream().flatMap(r -> Stream.of(r.getRecordComponents())).toList();
        Set<String> whole = namesOf(components, Integer.class, Long.class);
        Set<String> decimal = namesOf(components, Double.class);
        Set<String> text = namesOf(components, String.class);

        assertThat(wholes().map(a -> (String) a.get()[2]).toList()).containsExactlyInAnyOrderElementsOf(whole);
        assertThat(JourneyFileRules.SAMPLE_DECIMALS.stream().map(JourneyFileRules.Decimal::name).toList())
            .containsExactlyInAnyOrderElementsOf(decimal);
        assertThat(Stream.concat(texts().map(a -> (String) a.get()[2]), words().map(a -> (String) a.get()[1])).toList())
            .containsExactlyInAnyOrderElementsOf(text);
        assertThat(whole).hasSize(34);
        assertThat(decimal).hasSize(8);
        assertThat(text).hasSize(13);
    }

    private static Set<String> namesOf(List<RecordComponent> components, Class<?>... types) {
        Set<Class<?>> wanted = Set.of(types);
        return components.stream().filter(c -> wanted.contains(c.getType())).map(RecordComponent::getName)
            .collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("optionalWholes")
    void anOptionalWholeNumberMayBeNullOrMissing(String label, Function<ObjectNode, ObjectNode> owner, String name,
                                                 long low, long high, boolean required) {
        ObjectNode nulled = example();
        owner.apply(nulled).putNull(name);
        assertValid(nulled);
        ObjectNode missing = example();
        owner.apply(missing).remove(name);
        assertValid(missing);
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("requiredWholes")
    void aRequiredWholeNumberMayNotBeNullOrMissing(String label, Function<ObjectNode, ObjectNode> owner, String name,
                                                   long low, long high, boolean required) {
        ObjectNode nulled = example();
        owner.apply(nulled).putNull(name);
        refused(validating(nulled));
        ObjectNode missing = example();
        owner.apply(missing).remove(name);
        refused(validating(missing));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decimals")
    void aDecimalMayBeNullOrMissing(String name, double low, double high, boolean lowOpen) {
        ObjectNode nulled = example();
        firstSample(nulled).putNull(name);
        assertValid(nulled);
        ObjectNode missing = example();
        firstSample(missing).remove(name);
        assertValid(missing);
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("optionalTexts")
    void anOptionalTextMayBeNullOrMissing(String label, Function<ObjectNode, ObjectNode> owner, String name,
                                          int max) {
        ObjectNode nulled = example();
        owner.apply(nulled).putNull(name);
        assertValid(nulled);
        ObjectNode missing = example();
        owner.apply(missing).remove(name);
        assertValid(missing);
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("texts")
    void aTextTakesItsLongestAndOneCharacterMoreIsRefused(String label, Function<ObjectNode, ObjectNode> owner,
                                                          String name, int max) {
        ObjectNode longest = example();
        owner.apply(longest).put(name, filler(name, max));
        assertValid(longest);
        ObjectNode tooLong = example();
        owner.apply(tooLong).put(name, filler(name, max + 1));
        refused(validating(tooLong));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("texts")
    void aTextWithAControlCharacterOrALetterOutsideAsciiOrNothingAtAllIsRefused(String label,
            Function<ObjectNode, ObjectNode> owner, String name, int max) {
        ObjectNode control = example();
        owner.apply(control).put(name, filler(name, 2) + "\u0007");
        refused(validating(control));
        ObjectNode newline = example();
        owner.apply(newline).put(name, filler(name, 2) + "\n");
        refused(validating(newline));
        ObjectNode accent = example();
        owner.apply(accent).put(name, filler(name, 2) + "é");
        refused(validating(accent));
        ObjectNode empty = example();
        owner.apply(empty).put(name, "");
        refused(validating(empty));
    }

    @Test
    void aTextMayHoldSpaceAndTildeAndEveryPrintableCharacterBetween() {
        ObjectNode tree = example();
        firstSample(tree).put("trainUnit", " ~!\"#$%&'()*+,-./09:;<=>?@AZ[\\]^_`az{|}~");

        assertValid(tree);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("words")
    void aWordFieldTakesOnlyItsWordsAndMayBeNull(Function<ObjectNode, ObjectNode> owner, String name,
                                                 Set<String> allowed) {
        for (String word : allowed) {
            ObjectNode tree = example();
            owner.apply(tree).put(name, word);
            assertValid(tree);
        }
        ObjectNode other = example();
        owner.apply(other).put(name, "other");
        refused(validating(other));
        ObjectNode shouting = example();
        owner.apply(shouting).put(name, allowed.iterator().next().toUpperCase());
        refused(validating(shouting));
        ObjectNode nulled = example();
        owner.apply(nulled).putNull(name);
        assertValid(nulled);
    }

    @Test
    void anOperatorIdWithALetterIsRefusedAndOneOfOnlyDigitsIsTaken() {
        ObjectNode digits = example();
        firstModem(digits).put("operatorId", "0123456789");
        assertValid(digits);
        ObjectNode letter = example();
        firstModem(letter).put("operatorId", "01a");
        refused(validating(letter));
        ObjectNode minus = example();
        firstModem(minus).put("operatorId", "-1");
        refused(validating(minus));
    }

    @Test
    void roundsThatDoNotRiseAreRefused() {
        ObjectNode equal = example();
        ((ObjectNode) samples(equal).get(1)).put("round", 1);
        refused(validating(equal));
        ObjectNode falling = example();
        ((ObjectNode) samples(falling).get(3)).put("round", 2);
        refused(validating(falling));
        ObjectNode gaps = example();
        ((ObjectNode) samples(gaps).get(1)).put("round", 500);
        ((ObjectNode) samples(gaps).get(2)).put("round", 501);
        ((ObjectNode) samples(gaps).get(3)).put("round", 9_000_000);
        ((ObjectNode) samples(gaps).get(4)).put("round", 9_000_001);
        ((ObjectNode) samples(gaps).get(5)).put("round", 10_000_000);
        assertValid(gaps);
    }

    @Test
    void aModemIndexTwiceInOneSampleIsRefusedButTheSameIndexInTwoSamplesIsFine() {
        ObjectNode twice = example();
        ((ArrayNode) firstSample(twice).get("modems")).add(firstModem(twice).deepCopy());
        refused(validating(twice));
        assertValid(example());
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "cpu", "cores", "threads", "memoryBytes"})
    void aDeviceWithFourOfItsFiveValuesIsRefused(String missing) {
        ObjectNode tree = example();
        ((ObjectNode) tree.get("device")).remove(missing);
        refused(validating(tree));
        ObjectNode nulled = example();
        ((ObjectNode) nulled.get("device")).putNull(missing);
        refused(validating(nulled));
    }

    @Test
    void aDeviceMayBeAbsentOrNullButAnEmptyOneIsRefused() {
        ObjectNode absent = example();
        absent.remove("device");
        assertValid(absent);
        ObjectNode nulled = example();
        nulled.putNull("device");
        assertValid(nulled);
        ObjectNode empty = example();
        empty.putObject("device");
        refused(validating(empty));
    }

    @Test
    void twoThousandSamplesAreTakenAndTwoThousandAndOneAreRefused() {
        ObjectNode full = withRounds(2_000);
        assertValid(full);
        ObjectNode over = withRounds(2_001);
        refused(validating(over));
    }

    private static ObjectNode withRounds(int count) {
        ObjectNode tree = example();
        ArrayNode list = samples(tree);
        list.removeAll();
        for (int round = 1; round <= count; round++) {
            list.addObject().put("round", round).put("at", JourneyFileRules.MIN_MS + round);
        }
        return tree;
    }

    @Test
    void noSamplesAtAllAndANullSampleAreRefused() {
        ObjectNode none = example();
        samples(none).removeAll();
        refused(validating(none));
        ObjectNode missing = example();
        missing.remove("samples");
        refused(validating(missing));
        ObjectNode nullElement = example();
        samples(nullElement).addNull();
        refused(validating(nullElement));
    }

    @Test
    void sixteenModemsAreTakenAndSeventeenAreRefused() {
        ObjectNode sixteen = example();
        modemsWith(sixteen, 16);
        assertValid(sixteen);
        ObjectNode seventeen = example();
        modemsWith(seventeen, 17);
        refused(validating(seventeen));
    }

    private static void modemsWith(ObjectNode tree, int count) {
        ArrayNode modems = (ArrayNode) firstSample(tree).get("modems");
        modems.removeAll();
        for (int index = 0; index < count; index++) {
            modems.addObject().put("index", index);
        }
    }

    @Test
    void aNullModemIsRefused() {
        ObjectNode tree = example();
        ((ArrayNode) firstSample(tree).get("modems")).addNull();
        refused(validating(tree));
    }

    @Test
    void aFailedRoundOfOnlyItsNumberAndTimeIsValid() {
        ObjectNode tree = example();

        assertThat(tree.get("samples").get(2).propertyNames()).containsExactlyInAnyOrder("round", "at");
        assertValid(tree);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("notFinite")
    void aDecimalThatIsNotFiniteIsRefused(String name, double value) {
        ObjectNode tree = single();
        firstSample(tree).put(name, value);
        JourneyFile file = JourneyFileSamples.JSON.treeToValue(tree, JourneyFile.class);

        assertThatThrownBy(() -> JourneyFileRules.validate(file)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");
    }

    static Stream<Arguments> notFinite() {
        return JourneyFileRules.SAMPLE_DECIMALS.stream().flatMap(d -> Stream.of(Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map(v -> Arguments.of(d.name(), v)));
    }

    @Test
    void nothingButABadRequestIsEverThrownWhateverIsNull() {
        Sample empty = JourneyFileSamples.JSON.readValue("{}", Sample.class);
        Sample failedRound = JourneyFileSamples.JSON.readValue("{\"round\":1,\"at\":" + JourneyFileRules.MIN_MS + "}",
            Sample.class);
        DeviceInfo emptyDevice = JourneyFileSamples.JSON.readValue("{}", DeviceInfo.class);
        List<JourneyFile> files = List.of(
            new JourneyFile(null, null, null, null, null, null, null),
            new JourneyFile(JourneyFileRules.MIN_MS, 3, null, null, null, null, null),
            new JourneyFile(JourneyFileRules.MIN_MS, 3, null, null, null, emptyDevice, List.of(failedRound)),
            new JourneyFile(JourneyFileRules.MIN_MS, 3, null, null, null, null, List.of(empty)));

        for (JourneyFile file : files) {
            assertThatThrownBy(() -> JourneyFileRules.validate(file)).isExactlyInstanceOf(BadRequestException.class)
                .hasMessage("Invalid journey file");
        }
        assertThatThrownBy(() -> JourneyFileRules.validate(null)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");
        assertThatCode(() -> JourneyFileRules.validate(
            new JourneyFile(JourneyFileRules.MIN_MS, 3, null, null, null, null, List.of(failedRound))))
            .doesNotThrowAnyException();
    }

    @Test
    void aSampleWithANullModemListIsValid() {
        ObjectNode tree = example();
        firstSample(tree).putNull("modems");

        assertValid(tree);
    }
}
