package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlexArchiveTest {

    private static PlexArchive archive(ArchiveItem... items) {
        Map<Long, ArchiveItem> byKey = new java.util.TreeMap<>();
        for (ArchiveItem item : items) {
            byKey.put(item.ratingKey(), item);
        }
        return new PlexArchive(byKey, List.of(), List.of());
    }

    private static ArchiveItem item(long key, ArchiveItem.Media... media) {
        return new ArchiveItem(key, 1_000L, List.of(media), List.of());
    }

    @Test
    void indexesAFileByItsPathRelativeToThePrefixAndNamesTheVersionThatHoldsIt() {
        ArchiveItem.Media first = new ArchiveItem.Media(1L, List.of("/lib/Movies/A.m4v"));
        ArchiveItem.Media second = new ArchiveItem.Media(2L, List.of("/lib/Movies/Sub/B.mkv"));

        Map<String, List<ArchiveMatch>> index = archive(item(5, first, second)).index("/lib/Movies/");

        assertThat(index.keySet()).containsExactlyInAnyOrder("A.m4v", "Sub/B.mkv");
        assertThat(index.get("Sub/B.mkv")).containsExactly(new ArchiveMatch(item(5, first, second), second));
    }

    @Test
    void twoItemsWithTheSameFileBothMatch() {
        ArchiveItem.Media media = new ArchiveItem.Media(1L, List.of("/lib/Movies/Nu.m4v"));

        Map<String, List<ArchiveMatch>> index = archive(item(1, media), item(2, media)).index("/lib/Movies/");

        assertThat(index.get("Nu.m4v")).extracting(m -> m.item().ratingKey()).containsExactly(1L, 2L);
    }

    @Test
    void oneItemListingAFileTwiceMatchesOnceWithItsFirstVersion() {
        ArchiveItem.Media first = new ArchiveItem.Media(1L, List.of("/lib/Movies/A.m4v", "/lib/Movies/A.m4v"));
        ArchiveItem.Media second = new ArchiveItem.Media(2L, List.of("/lib/Movies/A.m4v"));

        Map<String, List<ArchiveMatch>> index = archive(item(1, first, second)).index("/lib/Movies/");

        assertThat(index.get("A.m4v")).containsExactly(new ArchiveMatch(item(1, first, second), first));
    }

    @Test
    void filesOutsideThePrefixOrInPlexVersionsAreNotIndexed() {
        ArchiveItem.Media media = new ArchiveItem.Media(1L, List.of("/other/A.m4v", "/lib/Movies/Plex Versions/B.mp4"));

        assertThat(archive(item(1, media)).index("/lib/Movies/")).isEmpty();
    }

    @Test
    void aBlankPrefixIndexesNothing() {
        ArchiveItem.Media media = new ArchiveItem.Media(1L, List.of("/lib/Movies/A.m4v"));

        assertThat(archive(item(1, media)).index("")).isEmpty();
    }

    @Test
    void theFixtureArchiveMatchesTheDecomposedExportPathOnceNormalised() throws IOException {
        PlexArchive archive = ArchiveReader.read(LearnFixtures.dir().resolve("archive"));

        Map<String, List<ArchiveMatch>> index = archive.index("/Volumes/Test Media/Movies/");

        assertThat(index.get("Amélie.m4v")).hasSize(1);
        assertThat(index.get("Amélie.m4v")).isNull();
        assertThat(index.get("Nu.m4v")).hasSize(2);
    }
}
