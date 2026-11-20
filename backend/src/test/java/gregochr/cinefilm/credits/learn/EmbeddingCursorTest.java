package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.learn.EmbeddingCursor.FilmEmbeddings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EmbeddingCursorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final CreditsTrainJob.Hashes HASHES = new CreditsTrainJob.Hashes("ds-hash", "labels-hash");

    @TempDir
    Path temp;

    private Path features;

    /** Film 1 has two coarse rows and one dense row, film 2 one coarse row: row {@code i} is embedded as {i, 10+i}. */
    @BeforeEach
    void setUp() throws IOException {
        features = Files.createDirectories(temp.resolve("features"));
        Files.writeString(features.resolve("features.csv"), TrainFixtures.csv(
            TrainFixtures.row(1, 0, "coarse", 100_000, "film", TrainFixtures.lit(0, 0)),
            TrainFixtures.row(1, 0, "coarse", 105_000, "credits", TrainFixtures.lit(1, 0)),
            TrainFixtures.row(1, 0, "dense", 90_000, "film", TrainFixtures.lit(2, 0)),
            TrainFixtures.row(2, 1, "coarse", 100_000, "film", TrainFixtures.lit(3, 0))));
    }

    private Path embeddings(LrFixtures.KeyEdit edit, int extra) throws IOException {
        return LrFixtures.embeddingsRun(temp.resolve("embed"), features.resolve("features.csv"), 2,
            i -> new float[] {i, 10 + i}, edit, extra);
    }

    private EmbeddingCursor open(Path embeddings) throws IOException {
        return EmbeddingCursor.open(embeddings, features, HASHES, false, List.of());
    }

    @Test
    void eachFilmGetsItsOwnEmbeddingsAndOnlyTheLastFilmIsHeld() throws IOException {
        List<FilmEmbeddings> films = new ArrayList<>();
        List<Integer> held = new ArrayList<>();

        try (EmbeddingCursor cursor = open(embeddings((i, key) -> key, 0))) {
            assertThat(cursor.heldEmbeddings()).isZero();
            FeatureRunReader.forEachFilm(features.resolve("features.csv"), false, rows -> {
                films.add(cursor.film(rows));
                held.add(cursor.heldEmbeddings());
            });
            cursor.finish();
        }

        assertThat(held).containsExactly(3, 1);
        assertThat(films.get(0).coarse()).isDeepEqualTo(new float[][] {{0, 10}, {1, 11}});
        assertThat(films.get(0).dense()).isDeepEqualTo(new float[][] {{2, 12}});
        assertThat(films.get(1).coarse()).isDeepEqualTo(new float[][] {{3, 13}});
        assertThat(films.get(1).dense()).isEmpty();
    }

    @Test
    void aRecordWhoseKeyIsNotTheRowsIsRefusedWithBothKeys() throws IOException {
        Path embeddings = embeddings((i, key) -> i == 3 ? new long[] {3, key[1], key[2]} : key, 0);

        try (EmbeddingCursor cursor = open(embeddings)) {
            assertThatThrownBy(() -> FeatureRunReader.forEachFilm(features.resolve("features.csv"), false,
                cursor::film)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embeddings.bin record 3 is (mediaFileId 3, kind 0, offsetMs 100000), features.csv "
                    + "data row 3 is (mediaFileId 2, kind 0, offsetMs 100000)");
        }
    }

    @Test
    void recordsThatRunOutBeforeTheRowsAreRefused() throws IOException {
        Path embeddings = embeddings((i, key) -> key, -1);

        try (EmbeddingCursor cursor = open(embeddings)) {
            assertThatThrownBy(() -> FeatureRunReader.forEachFilm(features.resolve("features.csv"), false,
                cursor::film)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embeddings.bin has 3 records, fewer than features.csv's data rows");
        }
    }

    @Test
    void recordsLeftAfterTheLastFilmAreRefusedByFinish() throws IOException {
        Path embeddings = embeddings((i, key) -> key, 2);

        try (EmbeddingCursor cursor = open(embeddings)) {
            FeatureRunReader.forEachFilm(features.resolve("features.csv"), false, cursor::film);
            assertThatThrownBy(cursor::finish).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embeddings.bin has 6 records, features.csv has 4 data rows");
        }
    }

    @Test
    void anEmbeddingsJsonThatLacksAFieldIsRefusedByName() throws IOException {
        Path embeddings = embeddings((i, key) -> key, 0);
        Path json = embeddings.resolve("embeddings.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readAllBytes(json));

        root.remove("devSha256");
        Files.write(json, MAPPER.writeValueAsBytes(root));
        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.json has no text field devSha256");

        root.put("devSha256", "d").put("featureVersion", "one");
        Files.write(json, MAPPER.writeValueAsBytes(root));
        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.json has no whole-number field featureVersion");

        root.remove("featureVersion");
        Files.write(json, MAPPER.writeValueAsBytes(root));
        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.json has no whole-number field featureVersion");

        root.put("featureVersion", 1).put("devSha256", 7);
        Files.write(json, MAPPER.writeValueAsBytes(root));
        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.json has no text field devSha256");

        root.put("devSha256", "d").put("encoder", "stub");
        Files.write(json, MAPPER.writeValueAsBytes(root));
        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.json has no object field encoder");

        root.remove("encoder");
        Files.write(json, MAPPER.writeValueAsBytes(root));
        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.json has no object field encoder");
    }

    @Test
    void aBinWhoseDimensionIsNotTheJsonsIsRefused() throws IOException {
        Path embeddings = embeddings((i, key) -> key, 0);
        Path json = embeddings.resolve("embeddings.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readAllBytes(json));
        ((ObjectNode) root.get("encoder")).put("dimension", 5);
        Files.write(json, MAPPER.writeValueAsBytes(root));

        assertThatThrownBy(() -> open(embeddings)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddings.bin has dimension 2, embeddings.json's encoder says 5");
    }

    @Test
    void theLookNeedsAnEmbeddingsRunWithALookJson() throws IOException {
        Path embeddings = embeddings((i, key) -> key, 0);

        assertThatThrownBy(() -> EmbeddingCursor.open(embeddings, features, HASHES, true, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--look=L5 needs an embeddings run made with --look=L5: no look.json in " + embeddings);
        Files.writeString(embeddings.resolve("look.json"), "{}");
        try (EmbeddingCursor cursor = EmbeddingCursor.open(embeddings, features, HASHES, true, List.of())) {
            assertThat(cursor.heldEmbeddings()).isZero();
        }
    }

    @Test
    void closingTheCursorClosesTheFile() throws IOException {
        Path embeddings = embeddings((i, key) -> key, 0);
        List<EmbeddingFile.Reader> captured = new ArrayList<>();

        EmbeddingCursor cursor = EmbeddingCursor.open(embeddings, features, HASHES, false, List.of(), file -> {
            captured.add(EmbeddingFile.open(file));
            return captured.getFirst();
        });
        cursor.close();

        assertThat(captured).hasSize(1);
        assertThatThrownBy(captured.getFirst()::next).isInstanceOf(IOException.class);
    }

    @Test
    void aRefusedDimensionClosesTheFile() throws IOException {
        Path embeddings = embeddings((i, key) -> key, 0);
        Path json = embeddings.resolve("embeddings.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readAllBytes(json));
        ((ObjectNode) root.get("encoder")).put("dimension", 5);
        Files.write(json, MAPPER.writeValueAsBytes(root));
        List<EmbeddingFile.Reader> captured = new ArrayList<>();

        assertThatThrownBy(() -> EmbeddingCursor.open(embeddings, features, HASHES, false, List.of(), file -> {
            captured.add(EmbeddingFile.open(file));
            return captured.getFirst();
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(captured).hasSize(1);
        assertThatThrownBy(captured.getFirst()::next).isInstanceOf(IOException.class);
    }
}
