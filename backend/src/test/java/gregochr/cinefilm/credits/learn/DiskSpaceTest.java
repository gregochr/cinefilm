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
        // Other processes write to the disk between the two reads, so allow a little drift.
        assertThat(new DiskSpace().usableBytes(temp))
            .isCloseTo(Files.getFileStore(temp).getUsableSpace(), org.assertj.core.data.Offset.offset(100_000_000L))
            .isPositive();
    }

    @Test
    void aDirectoryThatDoesNotExistYetIsMeasuredByItsNearestExistingParent() throws IOException {
        Path later = temp.resolve("not/made/yet");

        assertThat(new DiskSpace().usableBytes(later)).isPositive();
    }
}
