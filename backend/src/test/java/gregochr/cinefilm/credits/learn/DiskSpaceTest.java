package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The disk the dataset job checks: the one under the directory, or under its nearest existing parent. */
class DiskSpaceTest {

    @TempDir
    Path temp;

    @Test
    void anExistingDirectoryIsOnItsOwnDisk() throws IOException {
        assertThat(new DiskSpace().store(temp)).isEqualTo(Files.getFileStore(temp));
    }

    @Test
    void aDirectoryThatDoesNotExistYetIsOnTheDiskOfItsNearestExistingParent() throws IOException {
        assertThat(new DiskSpace().store(temp.resolve("not/made/yet"))).isEqualTo(Files.getFileStore(temp));
    }

    @Test
    void theUsableBytesOfADiskArePositive() throws IOException {
        assertThat(new DiskSpace().usableBytes(temp)).isPositive();
    }
}
