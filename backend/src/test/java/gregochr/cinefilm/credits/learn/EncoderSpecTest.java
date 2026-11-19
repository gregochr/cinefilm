package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncoderSpecTest {

    private static final List<Double> MEAN = List.of(0.25, 0.5, 0.75);
    private static final List<Double> STD = List.of(0.5, 0.25, 2.0);

    private static EncoderSpec spec(String file, int size, List<Double> mean, List<Double> std, int dimension) {
        return new EncoderSpec("n", file, "ab", "in", "out", size, mean, std, dimension);
    }

    private static void refused(Path dir, String message) {
        assertThatThrownBy(() -> EncoderSpec.read(dir))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void theFixtureIsRead() throws IOException {
        EncoderSpec spec = EncoderSpec.read(TinyFixture.dir());

        assertThat(spec).isEqualTo(new EncoderSpec("tiny", "tiny.onnx", TinyFixture.SHA256, "pixel_values",
            "image_embeds", 224, MEAN, STD, 3));
    }

    @Test
    void aSha256WrittenInCapitalsStillMatches(@TempDir Path dir) throws IOException {
        Path copy = TinyFixture.withJson(dir, TinyFixture.json(TinyFixture.SHA256, TinyFixture.SHA256.toUpperCase()));

        assertThat(EncoderSpec.read(copy).name()).isEqualTo("tiny");
    }

    @Test
    void aSha256ThatDiffersIsRefusedWithBothHashes(@TempDir Path dir) throws IOException {
        String other = "0".repeat(64);
        Path copy = TinyFixture.withJson(dir, TinyFixture.json(TinyFixture.SHA256, other));

        refused(copy, "tiny.onnx has SHA-256 " + TinyFixture.SHA256 + ", encoder.json says " + other);
    }

    @Test
    void aSizeOtherThan224IsRefused(@TempDir Path dir) throws IOException {
        Path copy = TinyFixture.withJson(dir, TinyFixture.json("\"size\": 224", "\"size\": 112"));

        refused(copy, "size must be 224 to match the stored frames: 112");
    }

    @Test
    void aMissingFieldIsRefusedByName(@TempDir Path dir) throws IOException {
        Path copy = TinyFixture.withJson(dir, TinyFixture.json("  \"dimension\": 3\n", "  \"extra\": 1\n"));

        refused(copy, "encoder.json is missing dimension");
    }

    @Test
    void anUnknownFieldIsRefusedByName(@TempDir Path dir) throws IOException {
        Path copy = TinyFixture.withJson(dir, TinyFixture.json("\"dimension\": 3", "\"dimension\": 3, \"extra\": 1"));

        refused(copy, "encoder.json has an unknown field extra");
    }

    @Test
    void aFileThatIsNotAnObjectIsRefused(@TempDir Path dir) throws IOException {
        refused(TinyFixture.withJson(dir, "[]"), "encoder.json is not a JSON object");
    }

    @Test
    void aTextFieldThatIsNotAStringIsRefused(@TempDir Path dir) throws IOException {
        Path copy = TinyFixture.withJson(dir, TinyFixture.json("\"name\": \"tiny\"", "\"name\": 5"));

        refused(copy, "name must be a string");
    }

    @Test
    void aWholeNumberFieldThatIsFractionalOrTextIsRefused(@TempDir Path dir, @TempDir Path other) throws IOException {
        refused(TinyFixture.withJson(dir, TinyFixture.json("\"dimension\": 3", "\"dimension\": 3.5")),
            "dimension must be a whole number");
        refused(TinyFixture.withJson(other, TinyFixture.json("\"size\": 224", "\"size\": \"224\"")),
            "size must be a whole number");
    }

    @Test
    void aNumbersFieldThatIsNotAnArrayOrHoldsText(@TempDir Path dir, @TempDir Path other) throws IOException {
        refused(TinyFixture.withJson(dir, TinyFixture.json("[0.25, 0.5, 0.75]", "0.5")),
            "mean must be an array of numbers");
        refused(TinyFixture.withJson(other, TinyFixture.json("[0.5, 0.25, 2.0]", "[0.5, \"x\", 2.0]")),
            "std must be an array of numbers");
    }

    @Test
    void theFileMustExistBesideTheJson(@TempDir Path dir) throws IOException {
        Path copy = TinyFixture.withJson(dir, TinyFixture.json("\"tiny.onnx\"", "\"gone.onnx\""));

        assertThatThrownBy(() -> EncoderSpec.read(copy)).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void aMissingEncoderJsonIsAnIoError(@TempDir Path dir) {
        assertThatThrownBy(() -> EncoderSpec.read(dir)).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void theRecordAcceptsAnySizeAndTheSmallestDimension() {
        assertThatCode(() -> spec("m.onnx", 1, MEAN, STD, 1)).doesNotThrowAnyException();
    }

    @Test
    void theMeanAndStdAreCopiedSoTheyCannotChange() {
        List<Double> mean = new java.util.ArrayList<>(MEAN);
        EncoderSpec spec = spec("m.onnx", 2, mean, STD, 3);

        mean.set(0, 9.0);

        assertThat(spec.mean()).isEqualTo(MEAN);
    }

    @Test
    void aSizeBelowOneIsRefused() {
        assertThatThrownBy(() -> spec("m.onnx", 0, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("size must be at least 1: 0");
    }

    @Test
    void aDimensionBelowOneIsRefused() {
        assertThatThrownBy(() -> spec("m.onnx", 2, MEAN, STD, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("dimension must be at least 1: 0");
    }

    @Test
    void aFileWithAPathSeparatorOrNoNameIsRefused() {
        for (String file : new String[] {"a/b.onnx", "a\\b.onnx", " ", null}) {
            assertThatThrownBy(() -> spec(file, 2, MEAN, STD, 3))
                .as("file %s", file)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file must be a plain file name: " + file);
        }
    }

    @Test
    void aMissingOrBlankNameInputOutputOrHashIsRefused() {
        assertThatThrownBy(() -> new EncoderSpec(" ", "m.onnx", "ab", "in", "out", 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
        assertThatThrownBy(() -> new EncoderSpec("n", "m.onnx", null, "in", "out", 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
        assertThatThrownBy(() -> new EncoderSpec("n", "m.onnx", "ab", "", "out", 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
        assertThatThrownBy(() -> new EncoderSpec("n", "m.onnx", "ab", "in", null, 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
        assertThatThrownBy(() -> new EncoderSpec(null, "m.onnx", "ab", "in", "out", 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
        assertThatThrownBy(() -> new EncoderSpec("n", "m.onnx", "ab", null, "out", 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
        assertThatThrownBy(() -> new EncoderSpec("n", "m.onnx", "ab", "in", " ", 2, MEAN, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("name, sha256, input and output must be given");
    }

    @Test
    void theMeanNeedsThreeFiniteValues() {
        assertThatThrownBy(() -> spec("m.onnx", 2, List.of(0.1, 0.2), STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("mean must have three values");
        assertThatThrownBy(() -> spec("m.onnx", 2, null, STD, 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("mean must have three values");
        assertThatThrownBy(() -> spec("m.onnx", 2, List.of(0.1, Double.NaN, 0.3), STD, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("mean must be three finite values: [0.1, NaN, 0.3]");
        assertThatThrownBy(() -> spec("m.onnx", 2, List.of(0.1, 0.2, Double.POSITIVE_INFINITY), STD, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("mean must be three finite values: [0.1, 0.2, Infinity]");
        assertThatCode(() -> spec("m.onnx", 2, List.of(-1.0, 0.0, 1.0), STD, 3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> spec("m.onnx", 2, Arrays.asList(0.1, null, 0.3), STD, 3))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStdNeedsThreeFiniteValuesAboveZero() {
        assertThatThrownBy(() -> spec("m.onnx", 2, MEAN, List.of(1.0, 1.0), 3))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("std must have three values");
        assertThatThrownBy(() -> spec("m.onnx", 2, MEAN, List.of(1.0, 0.0, 1.0), 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("std must be three finite values above 0: [1.0, 0.0, 1.0]");
        assertThatThrownBy(() -> spec("m.onnx", 2, MEAN, List.of(1.0, 1.0, -0.5), 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("std must be three finite values above 0: [1.0, 1.0, -0.5]");
        assertThatThrownBy(() -> spec("m.onnx", 2, MEAN, List.of(Double.NaN, 1.0, 1.0), 3))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> spec("m.onnx", 2, MEAN, List.of(Double.MIN_VALUE, 1.0, 1.0), 3))
            .doesNotThrowAnyException();
    }
}
