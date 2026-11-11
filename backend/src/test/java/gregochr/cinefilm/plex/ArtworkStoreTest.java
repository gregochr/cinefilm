package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;

/** The artwork store: content-hash names, an image-type allow-list, temp-then-move, and name validation on read. */
class ArtworkStoreTest {

    @TempDir
    Path dir;

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void aJpegIsStoredUnderItsSha256AndTheDotJpgPath() throws Exception {
        ArtworkStore store = new ArtworkStore(dir.toString());
        byte[] bytes = "jpeg bytes".getBytes(StandardCharsets.UTF_8);

        String path = store.store(bytes, "image/jpeg");

        assertThat(path).isEqualTo("/api/art/" + sha(bytes) + ".jpg");
        assertThat(Files.readAllBytes(dir.resolve(sha(bytes) + ".jpg"))).isEqualTo(bytes);
    }

    @Test
    void aPngKeepsItsExtensionAndAContentTypeParameterIsIgnored() throws Exception {
        ArtworkStore store = new ArtworkStore(dir.toString());
        byte[] bytes = {1, 2, 3};

        assertThat(store.store(bytes, "IMAGE/PNG; charset=binary")).isEqualTo("/api/art/" + sha(bytes) + ".png");
    }

    @Test
    void anyOtherContentTypeIsRejectedAndNothingIsWritten() throws Exception {
        ArtworkStore store = new ArtworkStore(dir.toString());

        for (String type : new String[] {"image/gif", "image/svg+xml", "text/html", "application/json", "", null}) {
            assertThatThrownBy(() -> store.store(new byte[] {1}, type)).isInstanceOf(IllegalArgumentException.class);
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void theSameBytesDedupeToOneFileAndOneName() throws Exception {
        ArtworkStore store = new ArtworkStore(dir.toString());
        byte[] bytes = {5, 6, 7};

        String first = store.store(bytes, "image/jpeg");
        Path file = dir.resolve(first.substring(9));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000));
        String second = store.store(bytes, "image/jpeg");

        assertThat(second).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(1_000);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void differentBytesGetDifferentNames() {
        ArtworkStore store = new ArtworkStore(dir.toString());

        assertThat(store.store(new byte[] {1}, "image/jpeg")).isNotEqualTo(store.store(new byte[] {2}, "image/jpeg"));
    }

    @Test
    void theFolderIsCreatedOnFirstUseAndNoTemporaryFileIsLeftBehind() throws Exception {
        Path nested = dir.resolve("a/b");
        ArtworkStore store = new ArtworkStore(nested.toString());

        String path = store.store(new byte[] {9}, "image/png");

        try (Stream<Path> files = Files.list(nested)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly(path.substring(9));
        }
    }

    @Test
    void aFolderThatCannotBeCreatedIsAnIoError() throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-folder"), "x");
        ArtworkStore store = new ArtworkStore(file.resolve("art").toString());

        assertThatThrownBy(() -> store.store(new byte[] {1}, "image/jpeg")).isInstanceOf(UncheckedIOException.class)
            .hasMessage("Could not store the artwork");
    }

    @Test
    void aStoredFileIsFoundByItsExactNameOnly() {
        ArtworkStore store = new ArtworkStore(dir.toString());
        String name = store.store(new byte[] {1}, "image/jpeg").substring(9);
        String hash = name.substring(0, 64);

        assertThat(store.file(name)).contains(dir.resolve(name));
        assertThat(store.file(hash + ".png")).isEmpty();
        assertThat(store.file(hash + ".gif")).isEmpty();
        assertThat(store.file(hash.toUpperCase() + ".jpg")).isEmpty();
        assertThat(store.file(hash.substring(1) + ".jpg")).isEmpty();
        assertThat(store.file(hash + "0.jpg")).isEmpty();
        assertThat(store.file("../" + name)).isEmpty();
        assertThat(store.file("..%2f" + name)).isEmpty();
        assertThat(store.file("x/" + name)).isEmpty();
        assertThat(store.file(name + "/")).isEmpty();
        assertThat(store.file(" " + name)).isEmpty();
        assertThat(store.file("")).isEmpty();
    }

