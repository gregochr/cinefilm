package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CreditsFeaturesOptionsTest {

    private static CreditsFeaturesOptions parse(String... args) {
        return CreditsFeaturesOptions.parse(args);
    }

    private static void assertRefused(String message, String... args) {
        assertThatThrownBy(() -> parse(args)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void theThreeRequiredFlagsGiveNoIds() {
        CreditsFeaturesOptions options = parse("--data", "/d", "--labels", "/l", "--out", "/o");

        assertThat(options.data()).isEqualTo(Path.of("/d"));
        assertThat(options.labels()).isEqualTo(Path.of("/l"));
        assertThat(options.out()).isEqualTo(Path.of("/o"));
        assertThat(options.ids()).isEmpty();
    }

    @Test
    void theIdsAreSortedAndDeduplicated() {
        CreditsFeaturesOptions options = parse("--out", "/o", "--ids", "12,5,12,7", "--labels", "/l", "--data", "/d");

        assertThat(options.ids()).containsExactly(5L, 7L, 12L);
    }

    @Test
    void aLookFlagIsAnUnknownArgument() {
        assertRefused("unknown argument --look=L5", "--data", "/d", "--labels", "/l", "--out", "/o", "--look=L5");
    }

    @Test
    void aStillsFlagIsAnUnknownArgument() {
        assertRefused("unknown argument --stills", "--stills", "--data", "/d", "--labels", "/l", "--out", "/o");
    }

    @Test
    void aProjectFlagIsAnUnknownArgument() {
        assertRefused("unknown argument --project", "--data", "/d", "--labels", "/l", "--out", "/o", "--project",
            "5");
    }

    @Test
    void aNameWithoutDashesIsUnknown() {
        assertRefused("unknown argument data", "data", "/d");
    }

    @Test
    void aMissingRequiredFlagIsNamed() {
        assertRefused("missing required flag --labels", "--data", "/d", "--out", "/o");
    }

    @Test
    void aFlagWithNoValueIsRefused() {
        assertRefused("flag --out needs a value", "--data", "/d", "--labels", "/l", "--out");
    }

    @Test
    void aRepeatedFlagIsRefused() {
        assertRefused("flag --data given twice", "--data", "/d", "--data", "/e", "--labels", "/l", "--out", "/o");
    }

    @Test
    void idsThatAreNotWholeNumbersAreRefused() {
        assertRefused("--ids must be whole numbers separated by commas", "--data", "/d", "--labels", "/l", "--out",
            "/o", "--ids", "1,x");
    }
}
