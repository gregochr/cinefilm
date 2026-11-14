package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The shared parser itself; {@link PgmReaderTest} and {@link PpmReaderTest} cover each format's messages. */
class NetpbmTest {

    private static byte[] file(String header, int pixelBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < pixelBytes; i++) {
            out.write(i);
        }
        return out.toByteArray();
    }

    @Test
    void readsTheSizeAndAFreshCopyOfThePixelsForOneAndThreeChannels() {
        Netpbm.Image grey = Netpbm.read(file("P5\n2 2\n255\n", 4), "PGM", "P5", 1);
        Netpbm.Image colour = Netpbm.read(file("P6\n2 1\n255\n", 6), "PPM", "P6", 3);

        assertThat(grey.width()).isEqualTo(2);
        assertThat(grey.height()).isEqualTo(2);
        assertThat(grey.pixels()).containsExactly(0, 1, 2, 3);
        assertThat(colour.width()).isEqualTo(2);
        assertThat(colour.height()).isEqualTo(1);
        assertThat(colour.pixels()).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    void theNameAndMagicOfTheFormatAppearInTheMessages() {
        assertThatThrownBy(() -> Netpbm.read(file("P6\n1 1\n255\n", 3), "PGM", "P5", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Only binary PGM (P5) is supported, found P6");
        assertThatThrownBy(() -> Netpbm.read(new byte[0], "PPM", "P6", 3)).hasMessage("The PPM file is empty");
        assertThatThrownBy(() -> Netpbm.read(file("P6\n1 1\n255\n", 2), "PPM", "P6", 3))
            .hasMessage("PPM data is truncated: 2 of 3 pixel bytes");
    }

    @Test
    void theChannelCountDecidesHowManyPixelBytesAreExpected() {
        assertThatThrownBy(() -> Netpbm.read(file("P5\n1 1\n255\n", 3), "PGM", "P5", 1))
            .hasMessage("PGM has 2 bytes after the pixels");
        assertThat(Netpbm.read(file("P5\n1 1\n255\n", 3), "PGM", "P5", 3).pixels()).hasSize(3);
    }
}
