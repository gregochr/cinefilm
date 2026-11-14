package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The free disk the dataset job checks, measured on the directory or on its nearest existing parent. */
class DiskSpaceTest {

    @TempDir
    Path temp;

    @Test
    void anExistingDirectoryHasItsDisksUsableSpace() throws IOException {
        assertThat(new DiskSpace().usableBytes(temp)).isEqualTo(Files.getFileStore(temp).getUsableSpace())
            .isPositive();
    }

    @Test
    void aDirectoryThatDoesNotExistYetIsMeasuredByItsNearestExistingParent() throws IOException {
        Path later = temp.resolve("not/made/yet");

        assertThat(new DiskSpace().usableBytes(later)).isPositive();
    }
}
