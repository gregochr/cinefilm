package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.CreditsFrameReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code complete.json}: written last and atomically, and current only with the right format and fingerprint. */
class DatasetCompleteTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final DatasetComplete.Mover MOVE = (from, to) ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);

    @TempDir
    Path temp;

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(temp.resolve("7"));
    }

    private DatasetComplete complete(String fingerprint) {
        return new DatasetComplete(1, 7L, fingerprint, 600_000L, 570_000L,
            new DatasetComplete.Window(360_000L, 600_000L), new DatasetComplete.Frame(480, 270),
            new DatasetComplete.Area(0, 34, 480, 202), List.of(180_000L, 225_000L), List.of(360_000L),
            List.of(540_000L), "ffmpeg version test", "25.0.1", "abc123", DatasetComplete.Settings.current(19),
            "2024-03-31T23:30:00Z");
    }

    private void writeRaw(String json) throws IOException {
        Files.writeString(dir.resolve("complete.json"), json);
    }

    @Test
    void aWrittenFileIsCurrentForItsOwnFingerprintOnly() throws IOException {
        DatasetComplete.write(dir, complete("1000:1"));

        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isTrue();
        assertThat(DatasetComplete.isCurrent(dir, "1000:2")).isFalse();
    }

    @Test
    void theTemporaryFileIsGoneAndTheFramesAreUntouchedAfterTheMove() throws IOException {
        Files.createDirectories(dir.resolve("coarse"));
        Files.write(dir.resolve("coarse/360000.png"), new byte[] {1, 2, 3});

        DatasetComplete.write(dir, complete("1000:1"));

        assertThat(dir.resolve("complete.json.tmp")).doesNotExist();
        assertThat(Files.readAllBytes(dir.resolve("coarse/360000.png"))).containsExactly(1, 2, 3);
    }

    @Test
    void everyFrameFileAndDirectoryIsFlushedFirstThenTheTemporaryFileThenTheDirectoryAfterTheMove()
        throws IOException {
        Files.createDirectories(dir.resolve("coarse"));
        Files.createDirectories(dir.resolve("dense"));
        Files.write(dir.resolve("coarse/360000.png"), new byte[] {1});
        Files.write(dir.resolve("dense/540000.jpg"), new byte[] {2});
        List<String> flushed = new ArrayList<>();
        List<String> whenFlushed = new ArrayList<>();
        DatasetComplete.Flusher recorder = path -> {
            flushed.add(dir.relativize(path).toString());
            whenFlushed.add((Files.exists(dir.resolve("complete.json.tmp")) ? "tmp " : "")
                + (Files.exists(dir.resolve("complete.json")) ? "final" : ""));
        };

        DatasetComplete.write(dir, complete("1000:1"), recorder, MOVE);

        assertThat(flushed.subList(0, 5)).containsExactlyInAnyOrder("", "coarse", "coarse/360000.png", "dense",
            "dense/540000.jpg");
        assertThat(flushed.subList(5, 7)).containsExactly("complete.json.tmp", "");
        assertThat(flushed).hasSize(7);
        // The frames are flushed before the temporary file exists; the temporary file is flushed before it is moved
        // over the final name; the directory is flushed after the move.
        assertThat(whenFlushed.subList(0, 5)).containsOnly("");
        assertThat(whenFlushed.get(5)).isEqualTo("tmp ");
        assertThat(whenFlushed.get(6)).isEqualTo("final");
    }

    @Test
    void aFlushThatFailsStopsTheWriteBeforeThereIsAFinalFile() throws IOException {
        DatasetComplete.Flusher failing = path -> {
            throw new IOException("disk error");
        };

        assertThatThrownBy(() -> DatasetComplete.write(dir, complete("1000:1"), failing, MOVE))
            .isInstanceOf(IOException.class).hasMessage("disk error");

        assertThat(dir.resolve("complete.json")).doesNotExist();
        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }

    @Test
    void aMoverThatRefusesLeavesNoTemporaryFileAndNoFinalFile() throws IOException {
        assertThatThrownBy(() -> DatasetComplete.write(dir, complete("1000:1"), (from, to) -> {
            assertThat(from).exists();
            throw new IllegalStateException("refused");
        })).isInstanceOf(IllegalStateException.class).hasMessage("refused");

        assertThat(dir.resolve("complete.json.tmp")).doesNotExist();
        assertThat(dir.resolve("complete.json")).doesNotExist();
    }

    @Test
    void aTemporaryFileThatCannotBeDeletedDoesNotHideTheMoversException() {
        IllegalStateException refused = new IllegalStateException("refused");

        assertThatThrownBy(() -> DatasetComplete.write(dir, complete("1000:1"), (from, to) -> {
            Files.delete(from);
            Files.createDirectories(from.resolve("inside"));
            throw refused;
        })).isSameAs(refused).satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
            .allSatisfy(suppressed -> assertThat(suppressed).isInstanceOf(IOException.class)));
    }

    @Test
    void theFingerprintIsReadOnlyFromAFileOfTheCurrentFormat() throws IOException {
        assertThat(DatasetComplete.fingerprint(dir)).isEmpty();
        writeRaw("{\"format\": 1, \"fingerprint\": \"9:9\"}");
        assertThat(DatasetComplete.fingerprint(dir)).contains("9:9");
        writeRaw("{\"format\": 0, \"fingerprint\": \"9:9\"}");
        assertThat(DatasetComplete.fingerprint(dir)).isEmpty();
        writeRaw("not json");
        assertThat(DatasetComplete.fingerprint(dir)).isEmpty();
    }

    @Test
    void anExistingFileIsReplacedInOneStep() throws IOException {
        DatasetComplete.write(dir, complete("1000:1"));

        DatasetComplete.write(dir, complete("1000:2"));

        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
        assertThat(DatasetComplete.isCurrent(dir, "1000:2")).isTrue();
    }

    @Test
    void theJsonHasExactlyTheDocumentedFieldsAndNoTitleOrPath() throws IOException {
        DatasetComplete.write(dir, complete("1000:1"));

        JsonNode node = MAPPER.readTree(Files.readAllBytes(dir.resolve("complete.json")));
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("format", "mediaFileId", "fingerprint",
            "durationMs", "targetStartMs", "window", "frame", "activeArea", "activeAreaOffsets", "coarse", "dense",
            "ffmpegVersion", "javaVersion", "gitCommit", "settings", "writtenAt");
        assertThat(node.path("settings").propertyNames()).containsExactlyInAnyOrder("filter", "framePixelFormat",
            "areaPixelFormat", "outputWidth", "greyWidth", "colourSize", "jpegQuality", "coarseStepMs", "denseFps",
            "denseLeadMs", "denseLengthMs", "niceness");
    }

    @Test
    void theSettingsComeFromTheCodeThatMakesTheFrames() {
        DatasetComplete.Settings settings = DatasetComplete.Settings.current(19);

        assertThat(settings.filter()).isEqualTo(CreditsFrameReader.FILTER)
            .isEqualTo("scale='trunc(iw*sar/2)*2':ih,setsar=1,scale=480:-2");
        assertThat(settings.framePixelFormat()).isEqualTo("rgb24");
        assertThat(settings.areaPixelFormat()).isEqualTo("gray");
        assertThat(settings.outputWidth()).isEqualTo(480);
        assertThat(settings.greyWidth()).isEqualTo(240);
        assertThat(settings.colourSize()).isEqualTo(224);
        assertThat(settings.jpegQuality()).isEqualTo(0.95f);
        assertThat(settings.coarseStepMs()).isEqualTo(5000);
        assertThat(settings.denseFps()).isEqualTo(2);
        assertThat(settings.denseLeadMs()).isEqualTo(30_000);
        assertThat(settings.denseLengthMs()).isEqualTo(60_000);
        assertThat(settings.niceness()).isEqualTo(19);
        assertThat(DatasetComplete.Settings.current(5).niceness()).isEqualTo(5);
    }

    @Test
    void aDirectoryWithNoFileIsNotComplete() {
        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }

    @Test
    void aFileThatDoesNotParseIsNotComplete() throws IOException {
        writeRaw("{\"format\": 1, \"fingerprint\": \"1000:1\"");

        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }

    @Test
    void anEmptyFileIsNotComplete() throws IOException {
        Files.write(dir.resolve("complete.json"), "".getBytes(StandardCharsets.UTF_8));

        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }

    @Test
    void anOldFormatIsNotComplete() throws IOException {
        writeRaw("{\"format\": 0, \"fingerprint\": \"1000:1\"}");

        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }

    @Test
    void aFileWithNoFormatOrNoFingerprintIsNotComplete() throws IOException {
        writeRaw("{\"fingerprint\": \"1000:1\"}");
        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();

        writeRaw("{\"format\": 1}");
        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }

    @Test
    void aFileWhoseContentIsNotAnObjectIsNotComplete() throws IOException {
        writeRaw("[1, 2, 3]");

        assertThat(DatasetComplete.isCurrent(dir, "1000:1")).isFalse();
    }
}
