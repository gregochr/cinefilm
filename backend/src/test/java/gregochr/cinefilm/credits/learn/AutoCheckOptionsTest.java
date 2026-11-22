package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AutoCheckOptionsTest {

    private static final Path OPERATIONS = Path.of("..", "docs", "engineering", "operations.md");
    private static final String LOADER = "-Dloader.main=gregochr.cinefilm.credits.learn.CreditsAutoCheckMain";

    private static AutoCheckOptions parse(String... args) {
        return AutoCheckOptions.parse(args);
    }

    private static void refused(String message, String... args) {
        assertThatThrownBy(() -> parse(args)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void theRequiredFlagsAreParsedAndTheMediaRootDefaultsToMedia() {
        AutoCheckOptions options = parse("--candidates", "/in/c.json", "--out", "/out");

        assertThat(options).isEqualTo(new AutoCheckOptions(Path.of("/in/c.json"), Path.of("/out"), Path.of("/media"),
            null));
    }

    @Test
    void theMediaRootCanBeGiven() {
        assertThat(parse("--candidates", "c", "--out", "o", "--media", "/m").media()).isEqualTo(Path.of("/m"));
    }

    @Test
    void fullTakesAnEditionIdAndATile() {
        AutoCheckOptions options = parse("--candidates", "c", "--full", "123", "4", "--out", "o");

        assertThat(options.full()).isEqualTo(new AutoCheckOptions.Full(123, 4));
    }

    @Test
    void theFirstAndLastTilesAreAllowed() {
        assertThat(parse("--candidates", "c", "--out", "o", "--full", "1", "1").full().tile()).isEqualTo(1);
        assertThat(parse("--candidates", "c", "--out", "o", "--full", "1", "9").full().tile()).isEqualTo(9);
    }

    @Test
    void aTileOutsideOneToNineIsRefused() {
        refused("--full needs an edition id and a tile from 1 to 9", "--candidates", "c", "--out", "o", "--full", "1",
            "0");
        refused("--full needs an edition id and a tile from 1 to 9", "--candidates", "c", "--out", "o", "--full", "1",
            "10");
    }

    @Test
    void anEditionIdOrTileThatIsNotANumberIsRefused() {
        refused("--full needs an edition id and a tile from 1 to 9", "--full", "x", "4");
        refused("--full needs an edition id and a tile from 1 to 9", "--full", "1", "x");
    }

    @Test
    void fullWithoutBothValuesIsRefused() {
        refused("flag --full needs an edition id and a tile", "--candidates", "c", "--out", "o", "--full", "1");
        refused("flag --full needs an edition id and a tile", "--full");
    }

    @Test
    void fullGivenTwiceIsRefused() {
        refused("flag --full given twice", "--full", "1", "1", "--full", "2", "2");
    }

    @Test
    void anUnknownArgumentIsRefused() {
        refused("unknown argument --stills", "--stills");
        refused("unknown argument out", "out");
    }

    @Test
    void aFlagWithoutAValueIsRefused() {
        refused("flag --out needs a value", "--candidates", "c", "--out");
    }

    @Test
    void aFlagGivenTwiceIsRefused() {
        refused("flag --out given twice", "--out", "a", "--out", "b");
    }

    @Test
    void aMissingRequiredFlagIsRefused() {
        refused("missing required flag --candidates", "--out", "o");
        refused("missing required flag --out", "--candidates", "c");
    }

    /** The two commands in the runbook parse: the arguments after the launcher are the tool's own. */
    @Test
    void theDocumentedCommandsParseAndNameTheToolsClass() throws IOException, ClassNotFoundException {
        String text = Files.readString(OPERATIONS).replace("\\\n", " ");
        String section = text.substring(text.indexOf("### The sheet tool's command"),
            text.indexOf("### Switching on and off"));
        Matcher matcher = Pattern.compile("(?m)^docker run .*$").matcher(section);
        List<AutoCheckOptions> parsed = new ArrayList<>();
        while (matcher.find()) {
            String command = matcher.group();
            assertThat(command).contains(LOADER).contains("--network none").contains("--entrypoint nice")
                .contains("-v /mnt/workhorse-films:/media:ro")
                .contains("-v ~/auto-candidates.json:/in/auto-candidates.json:ro")
                .containsPattern("-v ~/\\S+:/out( |$)")
                .contains("ghcr.io/gregochr/cinefilm:${CINEFILM_VERSION} -n 19 java ")
                .contains("-cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher ");
            String[] tail = command.substring(command.indexOf("PropertiesLauncher") + "PropertiesLauncher".length())
                .trim().split("\\s+");
            parsed.add(AutoCheckOptions.parse(tail));
        }

        assertThat(Class.forName("gregochr.cinefilm.credits.learn.CreditsAutoCheckMain")).isNotNull();
        assertThat(parsed).hasSize(2);
        assertThat(parsed).extracting(AutoCheckOptions::candidates).containsOnly(Path.of("/in/auto-candidates.json"));
        assertThat(parsed).extracting(AutoCheckOptions::out).containsOnly(Path.of("/out"));
        assertThat(parsed.get(0).full()).isNull();
        assertThat(parsed.get(1).full()).isNotNull();
    }
}
