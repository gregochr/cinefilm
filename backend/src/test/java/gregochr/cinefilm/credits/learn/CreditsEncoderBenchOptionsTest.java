package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CreditsEncoderBenchOptionsTest {

    private static void refused(String message, String... args) {
        assertThatThrownBy(() -> CreditsEncoderBenchOptions.parse(args))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void theTwoRequiredFlagsGiveOneFilmsFramesAndSixteenWarmupFrames() {
        CreditsEncoderBenchOptions options = CreditsEncoderBenchOptions.parse(
            new String[] {"--encoder", "/enc", "--out", "/out"});

        assertThat(options).isEqualTo(new CreditsEncoderBenchOptions(Path.of("/enc"), Path.of("/out"), 520, 16));
    }

    @Test
    void framesAndWarmupAreTakenAtTheirBounds() {
        assertThat(CreditsEncoderBenchOptions.parse(
            new String[] {"--out", "/o", "--encoder", "/e", "--frames", "1", "--warmup", "0"}))
            .isEqualTo(new CreditsEncoderBenchOptions(Path.of("/e"), Path.of("/o"), 1, 0));
        assertThat(CreditsEncoderBenchOptions.parse(
            new String[] {"--out", "/o", "--encoder", "/e", "--frames", "10000", "--warmup", "1000"}))
            .isEqualTo(new CreditsEncoderBenchOptions(Path.of("/e"), Path.of("/o"), 10_000, 1_000));
    }

    @Test
    void framesOutsideOneToTenThousandAreRefused() {
        refused("flag --frames must be 1 to 10000: 0", "--encoder", "/e", "--out", "/o", "--frames", "0");
        refused("flag --frames must be 1 to 10000: 10001", "--encoder", "/e", "--out", "/o", "--frames", "10001");
        refused("flag --frames must be 1 to 10000: -5", "--encoder", "/e", "--out", "/o", "--frames", "-5");
    }

    @Test
    void warmupOutsideZeroToAThousandIsRefused() {
        refused("flag --warmup must be 0 to 1000: -1", "--encoder", "/e", "--out", "/o", "--warmup", "-1");
        refused("flag --warmup must be 0 to 1000: 1001", "--encoder", "/e", "--out", "/o", "--warmup", "1001");
    }

    @Test
    void aNumberThatIsNotAWholeNumberIsRefused() {
        refused("flag --frames needs a whole number: many", "--encoder", "/e", "--out", "/o", "--frames", "many");
        refused("flag --warmup needs a whole number: 1.5", "--encoder", "/e", "--out", "/o", "--warmup", "1.5");
    }

    @Test
    void aMissingRequiredFlagIsRefused() {
        refused("missing required flag --encoder", "--out", "/o");
        refused("missing required flag --out", "--encoder", "/e");
    }

    @Test
    void anUnknownBareOrRepeatedFlagIsRefused() {
        refused("unknown argument --threads", "--encoder", "/e", "--out", "/o", "--threads", "2");
        refused("unknown argument encoder", "encoder", "/e");
        refused("flag --out given twice", "--encoder", "/e", "--out", "/o", "--out", "/p");
    }

    @Test
    void aFlagWithNoValueIsRefused() {
        refused("flag --out needs a value", "--encoder", "/e", "--out");
    }
}
