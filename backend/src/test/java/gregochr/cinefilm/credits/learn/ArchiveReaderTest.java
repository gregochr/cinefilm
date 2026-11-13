package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveReaderTest {

    @TempDir
    Path temp;

    @Test
    void readsTheFixtureArchive() throws IOException {
        PlexArchive archive = ArchiveReader.read(LearnFixtures.dir().resolve("archive"));

        assertThat(archive.items().keySet()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 13L, 14L,
            15L, 16L, 17L, 18L, 19L, 20L);
        assertThat(archive.listedWithoutItem()).containsExactly(12L);
        assertThat(archive.unexpectedFiles()).containsExactly("item-21.json.tmp", "item-notes.json");
    }

    @Test
    void anItemKeepsEveryMarkerOfEveryTypeWithItsFlagsAndTheVersionsFiles() throws IOException {
        ArchiveItem alpha = ArchiveReader.read(LearnFixtures.dir().resolve("archive")).items().get(1L);

        ArchiveItem.Media media = new ArchiveItem.Media(6_000_000L, List.of("/Volumes/Test Media/Movies/Alpha.m4v"));
        List<ArchiveItem.Marker> markers = List.of(new ArchiveItem.Marker("intro", 100_000L, 200_000L, false),
            new ArchiveItem.Marker("credits", 4_800_000L, 5_400_000L, true));
        assertThat(alpha).isEqualTo(new ArchiveItem(1, 6_000_000L, List.of(media), markers));
    }

    @Test
    void aMarkerWithNoEndHasANullEnd() throws IOException {
        ArchiveItem epsilon = ArchiveReader.read(LearnFixtures.dir().resolve("archive")).items().get(5L);

        assertThat(epsilon.markers()).containsExactly(new ArchiveItem.Marker("credits", 4_800_000L, null, false));
    }

    @Test
    void anItemWithTwoVersionsKeepsBothInOrder() throws IOException {
        ArchiveItem eta = ArchiveReader.read(LearnFixtures.dir().resolve("archive")).items().get(7L);

        assertThat(eta.media()).containsExactly(
            new ArchiveItem.Media(6_002_000L, List.of("/Volumes/Test Media/Movies/Eta.m4v")),
            new ArchiveItem.Media(6_001_000L, List.of("/Volumes/Test Media/Movies/Eta Two.mkv")));
    }

    @Test
    void aMissingOrNonNumericDurationIsNull() {
        ArchiveItem item = ArchiveReader.parseItem(3, "item-3.json",
            "{\"MediaContainer\":{\"Metadata\":[{\"duration\":\"x\",\"Media\":[{\"Part\":[{\"size\":1}]}]}]}}");

        assertThat(item).isEqualTo(new ArchiveItem(3, null, List.of(new ArchiveItem.Media(null, List.of())),
            List.of()));
    }

    @Test
    void anItemFileWithNoItemIsAnError() {
        assertThatThrownBy(() -> ArchiveReader.parseItem(3, "item-3.json", "{\"MediaContainer\":{}}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("item-3.json has no item in MediaContainer.Metadata");
    }

    @Test
    void invalidJsonIsAnErrorNamingTheFile() {
        assertThatThrownBy(() -> ArchiveReader.parseItem(3, "item-3.json", "{\"MediaContainer\":"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("item-3.json is not valid JSON");
    }

    @Test
    void aSectionEntryWithoutANumericRatingKeyIsAnError() {
        assertThatThrownBy(() -> ArchiveReader.listedKeys("section-1.json",
            "{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":\"12\"},{\"title\":\"x\"}]}}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("section-1.json lists an item with no numeric ratingKey");
    }

    @Test
    void aSectionListsItsRatingKeysAsTextOrNumbers() {
        assertThat(ArchiveReader.listedKeys("section-1.json",
            "{\"MediaContainer\":{\"Metadata\":[{\"ratingKey\":\"12\"},{\"ratingKey\":13}]}}"))
            .containsExactly(12L, 13L);
    }

    @Test
    void aBadItemFileInTheDirectoryFailsTheRead() throws IOException {
        Files.writeString(temp.resolve("item-5.json"), "nope");

        assertThatThrownBy(() -> ArchiveReader.read(temp))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("item-5.json is not valid JSON");
    }

    @Test
    void aMissingDirectoryIsAnIoError() {
        assertThatThrownBy(() -> ArchiveReader.read(temp.resolve("nothing")))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void otherFilesAreIgnoredAndAnEmptyDirectoryHasNothing() throws IOException {
        Files.writeString(temp.resolve("sections.json"), "{}");
        Files.writeString(temp.resolve("README.txt"), "x");

        PlexArchive archive = ArchiveReader.read(temp);

        assertThat(archive.items()).isEmpty();
        assertThat(archive.listedWithoutItem()).isEmpty();
        assertThat(archive.unexpectedFiles()).isEmpty();
    }
}