    @Test
    void aWellFormedNameWithNoFileIsEmptyAndADirectoryIsNotAFile() throws IOException {
        ArtworkStore store = new ArtworkStore(dir.toString());
        String name = "a".repeat(64) + ".jpg";
        Files.createDirectory(dir.resolve(name));

        assertThat(store.file(name)).isEmpty();
        assertThat(store.file("b".repeat(64) + ".png")).isEmpty();
    }

    @Test
    void aNameOutsideTheStoreIsNeverServedEvenWhenTheFileExistsNextToIt() throws Exception {
        Path inner = Files.createDirectory(dir.resolve("store"));
        ArtworkStore store = new ArtworkStore(inner.toString());
        String name = "c".repeat(64) + ".jpg";
        Files.write(dir.resolve(name), new byte[] {1});

        assertThat(store.file("../" + name)).isEmpty();
        assertThat(store.file(name)).isEmpty();
    }

    @Test
    void existsMeansAStoredArtPathThatIsReallyThere() {
        ArtworkStore store = new ArtworkStore(dir.toString());
        String path = store.store(new byte[] {1}, "image/jpeg");

        assertThat(store.exists(path)).isTrue();
        assertThat(store.exists("/api/art/" + "d".repeat(64) + ".jpg")).isFalse();
        assertThat(store.exists("/umSVjVdbVwtx5ryCA2QXL44Durm.jpg")).isFalse();
        assertThat(store.exists(path.substring(9))).isFalse();
        assertThat(store.exists(null)).isFalse();
    }

    @Test
    void thePngExtensionServesAsPngAndEverythingElseAsJpeg() {
        assertThat(ArtworkStore.mediaType("x.png")).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(ArtworkStore.mediaType("x.jpg")).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void theNameIsTheSha256OfTheBytesAndAMissingAlgorithmIsAnError() {
        assertThat(ArtworkStore.hash(new byte[0], "SHA-256"))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThatThrownBy(() -> ArtworkStore.hash(new byte[0], "NOPE-1")).isInstanceOf(IllegalStateException.class)
            .hasMessage("Digest algorithm unavailable: NOPE-1");
    }

    @Test
    void sweepingDeletesOnlyStoredFilesNoFilmReferencesAndLeavesOtherFilesAlone() throws Exception {
        ArtworkStore store = new ArtworkStore(dir.toString());
        String kept = store.store(new byte[] {1}, "image/jpeg");
        String orphan = store.store(new byte[] {2}, "image/png");
        Files.writeString(dir.resolve("notes.txt"), "x");

        int deleted = store.deleteUnreferenced(java.util.List.of(kept, "/tmdb.jpg", "/api/art/" + "f".repeat(64)
            + ".jpg"));

        assertThat(deleted).isEqualTo(1);
        assertThat(store.exists(kept)).isTrue();
        assertThat(store.exists(orphan)).isFalse();
        assertThat(dir.resolve("notes.txt")).exists();
    }

    @Test
    void sweepingAMissingFolderDeletesNothing() {
        assertThat(new ArtworkStore(dir.resolve("none").toString()).deleteUnreferenced(java.util.List.of())).isZero();
    }

    @Test
    void isStoredMeansPointsIntoTheStore() {
        assertThat(ArtworkStore.isStored("/api/art/x.jpg")).isTrue();
        assertThat(ArtworkStore.isStored("/api/art")).isFalse();
        assertThat(ArtworkStore.isStored("/abc.jpg")).isFalse();
        assertThat(ArtworkStore.isStored(null)).isFalse();
    }

    @Test
    void aNameThatWouldLeaveTheFolderIsEmptyAndOneInsideIsNormalised(@TempDir Path folder) {
        assertThat(ArtworkStore.within(folder, "../x.jpg")).isEmpty();
        assertThat(ArtworkStore.within(folder, "a/../../x.jpg")).isEmpty();
        assertThat(ArtworkStore.within(folder, "a/../x.jpg")).contains(folder.resolve("x.jpg"));
    }
}
