package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntFunction;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic embeddings runs for model B's training tool, written with {@link EmbeddingFile}. */
final class LrFixtures {

    static final String ENCODER_SHA = "e".repeat(64);
    static final String DEV_SHA = "d".repeat(64);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private LrFixtures() {
    }

    /** Changes the key of one record; the identity writes the records of the features file as they are. */
    @FunctionalInterface
    interface KeyEdit {
        long[] apply(int index, long[] key);
    }

    /** Each data row's {@code {mediaFileId, kind, offsetMs}}, in file order, blank lines skipped. */
    static List<long[]> keys(Path features) throws IOException {
        List<long[]> keys = new ArrayList<>();
        List<String> lines = Files.readAllLines(features);
        for (String line : lines.subList(1, lines.size())) {
            List<String> row = TrainRows.fields(line);
            if (!row.isEmpty()) {
                keys.add(new long[] {Long.parseLong(row.get(0)), row.get(3).equals("coarse") ? 0 : 1,
                    Long.parseLong(row.get(4))});
            }
        }
        return keys;
    }

    /** An embeddings run for {@code features}: one record per data row, {@code vector(i)} for row {@code i}. */
    static Path embeddingsRun(Path dir, Path features, int dimension, IntFunction<float[]> vector)
            throws IOException {
        return embeddingsRun(dir, features, dimension, vector, (index, key) -> key, 0);
    }

    /**
     * An embeddings run whose keys are edited and whose record count is the row count plus {@code extra}: a negative
     * number leaves the last records out and a positive one adds records after the last.
     */
    static Path embeddingsRun(Path dir, Path features, int dimension, IntFunction<float[]> vector, KeyEdit edit,
                              int extra) throws IOException {
        Files.createDirectories(dir);
        List<long[]> keys = keys(features);
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(dir.resolve("embeddings.bin"), dimension)) {
            int records = keys.size() + extra;
            for (int i = 0; i < records; i++) {
                long[] key = i < keys.size() ? keys.get(i) : new long[] {Long.MAX_VALUE - i, 0, 0};
                long[] written = edit.apply(i, key);
                writer.append(written[0], (int) written[1], written[2], vector.apply(Math.min(i, keys.size() - 1)));
            }
        }
        ObjectNode json = MAPPER.createObjectNode();
        json.put("toolGitCommit", "embedsha");
        json.put("generatedAt", "2024-01-01T00:00:00Z");
        json.put("featuresRun", features.getParent().getFileName().toString());
        json.put("featuresCsvSha256", sha256(features));
        json.put("featureVersion", CreditsFeatures.FEATURE_VERSION);
        json.put("datasetSha256", "ds-hash");
        json.put("labelsSha256", "labels-hash");
        ObjectNode encoder = json.putObject("encoder");
        encoder.put("name", "stub-encoder");
        encoder.put("sha256", ENCODER_SHA);
        encoder.put("dimension", dimension);
        json.put("devSha256", DEV_SHA);
        Files.write(dir.resolve("embeddings.json"), MAPPER.writeValueAsBytes(json));
        return dir;
    }

    /** The SHA-256 of a file, in hex. */
    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
