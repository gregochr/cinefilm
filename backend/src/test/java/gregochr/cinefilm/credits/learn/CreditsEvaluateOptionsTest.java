package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CreditsEvaluateOptionsTest {

    private static final String[] BASE = {"--features", "f", "--out", "o", "--train", "a"};

    private static String[] with(String... extra) {
        return Stream.concat(Stream.of(BASE), Stream.of(extra)).toArray(String[]::new);
    }

    @Test
    void everyFlagIsRead() {
        CreditsEvaluateOptions options = CreditsEvaluateOptions.parse(with("--train", "b/", "--look=L5",
            "--baseline", "v2.csv"));

        assertThat(options.features()).isEqualTo(Path.of("f"));
        assertThat(options.out()).isEqualTo(Path.of("o"));
        assertThat(options.train()).containsExactly(Path.of("a").toAbsolutePath().normalize(),
            Path.of("b").toAbsolutePath().normalize());
        assertThat(options.look()).isTrue();
        assertThat(options.baseline()).isEqualTo(Path.of("v2.csv"));
    }

    @Test
    void theLookAndTheBaselineAreOffByDefault() {
        CreditsEvaluateOptions options = CreditsEvaluateOptions.parse(BASE);

        assertThat(options.look()).isFalse();
        assertThat(options.baseline()).isNull();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of(new String[] {"--out", "o", "--train", "a"}, "missing required flag --features"),
            Arguments.of(new String[] {"--features", "f", "--train", "a"}, "missing required flag --out"),
            Arguments.of(new String[] {"--features", "f", "--out", "o"}, "missing required flag --train"),
            Arguments.of(with("--manifest", "m"), "unknown argument --manifest"),
            Arguments.of(with("features", "f"), "unknown argument features"),
            Arguments.of(with("--out", "p"), "flag --out given twice"),
            Arguments.of(new String[] {"--features", "f", "--train", "a", "--out"}, "flag --out needs a value"),
            Arguments.of(with("--look"), "flag --look needs a value: --look=L5"),
            Arguments.of(with("--look=L6"), "--look must be L5: --look=L6"),
            Arguments.of(with("--look=L5", "--look=L5"), "flag --look given twice"),
            Arguments.of(with("--baseline"), "flag --baseline needs a value"),
            Arguments.of(with("--baseline", "v2.csv"), "--baseline needs --look=L5"),
            Arguments.of(with("--train", "x/a"), "the same training run is given twice: a"));
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void aBadCommandLineIsRefusedWithAOneLineMessage(String[] args, String message) {
        assertThatThrownBy(() -> CreditsEvaluateOptions.parse(args)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
    }
}
