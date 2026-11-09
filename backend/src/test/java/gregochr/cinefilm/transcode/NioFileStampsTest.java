package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioFileStampsTest {

    @TempDir
    Path dir;

    @Test
    void theStampIsTheSizeAndTheModificationTimeInMilliseconds() throws IOException {
        Path file = dir.resolve("a.mp4");
        Files.writeString(file, "12345");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_123_000L));

        assertThat(new NioFileStamps().read(file)).isEqualTo(new FileStamps.Stamp(5, 1_700_000_123_000L));
    }
}
