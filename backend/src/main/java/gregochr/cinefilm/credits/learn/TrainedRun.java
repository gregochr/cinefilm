package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.CreditsTrainJob.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One training run read for the evaluation tool: the five shipping models of {@code model.json}, the ten pair models
 * of {@code oof-models.json} (keyed {@code "k,j"}) and the ten triple models of {@code nested-models.json} (keyed
 * {@code "k,j,m"}), all as {@link CreditsTrainJob} (model A, type {@code "gbt"}) or the model B trainer (type
 * {@code "logreg"}) wrote them.
 *
 * <p>{@code encoder} and {@code embeddingsDevSha256} are set for a {@code "logreg"} run and null for a {@code "gbt"}
 * one.
 */
record TrainedRun(String name, String manifestSha256, String type, List<FrameModel> shipping,
                  Map<String, FrameModel> pairs, Map<String, FrameModel> triples, Encoder encoder,
                  String embeddingsDevSha256) {

    /** The {@code type} of a model A run. */
    static final String GBT = "gbt";
    /** The {@code type} of a model B run. */
    static final String LOGREG = "logreg";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The encoder a model B run was trained on: the SHA-256 of its model file and the length of its embeddings. */
    record Encoder(String sha256, int dimension) {
    }

    /** Reads a run. */
    static TrainedRun load(Path dir, Hashes expected) throws IOException {
        String name = dir.getFileName().toString();
        JsonNode model = MAPPER.readTree(Files.readAllBytes(dir.resolve("model.json")));
        same(name, "datasetSha256", text(name, model, "datasetSha256"), expected.datasetSha256());
        same(name, "labelsSha256", text(name, model, "labelsSha256"), expected.labelsSha256());
        String type = text(name, model, "type");
        if (!type.equals(GBT) && !type.equals(LOGREG)) {
            throw new IllegalArgumentException(name + ": model.json type must be " + GBT + " or " + LOGREG + ": "
                + type);
        }
        boolean logreg = type.equals(LOGREG);
        JsonNode shippingNode = model.get("models");
        if (shippingNode == null || !shippingNode.isArray() || shippingNode.size() != CreditsTrainJob.FOLDS) {
            throw new IllegalArgumentException(name + ": model.json needs " + CreditsTrainJob.FOLDS + " models");
        }
        List<FrameModel> shipping = new ArrayList<>();
        for (JsonNode node : shippingNode) {
            shipping.add(parse(name, logreg, node));
        }
        Encoder encoder = logreg ? encoder(name, model) : null;
        String devSha = logreg ? text(name, model, "embeddingsDevSha256") : null;
        return new TrainedRun(name, text(name, model, "manifestSha256"), type, List.copyOf(shipping),
            keyed(name, logreg, dir.resolve("oof-models.json"), 2),
            keyed(name, logreg, dir.resolve("nested-models.json"), 3), encoder, devSha);
    }

    private static void same(String name, String field, String found, String expected) {
        if (!found.equals(expected)) {
            throw new IllegalArgumentException(name + ": " + field + " differs from the features run's");
        }
    }

    private static String text(String name, JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException(name + ": model.json has no text field " + field);
        }
        return value.stringValue();
    }

    private static Encoder encoder(String name, JsonNode model) {
        JsonNode encoder = model.get("encoder");
        if (encoder == null || !encoder.isObject()) {
            throw new IllegalArgumentException(name + ": model.json has no encoder object");
        }
        JsonNode dimension = encoder.get("dimension");
        if (dimension == null || !dimension.isInt() || dimension.intValue() < 1) {
            throw new IllegalArgumentException(name + ": model.json's encoder has no dimension of at least 1");
        }
        return new Encoder(text(name, encoder, "sha256"), dimension.intValue());
    }

    /** The models of a file, one for every key of {@code size} ascending folds. */
    private static Map<String, FrameModel> keyed(String name, boolean logreg, Path file, int size)
        throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
        Map<String, FrameModel> models = new LinkedHashMap<>();
        for (int a = 0; a < CreditsTrainJob.FOLDS; a++) {
            for (int b = a + 1; b < CreditsTrainJob.FOLDS; b++) {
                if (size == 2) {
                    put(name, logreg, file, root, models, a + "," + b);
                }
                for (int c = b + 1; c < CreditsTrainJob.FOLDS && size == 3; c++) {
                    put(name, logreg, file, root, models, a + "," + b + "," + c);
                }
            }
        }
        return models;
    }

    private static void put(String name, boolean logreg, Path file, JsonNode root, Map<String, FrameModel> models,
                            String key) {
        JsonNode node = root.get(key);
        if (node == null) {
            throw new IllegalArgumentException(name + ": " + file.getFileName() + " has no model " + key);
        }
        models.put(key, parse(name, logreg, node));
    }

    private static FrameModel parse(String name, boolean logreg, JsonNode node) {
        String json = MAPPER.writeValueAsString(node);
        if (logreg) {
            try {
                return LogisticRegression.fromJson(json);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
            }
        }
        try {
            return GradientBoostedTrees.fromJson(json);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        }
    }
}
