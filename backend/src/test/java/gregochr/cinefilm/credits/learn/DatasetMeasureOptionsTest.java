package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DatasetMeasureOptionsTest {

    private static DatasetMeasureOptions parse(String... args) {
        return DatasetMeasureOptions.parse(args);
    }

    private static void assertRefused(String message, String... args) {
        assertThatThrownBy(() -> parse(args)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void theTwoRequiredFlagsGiveTheDefaults() {
        DatasetMeasureOptions options = parse("--data", "/d", "--out", "/o");

        assertThat(options.data()).isEqualTo(Path.of("/d"));
        assertThat(options.out()).isEqualTo(Path.of("/o"));
        assertThat(options.ids()).isEmpty();
        assertThat(options.stills()).isFalse();
        assertThat(options.project()).isEqualTo(850);
    }

    @Test
    void everyFlagIsRead() {
        DatasetMeasureOptions options = parse("--out", "/o", "--stills", "--ids", "12,5,12,7", "--project", "40",
            "--data", "/d");

        assertThat(options.ids()).containsExactly(5L, 7L, 12L);
        assertThat(options.stills()).isTrue();
        assertThat(options.project()).isEqualTo(40);
    }

    @Test
    void theStillsSwitchTakesNoValueSoTheNextFlagIsStillAFlag() {
        DatasetMeasureOptions options = parse("--stills", "--data", "/d", "--out", "/o");

        assertThat(options.stills()).isTrue();
        assertThat(options.data()).isEqualTo(Path.of("/d"));
    }

    @Test
    void aMissingRequiredFlagIsNamed() {
        assertRefused("missing required flag --data", "--out", "/o");
        assertRefused("missing required flag --out", "--data", "/d");
        assertRefused("missing required flag --data");
    }

    @Test
    void anUnknownArgumentIsNamed() {
        assertRefused("unknown argument --nope", "--data", "/d", "--out", "/o", "--nope", "x");
        assertRefused("unknown argument data", "data", "/d", "--out", "/o");
        assertRefused("unknown argument /d", "/d");
    }

    @Test
    void aFlagWithoutAValueIsNamed() {
        assertRefused("flag --out needs a value", "--data", "/d", "--out");
        assertRefused("flag --ids needs a value", "--ids");
    }

    @Test
    void aRepeatedFlagIsNamed() {
        assertRefused("flag --data given twice", "--data", "/d", "--data", "/e", "--out", "/o");
        assertRefused("flag --stills given twice", "--stills", "--data", "/d", "--out", "/o", "--stills");
    }

    @Test
    void badIdsAreRefused() {
        String message = "--ids must be whole numbers separated by commas";
        assertRefused(message, "--data", "/d", "--out", "/o", "--ids", "1,x");
        assertRefused(message, "--data", "/d", "--out", "/o", "--ids", "");
        assertRefused(message, "--data", "/d", "--out", "/o", "--ids", "1,,2");
        assertRefused(message, "--data", "/d", "--out", "/o", "--ids", "1.5");
    }

    @Test
    void aProjectCountThatIsNotAPositiveWholeNumberIsRefused() {
        String message = "--project must be a whole number above 0";
        assertRefused(message, "--data", "/d", "--out", "/o", "--project", "0");
        assertRefused(message, "--data", "/d", "--out", "/o", "--project", "-3");
        assertRefused(message, "--data", "/d", "--out", "/o", "--project", "many");
    }

    @Test
    void aProjectCountOfOneIsAccepted() {
        assertThat(parse("--data", "/d", "--out", "/o", "--project", "1").project()).isEqualTo(1);
    }
}
