package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioSegmentFilesTest {

    @TempDir
    Path dir;

    private final NioSegmentFiles files = new NioSegmentFiles();
    private Path locked;

    @AfterEach
    void unlock() throws IOException {
        if (locked != null) {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private Path touch(String relative, int bytes) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[bytes]);
    }

    /** A directory the process cannot read; the test is skipped when permissions do not apply (running as root). */
    private Path unreadableDir() throws IOException {
        locked = Files.createDirectories(dir.resolve("locked"));
        Files.write(locked.resolve("seg-1.ts"), new byte[3]);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(locked), "permissions are not enforced for this user");
        return locked;
    }

    @Test
    void existsIsTrueForARegularFileOnly() throws IOException {
        Path file = touch("a/seg-0.ts", 1);

        assertThat(files.exists(file)).isTrue();
        assertThat(files.exists(dir.resolve("a"))).isFalse();
        assertThat(files.exists(dir.resolve("a/seg-1.ts"))).isFalse();
    }

    @Test
    void deleteRemovesAFileAndToleratesAMissingOneAndAFolderItCannotRemove() throws IOException {
        Path file = touch("a/seg-0.ts", 1);

        files.delete(file);
        files.delete(file);
        files.delete(dir.resolve("a/nothing"));
        touch("full/seg-0.ts", 1);
        files.delete(dir.resolve("full"));

        assertThat(file).doesNotExist();
        assertThat(dir.resolve("full/seg-0.ts")).exists();
    }

    @Test
    void deleteRangeRemovesSegmentsFromFirstToLastInclusiveAndNothingElse() throws IOException {
        for (int n = 0; n < 8; n++) {
            touch("r/seg-" + n + ".ts", 1);
        }
        touch("r/seg-x.ts", 1);
        touch("r/index.m3u8", 1);
        touch("other/seg-4.ts", 1);

        files.deleteRange(dir.resolve("r"), 3, 5);

        assertThat(dir.resolve("r/seg-2.ts")).exists();
        assertThat(dir.resolve("r/seg-3.ts")).doesNotExist();
        assertThat(dir.resolve("r/seg-5.ts")).doesNotExist();
        assertThat(dir.resolve("r/seg-6.ts")).exists();
        assertThat(dir.resolve("r/seg-7.ts")).exists();
        assertThat(dir.resolve("r/seg-x.ts")).exists();
        assertThat(dir.resolve("r/index.m3u8")).exists();
        assertThat(dir.resolve("other/seg-4.ts")).exists();
        assertThat(dir.resolve("r/seg-0.ts")).exists();
    }

    @Test
    void deleteRangeOfAMissingFolderDoesNothing() {
        files.deleteRange(dir.resolve("missing"), 0, 9);

        assertThat(dir.resolve("missing")).doesNotExist();
    }

    @Test
    void deleteRangeOfAnUnreadableFolderIsLoggedNotThrown() throws IOException {
        Path folder = unreadableDir();

        files.deleteRange(folder, 0, 9);

        assertThat(folder).exists();
    }

    @Test
    void deleteTreeRemovesTheFolderAndEverythingUnderIt() throws IOException {
        touch("s/original/seg-0.ts", 1);
        touch("s/720p/seg-0.ts", 1);

        files.deleteTree(dir.resolve("s"));

        assertThat(dir.resolve("s")).doesNotExist();
        assertThat(dir).exists();
    }

    @Test
    void deleteTreeOfAMissingOrUnreadableFolderIsHarmless() throws IOException {
        files.deleteTree(dir.resolve("missing"));
        Path folder = unreadableDir();

        files.deleteTree(folder);

        assertThat(folder).exists();
    }

    @Test
    void directoriesListsOnlyTheSubFolders() throws IOException {
        touch("hls/a/seg-0.ts", 1);
        touch("hls/b/seg-0.ts", 1);
        touch("hls/stray.txt", 1);

        assertThat(files.directories(dir.resolve("hls"))).containsExactlyInAnyOrder(dir.resolve("hls/a"),
            dir.resolve("hls/b"));
    }

    @Test
    void directoriesOfAMissingOrUnreadableFolderIsEmpty() throws IOException {
        assertThat(files.directories(dir.resolve("missing"))).isEmpty();
        Path folder = unreadableDir();

        assertThat(files.directories(folder)).isEmpty();
    }

    @Test
    void sizeAddsUpEveryFileUnderTheFolder() throws IOException {
        touch("hls/a/original/seg-0.ts", 100);
        touch("hls/a/720p/seg-0.ts", 20);
        touch("hls/b/seg-0.ts", 3);

        assertThat(files.size(dir.resolve("hls"))).isEqualTo(123);
        assertThat(files.size(dir.resolve("hls/a"))).isEqualTo(120);
    }

    @Test
    void sizeOfAMissingOrUnreadableFolderIsZero() throws IOException {
        assertThat(files.size(dir.resolve("missing"))).isZero();
        Path folder = unreadableDir();

        assertThat(files.size(folder)).isZero();
    }
}
