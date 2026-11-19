package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameSourceTest {

    @TempDir
    Path temp;

    private final FrameSource source = FrameSource.disk();
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = FeaturesFixtures.okFilm(temp, "1", 1);
    }

    @Test
    void completeBytesAndJpegsAreTheStoredFilesBytes() throws IOException {
        assertThat(source.completeBytes(dir)).isEqualTo(Files.readAllBytes(dir.resolve("complete.json")));
        assertThat(source.jpeg(dir, "dense", 98_500)).isEqualTo(Files.readAllBytes(dir.resolve("dense/98500.jpg")));
        assertThatThrownBy(() -> source.jpeg(dir, "coarse", 98_500)).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void aJpegIsThereOnlyAsARegularFileOfItsOwnKind() throws IOException {
        assertThat(source.hasJpeg(dir, "coarse", 115_000)).isTrue();
        assertThat(source.hasJpeg(dir, "dense", 115_000)).isFalse();
        Files.delete(dir.resolve("dense/98500.jpg"));
        Files.createDirectory(dir.resolve("dense/98500.jpg"));
        assertThat(source.hasJpeg(dir, "dense", 98_500)).isFalse();
    }
}
