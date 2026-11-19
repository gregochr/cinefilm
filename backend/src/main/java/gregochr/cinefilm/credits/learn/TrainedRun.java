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
 * {@code "k,j,m"}), all as {@link CreditsTrainJob} wrote them.
 */
record TrainedRun(String name, String manifestSha256, List<GradientBoostedTrees> shipping,
                  Map<String, GradientBoostedTrees> pairs, Map<String, GradientBoostedTrees> triples) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Reads a run. */
    static TrainedRun load(Path dir, Hashes expected) throws IOException {
        String name = dir.getFileName().toString();
        JsonNode model = MAPPER.readTree(Files.readAllBytes(dir.resolve("model.json")));
        same(name, "datasetSha256", text(name, model, "datasetSha256"), expected.datasetSha256());
        same(name, "labelsSha256", text(name, model, "labelsSha256"), expected.labelsSha256());
        JsonNode shippingNode = model.get("models");
        if (shippingNode == null || !shippingNode.isArray() || shippingNode.size() != CreditsTrainJob.FOLDS) {
            throw new IllegalArgumentException(name + ": model.json needs " + CreditsTrainJob.FOLDS + " models");
        }
        List<GradientBoostedTrees> shipping = new ArrayList<>();
        for (JsonNode node : shippingNode) {
            shipping.add(parse(name, node));
        }
        return new TrainedRun(name, text(name, model, "manifestSha256"), List.copyOf(shipping),
            keyed(name, dir.resolve("oof-models.json"), 2), keyed(name, dir.resolve("nested-models.json"), 3));
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

    /** The models of a file, one for every key of {@code size} ascending folds. */
    private static Map<String, GradientBoostedTrees> keyed(String name, Path file, int size) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
        Map<String, GradientBoostedTrees> models = new LinkedHashMap<>();
        for (int a = 0; a < CreditsTrainJob.FOLDS; a++) {
            for (int b = a + 1; b < CreditsTrainJob.FOLDS; b++) {
                if (size == 2) {
                    put(name, file, root, models, a + "," + b);
                }
                for (int c = b + 1; c < CreditsTrainJob.FOLDS && size == 3; c++) {
                    put(name, file, root, models, a + "," + b + "," + c);
                }
            }
        }
        return models;
    }

    private static void put(String name, Path file, JsonNode root, Map<String, GradientBoostedTrees> models,
                            String key) {
        JsonNode node = root.get(key);
        if (node == null) {
            throw new IllegalArgumentException(name + ": " + file.getFileName() + " has no model " + key);
        }
        models.put(key, parse(name, node));
    }

    private static GradientBoostedTrees parse(String name, JsonNode node) {
        try {
            return GradientBoostedTrees.fromJson(MAPPER.writeValueAsString(node));
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        }
    }
}
