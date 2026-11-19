package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CreditsEmbedOptionsTest {

    private static final String[] FOUR = {"--data", "/d", "--features", "/f", "--encoder", "/e", "--out", "/o"};

    private static void refused(String message, String... args) {
        assertThatThrownBy(() -> CreditsEmbedOptions.parse(args)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
    }

    private static String[] with(String... more) {
        String[] all = new String[FOUR.length + more.length];
        System.arraycopy(FOUR, 0, all, 0, FOUR.length);
        System.arraycopy(more, 0, all, FOUR.length, more.length);
        return all;
    }

    @Test
    void theFourRequiredFlagsGiveNoLookAndLookEqualsL5GivesIt() {
        assertThat(CreditsEmbedOptions.parse(FOUR)).isEqualTo(new CreditsEmbedOptions(Path.of("/d"), Path.of("/f"),
            Path.of("/e"), Path.of("/o"), false));
        assertThat(CreditsEmbedOptions.parse(with("--look=L5")).look()).isTrue();
    }

    @Test
    void aBadLookIsRefused() {
        refused("flag --look needs a value: --look=L5", with("--look"));
        refused("--look must be L5: --look=L4", with("--look=L4"));
        refused("--look must be L5: --look=", with("--look="));
        refused("flag --look given twice", with("--look=L5", "--look=L5"));
    }

    @Test
    void aMissingRequiredFlagIsRefused() {
        refused("missing required flag --data", "--features", "/f", "--encoder", "/e", "--out", "/o");
        refused("missing required flag --features", "--data", "/d", "--encoder", "/e", "--out", "/o");
        refused("missing required flag --encoder", "--data", "/d", "--features", "/f", "--out", "/o");
        refused("missing required flag --out", "--data", "/d", "--features", "/f", "--encoder", "/e");
    }

    @Test
    void anUnknownBareOrRepeatedFlagIsRefused() {
        refused("unknown argument --ids", with("--ids", "1"));
        refused("unknown argument data", with("data", "/x"));
        refused("flag --out needs a value", "--data", "/d", "--features", "/f", "--encoder", "/e", "--out");
        refused("flag --data given twice", with("--data", "/x"));
    }
}
