package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;

class LabelsFileTest {

    @TempDir
    Path temp;

    @Test
    void whatTheLabelsToolWritesIsReadBack() throws IOException {
        Path fixtures = LearnFixtures.dir();
        int code = LabelsMain.run(new String[] {"--archive", fixtures.resolve("archive").toString(), "--export",
            fixtures.resolve("export.csv").toString(), "--preseed", fixtures.resolve("preseed.txt").toString(),
            "--prefix", LearnFixtures.PREFIX, "--date", "2026-11-13", "--out", temp.toString()},
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(code).isZero();

        Map<Long, LabelRecord> labels = LabelsFile.read(temp.resolve("labels-2026-11-13.json"));

        assertThat(labels).hasSize(19);
        assertThat(labels.values()).extracting(LabelRecord::side).contains(Side.DEV, Side.TEST);
        assertThat(labels.values()).extracting(LabelRecord::excluded).containsNull();
        assertThat(labels.values().stream().filter(label -> label.excluded() != null)).isNotEmpty();
        assertThat(new ArrayList<>(labels.keySet())).isSorted();
    }

    @Test
    void aRecordIsReadWithItsIdSideAndExclusion() throws IOException {
        Path file = MeasureFixtures.labelsFile(temp.resolve("l.json"),
            MeasureFixtures.label(7, Side.DEV, null),
            MeasureFixtures.label(3, Side.TEST, ExcludeReason.NO_ARCHIVE_ITEM));

        Map<Long, LabelRecord> labels = LabelsFile.read(file);

        assertThat(labels.keySet()).containsExactly(3L, 7L);
        assertThat(labels.get(7L).side()).isEqualTo(Side.DEV);
        assertThat(labels.get(7L).excluded()).isNull();
        assertThat(labels.get(3L).side()).isEqualTo(Side.TEST);
        assertThat(labels.get(3L).excluded()).isEqualTo(ExcludeReason.NO_ARCHIVE_ITEM);
    }

    @Test
    void anEmptyArrayIsNoLabels() throws IOException {
        assertThat(LabelsFile.read(MeasureFixtures.labelsFile(temp.resolve("l.json")))).isEmpty();
    }

    @Test
    void aMissingFileIsAnIoFailure() {
        assertThatThrownBy(() -> LabelsFile.read(temp.resolve("nope.json"))).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void somethingThatIsNotAnArrayOfLabelsIsRefused() throws IOException {
        Path file = temp.resolve("l.json");
        for (String json : new String[] {"{}", "{\"films\":[]}", "[1,2]", "not json", "\"x\""}) {
            Files.writeString(file, json);

            assertThatThrownBy(() -> LabelsFile.read(file)).as(json).isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void aNullDocumentOrEntryIsRefusedWithoutNamingAnId() throws IOException {
        Path file = temp.resolve("l.json");
        Files.writeString(file, "null");
        assertThatThrownBy(() -> LabelsFile.read(file)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("labels file is not a JSON array");

        Files.writeString(file, "[null]");
        assertThatThrownBy(() -> LabelsFile.read(file)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("labels file has a null entry");
    }

    @Test
    void aFileListedTwiceIsRefusedWithoutNamingIt() throws IOException {
        Path file = MeasureFixtures.labelsFile(temp.resolve("l.json"), MeasureFixtures.label(4242, Side.TEST, null),
            MeasureFixtures.label(4242, Side.TEST, null));

        assertThatThrownBy(() -> LabelsFile.read(file)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("labels file repeats a media file");
    }
}
