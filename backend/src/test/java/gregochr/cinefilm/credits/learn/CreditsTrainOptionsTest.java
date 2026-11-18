package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CreditsTrainOptionsTest {

    private static final List<String> REQUIRED = List.of("--features", "f.csv", "--summary", "s.json", "--manifest",
        "m.json", "--out", "/out");

    private static String[] args(String... extra) {
        List<String> all = new ArrayList<>(REQUIRED);
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    @Test
    void theFourPathsAreRequiredAndEverythingElseDefaults() {
        CreditsTrainOptions options = CreditsTrainOptions.parse(args());

        assertThat(options).isEqualTo(new CreditsTrainOptions(Path.of("f.csv"), Path.of("s.json"),
            Path.of("m.json"), Path.of("/out"), GbtParams.defaults(), false, false));
    }

    @Test
    void everyFlagIsRead() {
        CreditsTrainOptions options = CreditsTrainOptions.parse(args("--trees", "9", "--depth", "4",
            "--learning-rate", "0.25", "--bins", "64", "--min-leaf", "5", "--lambda", "2.5", "--with-dense",
            "--lit-text-variant"));

        assertThat(options.params()).isEqualTo(new GbtParams(9, 4, 0.25, 64, 5, 2.5));
        assertThat(options.withDense()).isTrue();
        assertThat(options.litTextVariant()).isTrue();
    }

    @Test
    void theSwitchesAreIndependent() {
        assertThat(CreditsTrainOptions.parse(args("--with-dense")).litTextVariant()).isFalse();
        assertThat(CreditsTrainOptions.parse(args("--lit-text-variant")).withDense()).isFalse();
        assertThat(CreditsTrainOptions.parse(args("--lit-text-variant")).litTextVariant()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"--look=L5", "--look", "--stills", "features.csv", "--dense", "with-dense"})
    void anythingElseIsAnUnknownArgument(String flag) {
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args(flag)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown argument " + flag);
    }

    @Test
    void lookIsUnknownWhateverItIsFollowedBy() {
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--look", "L5")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown argument --look");
    }

    @ParameterizedTest
    @CsvSource({"--features", "--summary", "--manifest", "--out"})
    void aMissingPathIsNamed(String flag) {
        List<String> all = new ArrayList<>(REQUIRED);
        int at = all.indexOf(flag);
        all.remove(at);
        all.remove(at);

        assertThatThrownBy(() -> CreditsTrainOptions.parse(all.toArray(String[]::new)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("missing required flag " + flag);
    }

    @Test
    void aFlagWithNoValueIsRefused() {
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--trees")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --trees needs a value");
    }

    @Test
    void aFlagGivenTwiceIsRefused() {
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--trees", "1", "--trees", "2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --trees given twice");
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--with-dense", "--with-dense")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --with-dense given twice");
    }

    @Test
    void aNumberThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--trees", "many")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --trees needs a whole number: many");
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--lambda", "x")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --lambda needs a number: x");
    }

    @Test
    void aSettingOutOfRangeIsRefusedByTheParams() {
        assertThatThrownBy(() -> CreditsTrainOptions.parse(args("--trees", "0")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("trees must be at least 1: 0");
    }
}
