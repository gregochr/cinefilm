package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.TrainFileSamples.calls;
import static gregochr.cinefilm.journey.TrainFileSamples.example;
import static gregochr.cinefilm.journey.TrainFileSamples.firstCall;
import static gregochr.cinefilm.journey.TrainFileSamples.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.TrainFile.Call;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** The rules of a train's file: each bound just inside and just outside, and every rule for a call's times. */
class TrainFileRulesTest {

    private static final Function<ObjectNode, ObjectNode> FILE = root -> root;
    private static final Function<ObjectNode, ObjectNode> CALL = TrainFileSamples::firstCall;

    /** A text of the contract: its name, greatest length and whether it must be there. */
    private record TextBound(String name, int max, boolean required) {
    }

    /** The contract's texts, written out from the plan's section "The train's file" and not from the code. */
    private static final List<TextBound> FILE_CONTRACT = List.of(
        new TextBound("uid", 10, true),
        new TextBound("headcode", 10, true),
        new TextBound("operator", 100, false),
        new TextBound("source", 200, false));

    private static final List<TextBound> CALL_CONTRACT = List.of(new TextBound("location", 100, true));

    private static final List<String> TIME_NAMES = List.of("plannedArrival", "plannedDeparture", "plannedPass",
        "actualArrival", "actualDeparture", "actualPass");

    private static AbstractThrowableAssert<?, ? extends Throwable> validating(ObjectNode tree) {
        TrainFile file = read(tree);
        return assertThatThrownBy(() -> TrainFileRules.validate(file));
    }

    private static void refused(AbstractThrowableAssert<?, ? extends Throwable> thrown) {
        thrown.isExactlyInstanceOf(BadRequestException.class).hasMessage("Invalid train file");
    }

    private static void assertValid(ObjectNode tree) {
        TrainFile file = read(tree);
        assertThatCode(() -> TrainFileRules.validate(file)).doesNotThrowAnyException();
    }

