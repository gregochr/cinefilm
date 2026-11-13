package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LabelsOptionsTest {

    private static final List<String> REQUIRED = List.of("--archive", "a", "--export", "e.csv", "--preseed", "p.txt",
        "--prefix", "/Volumes/Media Library/Movies", "--date", "2026-11-13", "--out", "o");

    private static String[] args(String... more) {
        List<String> all = new ArrayList<>(REQUIRED);
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    @Test
    void parsesTheRequiredFlagsAndNormalisesThePrefix() {
        assertThat(LabelsOptions.parse(args())).isEqualTo(new LabelsOptions(Path.of("a"), Path.of("e.csv"),
            Path.of("p.txt"), "/Volumes/Media Library/Movies/", LocalDate.of(2026, 11, 13), Path.of("o"), null, null));
    }

    @Test
    void parsesTheOptionalFlagsInAnyOrder() {
        LabelsOptions options = LabelsOptions.parse(args("--exclusions", "x.csv", "--previous-split", "s.json"));

        assertThat(options.previousSplit()).isEqualTo(Path.of("s.json"));
        assertThat(options.exclusions()).isEqualTo(Path.of("x.csv"));
    }

    @Test
    void everyRequiredFlagIsRequired() {
        for (int i = 0; i < REQUIRED.size(); i += 2) {
            List<String> without = new ArrayList<>(REQUIRED);
            String name = without.get(i);
            without.remove(i);
            without.remove(i);
            assertThatThrownBy(() -> LabelsOptions.parse(without.toArray(String[]::new)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("missing required flag " + name);
        }
    }

    @Test
    void anUnknownFlagIsAnError() {
        assertThatThrownBy(() -> LabelsOptions.parse(args("--look", "L5")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown argument --look");
    }

    @Test
    void aWordWithoutDashesIsAnUnknownArgument() {
        assertThatThrownBy(() -> LabelsOptions.parse(args("archive", "b")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown argument archive");
    }

    @Test
    void aFlagWithoutAValueIsAnError() {
        assertThatThrownBy(() -> LabelsOptions.parse(args("--exclusions")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --exclusions needs a value");
    }

    @Test
    void aRepeatedFlagIsAnError() {
        assertThatThrownBy(() -> LabelsOptions.parse(args("--out", "other")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("flag --out given twice");
    }

    @Test
    void aBadDateIsAnError() {
        String[] bad = REQUIRED.toArray(String[]::new);
        bad[9] = "13/11/2026";

        assertThatThrownBy(() -> LabelsOptions.parse(bad))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("--date must be yyyy-MM-dd");
    }

    @Test
    void aBlankPrefixIsAnError() {
        String[] bad = REQUIRED.toArray(String[]::new);
        bad[7] = "  ";

        assertThatThrownBy(() -> LabelsOptions.parse(bad))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("--prefix must not be blank");
    }
}
