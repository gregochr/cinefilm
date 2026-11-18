package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilmSourceTest {

    @TempDir
    Path temp;

    private final FilmSource source = FilmSource.disk();
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = FeaturesFixtures.okFilm(temp, "1", 1);
    }

    @Test
    void completeIsTheDirectorysCompleteJson() {
        assertThat(source.complete(dir)).hasValueSatisfying(complete -> {
            assertThat(complete.mediaFileId()).isEqualTo(1);
            assertThat(complete.fingerprint()).isEqualTo(FeaturesFixtures.FINGERPRINT);
        });
    }

    @Test
    void completeIsEmptyForADirectoryWithoutOne() throws IOException {
        assertThat(source.complete(Files.createDirectories(temp.resolve("2")))).isEmpty();
    }

    @Test
    void completeBytesAreTheFilesBytes() throws IOException {
        assertThat(source.completeBytes(dir)).isEqualTo(Files.readAllBytes(dir.resolve("complete.json")));
    }

    @Test
    void checkHandsEverySoundFrameToTheSink() throws IOException {
        List<String> seen = new ArrayList<>();

        FilmCheck.Result result = source.check(dir, frame -> seen.add(frame.kind()));

        assertThat(result.status()).isEqualTo(FilmCheck.Status.OK);
        assertThat(seen).hasSize(8 + 25);
    }

    @Test
    void greyIsTheDecodedStoredFrame() throws IOException {
        GreyFrame grey = source.grey(dir, "coarse", 115_000);

        assertThat(grey.width()).isEqualTo(16);
        assertThat(grey.height()).isEqualTo(16);
        assertThat(grey.luma(3, 4)).isEqualTo(60);
    }
}
