package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The dataset job's settings: paths made absolute, the disk floor in 10^9 byte gigabytes, the overlap check. */
class DatasetPropertiesTest {

    private static DatasetProperties props(String dir, String cache) {
        return new DatasetProperties(true, dir, "/cache/credits-dataset-out", 19, 15, "ffmpeg", cache);
    }

    @Test
    void theFramesAndTheMarkerLiveUnderTheDatasetDirectory() {
        DatasetProperties props = props("/cache/credits-dataset", "/cache");

        assertThat(props.enabled()).isTrue();
        assertThat(props.dir()).isEqualTo(Path.of("/cache/credits-dataset"));
        assertThat(props.framesDir()).isEqualTo(Path.of("/cache/credits-dataset/frames"));
        assertThat(props.marker()).isEqualTo(Path.of("/cache/credits-dataset/running"));
        assertThat(props.outDir()).isEqualTo(Path.of("/cache/credits-dataset-out"));
        assertThat(props.nice()).isEqualTo(19);
        assertThat(props.ffmpeg()).isEqualTo("ffmpeg");
    }

    @Test
    void theDiskFloorIsInGigabytesOfATenToTheNinthBytes() {
        assertThat(props("/cache/credits-dataset", "/cache").minFreeBytes()).isEqualTo(15_000_000_000L);
    }

    @Test
    void pathsAreMadeAbsoluteAndNormalised() {
        DatasetProperties props = props("data/../data/set", "/cache");

        assertThat(props.dir()).isEqualTo(Path.of("data/set").toAbsolutePath());
    }

    @Test
    void theReaderDirectoryIsTheCreditsFolderOfTheCache() {
        assertThat(props("/cache/credits-dataset", "/cache").readerDir()).isEqualTo(Path.of("/cache/credits"));
    }

    @Test
    void aDatasetDirectoryInsideTheReadersDirectoryOverlapsIt() {
        assertThat(props("/cache/credits/dataset", "/cache").overlapsReaderDir()).isTrue();
        assertThat(props("/cache/credits", "/cache").overlapsReaderDir()).isTrue();
    }

    @Test
    void anOutputDirectoryInsideTheReadersDirectoryOverlapsItToo() {
        DatasetProperties props = new DatasetProperties(true, "/cache/credits-dataset", "/cache/credits/out", 19, 15,
            "ffmpeg", "/cache");

        assertThat(props.overlapsReaderDir()).isTrue();
    }

    @Test
    void aSiblingWhoseNameOnlyStartsTheSameDoesNotOverlap() {
        assertThat(props("/cache/credits-dataset", "/cache").overlapsReaderDir()).isFalse();
        assertThat(props("/elsewhere/credits", "/cache").overlapsReaderDir()).isFalse();
    }
}
