package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CreditsTrainLrOptionsTest {

    private static final List<String> REQUIRED = List.of("--features", "f.csv", "--summary", "s.json", "--manifest",
        "m.json", "--embeddings", "/out/embed-x", "--out", "/out");

    private static String[] args(String... extra) {
        List<String> all = new ArrayList<>(REQUIRED);
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    @Test
    void theFivePathsAreRequiredAndEverythingElseDefaults() {
        CreditsTrainLrOptions options = CreditsTrainLrOptions.parse(args());

        assertThat(options).isEqualTo(new CreditsTrainLrOptions(Path.of("f.csv"), Path.of("s.json"),
            Path.of("m.json"), Path.of("/out/embed-x"), Path.of("/out"), new LrParams(1.0, 300, 1e-6), false,
            false));
    }

    @Test
    void everyFlagIsRead() {
        CreditsTrainLrOptions options = CreditsTrainLrOptions.parse(args("--l2", "2.5", "--max-iterations", "40",
            "--tolerance", "1e-3", "--with-dense", "--lit-text-variant"));

        assertThat(options.params()).isEqualTo(new LrParams(2.5, 40, 1e-3));
        assertThat(options.withDense()).isTrue();
        assertThat(options.litTextVariant()).isTrue();
    }

    @Test
    void theSwitchesAreIndependent() {
        assertThat(CreditsTrainLrOptions.parse(args("--with-dense")).litTextVariant()).isFalse();
        assertThat(CreditsTrainLrOptions.parse(args("--lit-text-variant")).withDense()).isFalse();
        assertThat(CreditsTrainLrOptions.parse(args("--lit-text-variant")).litTextVariant()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"--look=L5", "--look", "--trees", "features.csv", "--dense", "with-dense"})
    void anythingElseIsAnUnknownArgument(String flag) {
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args(flag)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown argument " + flag);
    }

    @Test
    void lookIsUnknownWhateverItIsFollowedBy() {
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--look", "L5")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown argument --look");
    }

    @ParameterizedTest
    @CsvSource({"--features", "--summary", "--manifest", "--embeddings", "--out"})
    void aMissingPathIsNamed(String flag) {
        List<String> all = new ArrayList<>(REQUIRED);
        int at = all.indexOf(flag);
        all.remove(at);
        all.remove(at);

        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(all.toArray(String[]::new)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("missing required flag " + flag);
    }

    @Test
    void aFlagWithNoValueIsRefused() {
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--l2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --l2 needs a value");
    }

    @Test
    void aFlagGivenTwiceIsRefused() {
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--l2", "1", "--l2", "2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --l2 given twice");
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--with-dense", "--with-dense")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --with-dense given twice");
    }

    @Test
    void aNumberThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--max-iterations", "many")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("flag --max-iterations needs a whole number: many");
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--l2", "x")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --l2 needs a number: x");
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--tolerance", "")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --tolerance needs a number: ");
    }

    @Test
    void aSettingOutOfRangeIsRefusedByTheParams() {
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--l2", "0")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("l2 must be finite and above 0: 0.0");
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--max-iterations", "0")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("maxIterations must be 1 to 10000: 0");
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--max-iterations", "10001")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("maxIterations must be 1 to 10000: 10001");
        assertThatThrownBy(() -> CreditsTrainLrOptions.parse(args("--tolerance", "-1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("tolerance must be finite and above 0: -1.0");
    }

    @Test
    void theBoundsOfTheIterationCountAreAccepted() {
        assertThat(CreditsTrainLrOptions.parse(args("--max-iterations", "1")).params().maxIterations()).isEqualTo(1);
        assertThat(CreditsTrainLrOptions.parse(args("--max-iterations", "10000")).params().maxIterations())
            .isEqualTo(10_000);
    }
}
