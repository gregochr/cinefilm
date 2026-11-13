package gregochr.cinefilm.health;

import static gregochr.cinefilm.health.HealthSamples.client;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.fullMinute;
import static gregochr.cinefilm.health.HealthSamples.minutes;
import static gregochr.cinefilm.health.HealthSamples.report;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.health.HealthDtos.HealthReport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class HealthRulesTest {

    private static final long MAX = 2_000_000_000L;

    private static final String[] REQUIRED_TOP_LEVEL = {"seq", "editionId", "elapsedMs", "environment", "quality",
        "autoplayRefused", "watchedMs", "stallCount", "stallMs", "longestStallMs", "seekCount", "seekWaitMs",
        "reloadCount", "reloadMs", "reconnectCount", "reconnectMs", "recoveries", "fellBack", "stepsDown", "stepsUp",
        "netErrors", "timeouts", "kbpsSamples", "rungMs", "minutes", "client"};

    private static final String[] NULLABLE_TOP_LEVEL = {"operator", "latencyMs", "engine", "startupMs", "kbpsLow",
        "kbpsMedian", "kbpsHigh"};

    /** Every figure at the top level except seq and editionId (those start at 1), nullable or not. */
    private static final String[] FIGURES = {"elapsedMs", "latencyMs", "startupMs", "watchedMs", "stallCount",
        "stallMs", "longestStallMs", "seekCount", "seekWaitMs", "reloadCount", "reloadMs", "reconnectCount",
        "reconnectMs", "recoveries", "stepsDown", "stepsUp", "netErrors", "timeouts", "kbpsLow", "kbpsMedian",
        "kbpsHigh", "kbpsSamples"};

    private static void assertValid(ObjectNode node) {
        assertThatCode(() -> HealthRules.validate(report(node))).doesNotThrowAnyException();
    }

    private static void assertRefused(ObjectNode node) {
        HealthReport parsed = report(node);
        assertThatThrownBy(() -> HealthRules.validate(parsed))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    static Stream<String> requiredTopLevel() {
        return Stream.of(REQUIRED_TOP_LEVEL);
    }

    static Stream<String> nullableTopLevel() {
        return Stream.of(NULLABLE_TOP_LEVEL);
    }

    static Stream<String> figures() {
        return Stream.of(FIGURES);
    }

    @Test
    void thePlansExampleReportPasses() {
        assertValid(example());
    }

    @Test
    void noReportAtAllIsRefused() {
        assertThatThrownBy(() -> HealthRules.validate(null))
            .isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid health report");
    }

    // required fields

    @ParameterizedTest
    @MethodSource("requiredTopLevel")
    void aRequiredFieldThatIsNullIsRefused(String field) {
        ObjectNode node = example();
        node.putNull(field);
        assertRefused(node);
    }

    @ParameterizedTest
    @MethodSource("requiredTopLevel")
    void aRequiredFieldThatIsMissingIsRefused(String field) {
        ObjectNode node = example();
        node.remove(field);
        assertRefused(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"userAgent", "screen"})
    void aRequiredClientFieldThatIsNullIsRefused(String field) {
        ObjectNode node = example();
        client(node).putNull(field);
        assertRefused(node);
    }

    @ParameterizedTest
    @MethodSource("nullableTopLevel")
    void aNullableFieldThatIsNullPasses(String field) {
        ObjectNode node = example();
        node.putNull(field);
        assertValid(node);
    }

    @Test
    void theCoresOfTheClientMayBeNullOrMissing() {
        ObjectNode nullCores = example();
        client(nullCores).putNull("cores");
        assertValid(nullCores);

        ObjectNode noCores = example();
        client(noCores).remove("cores");
        assertValid(noCores);
    }

    // seq and editionId

    @ParameterizedTest
    @ValueSource(strings = {"seq", "editionId"})
    void seqAndEditionIdStartAtOne(String field) {
        ObjectNode zero = example();
        zero.put(field, 0);
        assertRefused(zero);

        ObjectNode one = example();
        one.put(field, 1);
        assertValid(one);

        ObjectNode negative = example();
        negative.put(field, -1);
        assertRefused(negative);
    }

    @ParameterizedTest
    @ValueSource(strings = {"seq", "editionId"})
    void seqAndEditionIdStopAtTwoThousandMillion(String field) {
        ObjectNode top = example();
        top.put(field, MAX);
        assertValid(top);

        ObjectNode over = example();
        over.put(field, MAX + 1);
        assertRefused(over);
    }

    // figures

    @ParameterizedTest
    @MethodSource("figures")
    void aFigureBelowZeroIsRefusedAndZeroPasses(String field) {
        ObjectNode below = example();
        below.put(field, -1);
        assertRefused(below);

        ObjectNode zero = example();
        zero.put(field, 0);
        assertValid(zero);
    }

    @ParameterizedTest
    @MethodSource("figures")
    void aFigureAtTwoThousandMillionPassesAndOneMoreIsRefused(String field) {
        ObjectNode top = example();
        top.put(field, MAX);
        assertValid(top);

        ObjectNode over = example();
        over.put(field, MAX + 1);
        assertRefused(over);
    }

    @Test
    void theCoresOfTheClientFollowTheSameBounds() {
        for (long ok : new long[] {0, MAX}) {
            ObjectNode node = example();
            client(node).put("cores", ok);
            assertValid(node);
        }
        for (long bad : new long[] {-1, MAX + 1}) {
            ObjectNode node = example();
            client(node).put("cores", bad);
            assertRefused(node);
        }
    }

    // enum-like fields

    @ParameterizedTest
    @ValueSource(strings = {"home", "train", "away", "unknown"})
    void everyEnvironmentInTheListPasses(String environment) {
        ObjectNode node = example();
        node.put("environment", environment);
        assertValid(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Home", "plane", "HOME", "home "})
    void anEnvironmentOutsideTheListIsRefused(String environment) {
        ObjectNode node = example();
        node.put("environment", environment);
        assertRefused(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hlsjs", "native", "direct"})
    void everyEngineInTheListPasses(String engine) {
        ObjectNode node = example();
        node.put("engine", engine);
        assertValid(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "HLSJS", "mse", "hls.js"})
    void anEngineOutsideTheListIsRefused(String engine) {
        ObjectNode node = example();
        node.put("engine", engine);
        assertRefused(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "original", "1080p", "720p", "480p", "360p", "240p"})
    void everyQualityInTheLadderPasses(String quality) {
        ObjectNode node = example();
        node.put("quality", quality);
        assertValid(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2160p", "AUTO", "high"})
    void aQualityOutsideTheLadderIsRefused(String quality) {
        ObjectNode node = example();
        node.put("quality", quality);
        assertRefused(node);
    }

    // strings

    @Test
    void anOperatorOfOneHundredCharactersPassesAndOneHundredAndOneIsRefused() {
        ObjectNode ok = example();
        ok.put("operator", "x".repeat(100));
        assertValid(ok);

        ObjectNode over = example();
        over.put("operator", "x".repeat(101));
        assertRefused(over);
    }

    @Test
    void aUserAgentOfThreeHundredCharactersPassesAndThreeHundredAndOneIsRefused() {
        ObjectNode ok = example();
        client(ok).put("userAgent", "x".repeat(300));
        assertValid(ok);

        ObjectNode over = example();
        client(over).put("userAgent", "x".repeat(301));
        assertRefused(over);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1512x982", "1x1", "12345x12345"})
    void aScreenOfTwoNumbersJoinedByXPasses(String screen) {
        ObjectNode node = example();
        client(node).put("screen", screen);
        assertValid(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1512", "x", "123456x1", "1x123456", "1512x", "x982", "1512X982", "1512x982\n",
        " 1512x982", "15a2x982", "-1x5", ""})
    void aScreenThatIsNotTwoNumbersJoinedByXIsRefused(String screen) {
        ObjectNode node = example();
        client(node).put("screen", screen);
        assertRefused(node);
    }

    // rungMs

    @ParameterizedTest
    @ValueSource(strings = {"original", "1080p", "720p", "480p", "360p", "240p"})
    void everyRungIdIsAKeyThatPasses(String rung) {
        ObjectNode node = example();
        ((ObjectNode) node.get("rungMs")).put(rung, 5);
        assertValid(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "2160p", "", "480P"})
    void aKeyThatIsNotARungIsRefused(String key) {
        ObjectNode node = example();
        ((ObjectNode) node.get("rungMs")).put(key, 5);
        assertRefused(node);
    }

    @Test
    void aNullValueInRungMsIsRefused() {
        ObjectNode node = example();
        ((ObjectNode) node.get("rungMs")).putNull("480p");
        assertRefused(node);
    }

    @Test
    void aRungValueIsBoundedLikeEveryFigure() {
        ObjectNode below = example();
        ((ObjectNode) below.get("rungMs")).put("480p", -1);
        assertRefused(below);

        ObjectNode top = example();
        ((ObjectNode) top.get("rungMs")).put("480p", MAX);
        assertValid(top);

        ObjectNode over = example();
        ((ObjectNode) over.get("rungMs")).put("480p", MAX + 1);
        assertRefused(over);
    }

    @Test
    void anEmptyRungMsPasses() {
        ObjectNode node = example();
        ((ObjectNode) node.get("rungMs")).removeAll();
        assertValid(node);
    }

    // minutes

    private static void fillMinutesTo(ObjectNode node, int size) {
        ArrayNode minutes = minutes(node);
        minutes.removeAll();
        for (int i = 0; i < size; i++) {
            minutes.add(fullMinute());
        }
    }

    @Test
    void threeHundredAndSixtyMinutesPassAndThreeHundredAndSixtyOneAreRefused() {
        ObjectNode ok = example();
        fillMinutesTo(ok, 360);
        assertValid(ok);

        ObjectNode over = example();
        fillMinutesTo(over, 361);
        assertRefused(over);
    }

    @Test
    void noMinutesAtAllPasses() {
        ObjectNode node = example();
        fillMinutesTo(node, 0);
        assertValid(node);
    }

    @Test
    void aMinuteOfFourSixOrEightNumbersIsRefusedFiveAndSevenArePassed() {
        ObjectNode four = example();
        ((ArrayNode) minutes(four).get(0)).remove(4);
        assertRefused(four);

        ObjectNode five = example();
        assertValid(five);

        ObjectNode six = example();
        ((ArrayNode) minutes(six).get(0)).add(0);
        assertRefused(six);

        ObjectNode seven = example();
        ((ArrayNode) minutes(seven).get(0)).add(0).add(0);
        assertValid(seven);

        ObjectNode eight = example();
        ((ArrayNode) minutes(eight).get(0)).add(0).add(0).add(0);
        assertRefused(eight);
    }

    @Test
    void aNullInsideAMinuteIsRefusedAtEveryPosition() {
        for (int position = 0; position < 5; position++) {
            ObjectNode node = example();
            ((ArrayNode) minutes(node).get(1)).set(position, node.nullNode());
            assertRefused(node);
        }
    }

    @Test
    void aNullMinuteIsRefused() {
        ObjectNode node = example();
        minutes(node).addNull();
        assertRefused(node);
    }

    @Test
    void aRungCodeOfSixPassesAndSevenIsRefused() {
        ObjectNode six = example();
        ((ArrayNode) minutes(six).get(0)).set(2, six.numberNode(6));
        assertValid(six);

        ObjectNode seven = example();
        ((ArrayNode) minutes(seven).get(0)).set(2, seven.numberNode(7));
        assertRefused(seven);
    }

    @Test
    void aRungCodeOfZeroPassesAndBelowZeroIsRefused() {
        ObjectNode zero = example();
        ((ArrayNode) minutes(zero).get(0)).set(2, zero.numberNode(0));
        assertValid(zero);

        ObjectNode below = example();
        ((ArrayNode) minutes(below).get(0)).set(2, below.numberNode(-1));
        assertRefused(below);
    }

    @Test
    void aFetchedRungCodeOfSixPassesAndSevenIsRefused() {
        ObjectNode six = example();
        ((ArrayNode) minutes(six).get(3)).set(6, six.numberNode(6));
        assertValid(six);

        ObjectNode zero = example();
        ((ArrayNode) minutes(zero).get(3)).set(6, zero.numberNode(0));
        assertValid(zero);

        ObjectNode seven = example();
        ((ArrayNode) minutes(seven).get(3)).set(6, seven.numberNode(7));
        assertRefused(seven);
    }

    @Test
    void aSixthFigureOfTheMaximumPassesAndAboveItOrBelowZeroIsRefused() {
        ObjectNode top = example();
        ((ArrayNode) minutes(top).get(3)).set(5, top.numberNode(MAX));
        assertValid(top);

        ObjectNode over = example();
        ((ArrayNode) minutes(over).get(3)).set(5, over.numberNode(MAX + 1));
        assertRefused(over);

        ObjectNode below = example();
        ((ArrayNode) minutes(below).get(3)).set(5, below.numberNode(-1));
        assertRefused(below);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void aNullInsideASevenNumberMinuteIsRefusedAtEveryPosition(int position) {
        ObjectNode node = example();
        ((ArrayNode) minutes(node).get(3)).set(position, node.nullNode());
        assertRefused(node);
    }

    @Test
    void aSeventhFigureBelowZeroIsRefused() {
        ObjectNode node = example();
        ((ArrayNode) minutes(node).get(3)).set(6, node.numberNode(-1));
        assertRefused(node);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void aMinuteValueBelowZeroIsRefusedAtEveryPosition(int position) {
        ObjectNode node = example();
        ((ArrayNode) minutes(node).get(2)).set(position, node.numberNode(-1));
        assertRefused(node);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4})
    void aMinuteValueIsBoundedAtTwoThousandMillion(int position) {
        ObjectNode top = example();
        ((ArrayNode) minutes(top).get(2)).set(position, top.numberNode(MAX));
        assertValid(top);

        ObjectNode over = example();
        ((ArrayNode) minutes(over).get(2)).set(position, over.numberNode(MAX + 1));
        assertRefused(over);
    }

    // nothing but a refusal

    @Test
    void aClientThatIsMissingIsRefusedNotFailed() {
        ObjectNode node = example();
        node.remove("client");
        assertRefused(node);
    }
}
