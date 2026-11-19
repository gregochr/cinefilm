package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TinyOnnxTest {

    @Test
    void theCommittedFixtureIsWhatTinyOnnxWrites() throws IOException {
        byte[] committed = Files.readAllBytes(TinyFixture.dir().resolve("tiny.onnx"));

        assertThat(committed).isEqualTo(TinyOnnx.bytes());
    }

    @Test
    void theCommittedEncoderJsonNamesTheFixturesHash() throws IOException {
        EncoderSpec spec = EncoderSpec.read(TinyFixture.dir());

        assertThat(spec.sha256()).isEqualTo(TinyFixture.SHA256);
    }

    @Test
    void mainWritesTheModelToThePathGiven(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.onnx");

        TinyOnnx.main(new String[] {file.toString()});

        assertThat(Files.readAllBytes(file)).isEqualTo(TinyOnnx.bytes());
    }

    @Test
    void theModelStartsWithIrVersion7AndEndsWithOpset13() {
        byte[] bytes = TinyOnnx.bytes();

        assertThat(bytes[0]).isEqualTo((byte) 0x08);
        assertThat(bytes[1]).isEqualTo((byte) 7);
        assertThat(bytes[bytes.length - 4]).isEqualTo((byte) 0x42);
        assertThat(bytes[bytes.length - 3]).isEqualTo((byte) 2);
        assertThat(bytes[bytes.length - 2]).isEqualTo((byte) 0x10);
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 13);
    }
}