    private static void assertRefusedFile(TrainFile file) {
        assertThatThrownBy(() -> TrainFileRules.validate(file)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid train file");
    }

    /** One call from its six times, in the order of {@link #TIME_NAMES}; a null stays missing. */
    private static ObjectNode callOf(String... times) {
        ObjectNode call = TrainFileSamples.JSON.createObjectNode().put("location", "Alpha Junction");
        for (int i = 0; i < times.length; i++) {
            if (times[i] != null) {
                call.put(TIME_NAMES.get(i), times[i]);
            }
        }
        return call;
    }

    /** The example with its first call replaced by this one. */
    private static ObjectNode withFirstCall(ObjectNode call) {
        ObjectNode tree = example();
        calls(tree).set(0, call);
        return tree;
    }

    private static String filler(int length) {
        return "a".repeat(length);
    }

    static Stream<Arguments> texts() {
        return Stream.concat(
            FILE_CONTRACT.stream().map(t -> Arguments.of(FILE, t.name(), t.max(), t.required())),
            CALL_CONTRACT.stream().map(t -> Arguments.of(CALL, t.name(), t.max(), t.required())));
    }

    static Stream<Arguments> requiredTexts() {
        return texts().filter(a -> (Boolean) a.get()[3]);
    }

    static Stream<Arguments> optionalTexts() {
        return texts().filter(a -> !(Boolean) a.get()[3]);
    }

    @Test
    void theCodesTablesAreTheContractsTextsFieldByField() {
        assertThat(TrainFileRules.FILE_TEXTS).extracting(TrainFileRules.Text::name, TrainFileRules.Text::max,
                TrainFileRules.Text::required)
            .containsExactlyElementsOf(FILE_CONTRACT.stream()
                .map(t -> org.assertj.core.groups.Tuple.tuple(t.name(), t.max(), t.required())).toList());
        assertThat(TrainFileRules.CALL_TEXTS).extracting(TrainFileRules.Text::name, TrainFileRules.Text::max,
                TrainFileRules.Text::required)
            .containsExactlyElementsOf(CALL_CONTRACT.stream()
                .map(t -> org.assertj.core.groups.Tuple.tuple(t.name(), t.max(), t.required())).toList());
    }

    @Test
    void theCodesTimesDateRangeAndCallCountsAreTheContracts() {
        assertThat(TrainFileRules.CLOCKS).extracting(TrainFileRules.Clock::name).containsExactlyElementsOf(TIME_NAMES);
        assertThat(TrainFileRules.MIN_DATE).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(TrainFileRules.MAX_DATE).isEqualTo(LocalDate.parse("2100-01-01"));
        assertThat(TrainFileRules.MIN_CALLS).isEqualTo(2);
        assertThat(TrainFileRules.MAX_CALLS).isEqualTo(300);
    }

    @Test
    void theWorkedExamplePasses() {
        assertValid(example());
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("texts")
    void aTextAtItsGreatestLengthAndAtOneCharacterPasses(Function<ObjectNode, ObjectNode> owner, String name,
            int max, boolean required) {
        ObjectNode longest = example();
        owner.apply(longest).put(name, filler(max));
        ObjectNode shortest = example();
        owner.apply(shortest).put(name, "a");

        assertValid(longest);
        assertValid(shortest);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("texts")
    void aTextOneLongerThanItsGreatestLengthIsRefused(Function<ObjectNode, ObjectNode> owner, String name, int max,
            boolean required) {
        ObjectNode tree = example();
        owner.apply(tree).put(name, filler(max + 1));

        refused(validating(tree));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("texts")
    void anEmptyTextIsRefused(Function<ObjectNode, ObjectNode> owner, String name, int max, boolean required) {
        ObjectNode tree = example();
        owner.apply(tree).put(name, "");

        refused(validating(tree));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("requiredTexts")
    void aRequiredTextThatIsMissingOrNullIsRefused(Function<ObjectNode, ObjectNode> owner, String name, int max,
            boolean required) {
        ObjectNode missing = example();
        owner.apply(missing).remove(name);
        ObjectNode nulled = example();
        owner.apply(nulled).putNull(name);

        refused(validating(missing));
        refused(validating(nulled));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("optionalTexts")
    void anOptionalTextThatIsMissingOrNullPasses(Function<ObjectNode, ObjectNode> owner, String name, int max,
            boolean required) {
        ObjectNode missing = example();
        owner.apply(missing).remove(name);
        ObjectNode nulled = example();
        owner.apply(nulled).putNull(name);

        assertValid(missing);
        assertValid(nulled);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("texts")
    void aTextWithAControlCharacterOrALetterOutsideAsciiIsRefused(Function<ObjectNode, ObjectNode> owner,
            String name, int max, boolean required) {
        for (String bad : new String[] {"ab\ncd", "ab\tcd", "ab\u007fcd", "café", "ab–cd"}) {
            ObjectNode tree = example();
            owner.apply(tree).put(name, bad);

            refused(validating(tree));
        }
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("texts")
    void everyPrintableAsciiCharacterIsAccepted(Function<ObjectNode, ObjectNode> owner, String name, int max,
            boolean required) {
        for (char c = ' '; c <= '~'; c++) {
            ObjectNode tree = example();
            owner.apply(tree).put(name, "a" + c);

            assertValid(tree);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020-01-01", "2100-01-01", "2030-03-04", "2028-02-29"})
    void aDateInsideTheRangeThatIsRealPasses(String date) {
        ObjectNode tree = example();
        tree.put("date", date);

        assertValid(tree);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "2027-02-29", "2026-13-01", "2026-00-10", "2026-01-00", "26-01-01",
        "2019-12-31", "2100-01-02", "2026-1-01", "2026-01-1", "20260101", "2030-03-04T00:00", " 2030-03-04",
        "2030-03-04 ", "+12030-03-04", "+02030-03-04", "+002030-03-04",
        "02030-03-04", "-2030-03-04", "２０３０-03-04", "not a date", "", "٢٠٣٠-03-04"})
    void aBadDateIsRefused(String date) {
        ObjectNode tree = example();
        tree.put("date", date);

        refused(validating(tree));
    }

    @Test
    void aMissingOrNullDateIsRefused() {
        ObjectNode missing = example();
        missing.remove("date");
        ObjectNode nulled = example();
        nulled.putNull("date");

        refused(validating(missing));
        refused(validating(nulled));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "23:59", "12:00:30", "12:00:00", "09:05", "19:59:00", "20:00:30"})
    void aGoodTimePasses(String time) {
        ObjectNode tree = withFirstCall(callOf(null, time, null, null, null, null));

        assertValid(tree);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7:05", "24:00", "12:60", "12:00:15", "12:00:60", "1200", "noon", "", "12:00:3",
        "12:0", "12:00:00:00", " 12:00", "12:00 ", "12:00\n", "12.00", "29:00", "١٢:00", "-1:00"})
    void aBadTimeIsRefusedWhicheverTimeItIsIn(String time) {
        for (int i = 0; i < TIME_NAMES.size(); i++) {
            ObjectNode call = callOf();
            call.put("plannedDeparture", "22:00");
            call.put(TIME_NAMES.get(i), time);

            refused(validating(withFirstCall(call)));
        }
    }

    @Test
    void aNullTimeIsTheSameAsAMissingOne() {
        ObjectNode call = callOf(null, "22:00", null, null, null, null);
        call.putNull("actualArrival");

        assertValid(withFirstCall(call));
    }

    @Test
    void aCallWithNoPlannedTimeIsRefusedWhateverItsActualTimesAre() {
        refused(validating(withFirstCall(callOf())));
        refused(validating(withFirstCall(callOf(null, null, null, "22:00", "22:01", null))));
        refused(validating(withFirstCall(callOf(null, null, null, null, null, "22:00"))));
    }

    @Test
    void aPlannedArrivalAloneADepartureAloneBothOrAPassAlonePass() {
        assertValid(withFirstCall(callOf("22:00", null, null, null, null, null)));
        assertValid(withFirstCall(callOf(null, "22:00", null, null, null, null)));
        assertValid(withFirstCall(callOf("22:00", "22:02", null, null, null, null)));
        assertValid(withFirstCall(callOf(null, null, "22:00", null, null, null)));
    }

    @Test
    void aPlannedPassWithAnArrivalOrADepartureIsRefused() {
        refused(validating(withFirstCall(callOf("22:00", null, "22:00", null, null, null))));
        refused(validating(withFirstCall(callOf(null, "22:00", "22:00", null, null, null))));
        refused(validating(withFirstCall(callOf("22:00", "22:02", "22:01", null, null, null))));
    }

    @Test
    void anActualArrivalAloneADepartureAloneBothAPassAloneOrNothingPasses() {
        assertValid(withFirstCall(callOf(null, "22:00", null, "22:01", null, null)));
        assertValid(withFirstCall(callOf(null, "22:00", null, null, "22:01", null)));
        assertValid(withFirstCall(callOf(null, "22:00", null, "22:01", "22:02", null)));
        assertValid(withFirstCall(callOf(null, "22:00", null, null, null, "22:01")));
        assertValid(withFirstCall(callOf(null, "22:00", null, null, null, null)));
    }

    @Test
    void anActualPassWithAnArrivalOrADepartureIsRefused() {
        refused(validating(withFirstCall(callOf(null, "22:00", null, "22:01", null, "22:01"))));
        refused(validating(withFirstCall(callOf(null, "22:00", null, null, "22:01", "22:01"))));
        refused(validating(withFirstCall(callOf(null, "22:00", null, "22:01", "22:02", "22:01"))));
    }

    @Test
    void aCallPlannedAsAPassMayHaveStoppedAndOneMayBePlannedToStopAndPass() {
        assertValid(withFirstCall(callOf(null, null, "22:00", "22:01", "22:02", null)));
        assertValid(withFirstCall(callOf(null, "22:00", null, null, null, "22:01")));
    }

    @Test
    void aCallWithoutALocationIsRefused() {
        ObjectNode call = callOf(null, "22:00", null, null, null, null);
        call.remove("location");

        refused(validating(withFirstCall(call)));
    }

    @Test
    void anyCallThatBreaksARuleRefusesTheFileWhereverItIs() {
        ObjectNode last = example();
        ObjectNode bad = callOf("22:00", null, "22:00", null, null, null);
        calls(last).set(calls(last).size() - 1, bad);
        ObjectNode middle = example();
        ((ObjectNode) calls(middle).get(3)).put("actualPass", "25:00");

        refused(validating(last));
        refused(validating(middle));
    }

    @Test
    void oneCallOnlyIsRefused() {
        ObjectNode tree = example();
        while (calls(tree).size() > 1) {
            calls(tree).remove(1);
        }

        refused(validating(tree));
    }

    @Test
    void twoCallsPass() {
        ObjectNode tree = example();
        while (calls(tree).size() > 2) {
            calls(tree).remove(2);
        }

        assertValid(tree);
    }

    @Test
    void noCallsOrNullCallsAreRefused() {
        ObjectNode empty = example();
        calls(empty).removeAll();
        ObjectNode missing = example();
        missing.remove("calls");
        ObjectNode nulled = example();
        nulled.putNull("calls");

        refused(validating(empty));
        refused(validating(missing));
        refused(validating(nulled));
    }

    @Test
    void threeHundredCallsPassAndThreeHundredAndOneAreRefused() {
        ObjectNode tree = example();
        ObjectNode copy = firstCall(tree).deepCopy();
        while (calls(tree).size() < 300) {
            calls(tree).add(copy.deepCopy());
        }
        assertValid(tree);

        calls(tree).add(copy.deepCopy());
        refused(validating(tree));
    }

    @Test
    void nothingButTheOneRefusalIsEverThrownWhateverIsNull() {
        Call call = new Call("Alpha Junction", null, "22:00", null, null, null, null);
        List<Call> withNull = new ArrayList<>(List.of(call, call));
        withNull.set(1, null);

        assertRefusedFile(null);
        assertRefusedFile(new TrainFile(null, null, null, null, null, null));
        assertRefusedFile(new TrainFile("X12345", "2030-03-04", "9Z99", null, null, withNull));
        assertRefusedFile(new TrainFile("X12345", "2030-03-04", "9Z99", null, null, List.of(call)));
        assertRefusedFile(new TrainFile("X12345", "2030-03-04", "9Z99", null, null,
            List.of(call, new Call(null, null, null, null, null, null, null))));
    }

    @Test
    void aFileOfValidValuesBuiltByHandPasses() {
        Call origin = new Call("Alpha Junction", null, "22:00", null, null, "22:01", null);
        Call end = new Call("Beta Parkway", "22:30", null, null, "22:31:30", null, null);

        assertThatCode(() -> TrainFileRules.validate(
            new TrainFile("X12345", "2030-03-04", "9Z99", null, null, List.of(origin, end))))
            .doesNotThrowAnyException();
    }
}
