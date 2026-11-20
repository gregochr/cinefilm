package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fixtures for evaluating model B: an embeddings run for {@link EvaluateFixtures}' features run, and hand-made
 * {@code "logreg"} runs trained on its embeddings. An embedding is {@code {1 if the frame is at or after Plex's target
 * else 0, (offset / 500) mod 2}}, so a model that reads its first value separates film from credits.
 */
final class EmbeddedEvaluateFixtures {

    static final String EMBEDDINGS_RUN = "embed-20240101T000000Z";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EmbeddedEvaluateFixtures() {
    }

    /** The embedding of the frame with this key. */
    static float[] vector(long[] key) {
        return new float[] {key[2] >= EvaluateFixtures.TARGET ? 1f : 0f, (key[2] / 500) % 2};
    }

    /** An embeddings run of the features run's {@code features.csv}, written into {@code parent}. */
    static Path embeddingsRun(Path parent, Path featuresRun) throws IOException {
        return embeddingsRun(parent, featuresRun, (index, key) -> key, 0);
    }

    /** As {@link #embeddingsRun(Path, Path)}, with the keys edited and the record count changed by {@code extra}. */
    static Path embeddingsRun(Path parent, Path featuresRun, LrFixtures.KeyEdit edit, int extra) throws IOException {
        Path csv = featuresRun.resolve("features.csv");
        List<long[]> keys = LrFixtures.keys(csv);
        return LrFixtures.embeddingsRun(parent.resolve(EMBEDDINGS_RUN), csv, 2, i -> vector(keys.get(i)), edit,
            extra);
    }

    /** Changes a field of an embeddings run's {@code embeddings.json}. */
    static void edit(Path embeddings, java.util.function.Consumer<ObjectNode> change) throws IOException {
        Path json = embeddings.resolve("embeddings.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readAllBytes(json));
        change.accept(root);
        Files.write(json, MAPPER.writeValueAsBytes(root));
    }

    /** A model B run that agrees with the embeddings: credits are high in the first value. */
    static Path logregRun(Path parent, String name) throws IOException {
        return logregRun(parent, name, false);
    }

    /**
     * A hand-made model B run: five shipping models, ten pair models and ten triple models, all one fit on four rows
     * of two values, whose labels are the first value, or its opposite when {@code inverted}. It names the encoder and
     * the development hash of {@link #embeddingsRun}.
     */
    static Path logregRun(Path parent, String name, boolean inverted) throws IOException {
        Path dir = Files.createDirectories(parent.resolve(name));
        float[][] rows = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};
        int[] labels = inverted ? new int[] {1, 1, 0, 0} : new int[] {0, 0, 1, 1};
        JsonNode lr = MAPPER.readTree(LogisticRegression.fit(rows, labels, new LrParams(1.0, 300, 1e-6)).toJson());
        ObjectNode model = MAPPER.createObjectNode();
        model.put("type", "logreg");
        model.put("featureVersion", CreditsFeatures.FEATURE_VERSION);
        ObjectNode encoder = model.putObject("encoder");
        encoder.put("name", "stub-encoder");
        encoder.put("sha256", LrFixtures.ENCODER_SHA);
        encoder.put("dimension", 2);
        for (int i = 0; i < 5; i++) {
            model.withArray("models").add(lr);
        }
        model.put("datasetSha256", "ds-hash");
        model.put("labelsSha256", "labels-hash");
        model.put("manifestSha256", TrainFixtures.manifestSha());
        model.put("embeddingsRun", EMBEDDINGS_RUN);
        model.put("embeddingsDevSha256", LrFixtures.DEV_SHA);
        Files.write(dir.resolve("model.json"), MAPPER.writeValueAsBytes(model));
        ObjectNode pairs = MAPPER.createObjectNode();
        ObjectNode triples = MAPPER.createObjectNode();
        for (int a = 0; a < 5; a++) {
            for (int b = a + 1; b < 5; b++) {
                pairs.set(a + "," + b, lr);
                for (int c = b + 1; c < 5; c++) {
                    triples.set(a + "," + b + "," + c, lr);
                }
            }
        }
        Files.write(dir.resolve("oof-models.json"), MAPPER.writeValueAsBytes(pairs));
        Files.write(dir.resolve("nested-models.json"), MAPPER.writeValueAsBytes(triples));
        return dir;
    }

    /** Changes a field of a run's {@code model.json}. */
    static void editModel(Path run, java.util.function.Consumer<ObjectNode> change) throws IOException {
        Path json = run.resolve("model.json");
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readAllBytes(json));
        change.accept(root);
        Files.write(json, MAPPER.writeValueAsBytes(root));
    }
}
