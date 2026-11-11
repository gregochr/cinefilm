package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** {@code GET /api/art/{name}?w=} through the whole stack: the real store, resizer, cache folder and controller. */
class ArtworkApiTest extends ApiTestBase {

    @Autowired
    private ArtworkStore store;

    @Value("${cinefilm.cache.dir}")
    private String cacheDir;

    @BeforeEach
    @AfterEach
    void clearResizedCopies() throws IOException {
        Path art = Path.of(cacheDir).resolve("art");
        if (Files.isDirectory(art)) {
            try (Stream<Path> paths = Files.walk(art)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static byte[] noisyJpeg(int width, int height, long seed) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpg", out)).isTrue();
        return out.toByteArray();
    }

    private String referenced(byte[] jpeg) {
        String path = store.store(jpeg, "image/jpeg");
        Film film = film(null, "Heat", 1995, NOW);
        film.setPosterPath(path);
        save(film);
        return path;
    }

    @Test
    void aStoredPosterAskedAt342IsASmallerJpeg342WideAndTheSecondFetchIsIdentical() throws Exception {
        byte[] original = noisyJpeg(600, 900, 11);
        String path = referenced(original);

        byte[] first = mvc.perform(get(path + "?w=342"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andReturn().getResponse().getContentAsByteArray();
        byte[] second = mvc.perform(get(path + "?w=342"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(first));
        assertThat(image.getWidth()).isEqualTo(342);
        assertThat(image.getHeight()).isEqualTo(513);
        assertThat(first.length).isLessThan(original.length);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aPosterAskedWiderThanItIsComesBackAsStored() throws Exception {
        byte[] original = noisyJpeg(600, 900, 12);
        String path = referenced(original);

        byte[] body = mvc.perform(get(path + "?w=780"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(original);
    }

    @Test
    void aBadWidthIs400AndNoWidthIsTheStoredFile() throws Exception {
        byte[] original = noisyJpeg(100, 100, 13);
        String path = referenced(original);

        mvc.perform(get(path + "?w=343"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Width must be 342, 500, 780 or 1280"));
        byte[] body = mvc.perform(get(path)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(original);
    }
}
