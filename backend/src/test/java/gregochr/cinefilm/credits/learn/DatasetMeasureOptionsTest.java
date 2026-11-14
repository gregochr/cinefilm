package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DatasetMeasureOptionsTest {

    private static DatasetMeasureOptions parse(String... args) {
        return DatasetMeasureOptions.parse(args);
    }

    private static void assertRefused(String message, String... args) {
        assertThatThrownBy(() -> parse(args)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void theThreeRequiredFlagsGiveTheDefaults() {
        DatasetMeasureOptions options = parse("--data", "/d", "--out", "/o", "--labels", "/l");

        assertThat(options.data()).isEqualTo(Path.of("/d"));
        assertThat(options.out()).isEqualTo(Path.of("/o"));
        assertThat(options.labels()).isEqualTo(Path.of("/l"));
        assertThat(options.ids()).isEmpty();
        assertThat(options.stills()).isFalse();
        assertThat(options.project()).isEqualTo(850);
    }

    @Test
    void everyFlagIsRead() {
        DatasetMeasureOptions options = parse("--out", "/o", "--stills", "--ids", "12,5,12,7", "--project", "40",
            "--labels", "/l", "--data", "/d");

        assertThat(options.ids()).containsExactly(5L, 7L, 12L);
        assertThat(options.stills()).isTrue();
        assertThat(options.project()).isEqualTo(40);
    }

    @Test
    void aLookFlagIsAnUnknownArgument() {
        String[] base = {"--data", "/d", "--out", "/o", "--labels", "/l"};
        String[] equals = Arrays.copyOf(base, base.length + 1);
        equals[base.length] = "--look=L5";
        String[] separate = Arrays.copyOf(base, base.length + 2);
        separate[base.length] = "--look";
        separate[base.length + 1] = "gate";

        assertRefused("unknown argument --look=L5", equals);
        assertRefused("unknown argument --look", separate);
    }

    @Test
    void theStillsSwitchTakesNoValueSoTheNextFlagIsStillAFlag() {
        DatasetMeasureOptions options = parse("--stills", "--data", "/d", "--out", "/o", "--labels", "/l");

        assertThat(options.stills()).isTrue();
        assertThat(options.data()).isEqualTo(Path.of("/d"));
    }

    @Test
    void aMissingRequiredFlagIsNamed() {
        assertRefused("missing required flag --data", "--out", "/o", "--labels", "/l");
        assertRefused("missing required flag --out", "--data", "/d", "--labels", "/l");
        assertRefused("missing required flag --labels", "--data", "/d", "--out", "/o");
        assertRefused("missing required flag --data");
    }

    @Test
    void anUnknownArgumentIsNamed() {
        assertRefused("unknown argument --nope", "--data", "/d", "--out", "/o", "--nope", "x");
        assertRefused("unknown argument data", "data", "/d", "--out", "/o");
        assertRefused("unknown argument /d", "/d");
        assertRefused("unknown argument --stills=yes", "--stills=yes");
    }

    @Test
    void aFlagWithoutAValueIsNamed() {
        assertRefused("flag --out needs a value", "--data", "/d", "--out");
        assertRefused("flag --ids needs a value", "--ids");
        assertRefused("flag --labels needs a value", "--labels");
    }

    @Test
    void aRepeatedFlagIsNamed() {
        assertRefused("flag --data given twice", "--data", "/d", "--data", "/e", "--out", "/o");
        assertRefused("flag --stills given twice", "--stills", "--data", "/d", "--out", "/o", "--stills");
    }

    @Test
    void badIdsAreRefused() {
        String message = "--ids must be whole numbers separated by commas";
        String[] base = {"--data", "/d", "--out", "/o", "--labels", "/l", "--ids"};
        for (String ids : new String[] {"1,x", "", "1,,2", "1.5"}) {
            String[] args = Arrays.copyOf(base, base.length + 1);
            args[base.length] = ids;
            assertRefused(message, args);
        }
    }

    @Test
    void aProjectCountThatIsNotAPositiveWholeNumberIsRefused() {
        String message = "--project must be a whole number above 0";
        assertRefused(message, "--data", "/d", "--out", "/o", "--labels", "/l", "--project", "0");
        assertRefused(message, "--data", "/d", "--out", "/o", "--labels", "/l", "--project", "-3");
        assertRefused(message, "--data", "/d", "--out", "/o", "--labels", "/l", "--project", "many");
    }

    @Test
    void aProjectCountOfOneIsAccepted() {
        assertThat(parse("--data", "/d", "--out", "/o", "--labels", "/l", "--project", "1").project()).isEqualTo(1);
    }
}
