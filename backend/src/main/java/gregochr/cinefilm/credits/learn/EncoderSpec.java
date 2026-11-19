package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An image encoder as {@code encoder.json} describes it, beside its ONNX file.
 *
 * @param name the encoder's name
 * @param file the ONNX file's name, in the same directory: no path separator
 * @param sha256 the file's SHA-256, in hex
 * @param input the input tensor's name
 * @param output the output tensor's name
 * @param size the square side of the input, at least 1 (a file read by {@link #read} must say 224)
 * @param mean the per-channel mean, red, green, blue: three finite values
 * @param std the per-channel standard deviation, red, green, blue: three finite values above 0
 * @param dimension the length of an embedding, at least 1
 */
record EncoderSpec(String name, String file, String sha256, String input, String output, int size,
                   List<Double> mean, List<Double> std, int dimension) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> FIELDS =
        Set.of("name", "file", "sha256", "input", "output", "size", "mean", "std", "dimension");

    /**
     * Checks each field.
     *
     * @throws IllegalArgumentException when a field is out of its range
     */
    EncoderSpec {
        if (name == null || name.isBlank() || sha256 == null || input == null || input.isBlank()
            || output == null || output.isBlank()) {
            throw new IllegalArgumentException("name, sha256, input and output must be given");
        }
        if (file == null || file.isBlank() || file.contains("/") || file.contains("\\")) {
            throw new IllegalArgumentException("file must be a plain file name: " + file);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1: " + size);
        }
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be at least 1: " + dimension);
        }
        mean = checked("mean", mean, false);
        std = checked("std", std, true);
    }

    private static List<Double> checked(String what, List<Double> values, boolean positive) {
        if (values == null || values.size() != 3) {
            throw new IllegalArgumentException(what + " must have three values");
        }
        for (Double value : values) {
            if (value == null || !Double.isFinite(value) || positive && value <= 0) {
                throw new IllegalArgumentException(
                    what + " must be three finite values" + (positive ? " above 0" : "") + ": " + values);
            }
        }
        return List.copyOf(values);
    }

    /**
     * Reads {@code dir/encoder.json} and checks the ONNX file beside it.
     *
     * @param dir the encoder's directory
     * @return the spec
     * @throws IOException when a file cannot be read
     * @throws IllegalArgumentException when a field is missing, unknown or of the wrong type, the SHA-256 differs
     *     from the file's, or {@code size} is not {@link FramePrep#COLOUR_SIZE}
     */
    static EncoderSpec read(Path dir) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(dir.resolve("encoder.json")));
        if (!root.isObject()) {
            throw new IllegalArgumentException("encoder.json is not a JSON object");
        }
        for (String field : FIELDS) {
            if (!root.has(field)) {
                throw new IllegalArgumentException("encoder.json is missing " + field);
            }
        }
        for (String field : root.propertyNames()) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("encoder.json has an unknown field " + field);
            }
        }
        EncoderSpec spec = new EncoderSpec(text(root, "name"), text(root, "file"), text(root, "sha256"),
            text(root, "input"), text(root, "output"), whole(root, "size"), numbers(root, "mean"),
            numbers(root, "std"), whole(root, "dimension"));
        if (spec.size() != FramePrep.COLOUR_SIZE) {
            throw new IllegalArgumentException(
                "size must be " + FramePrep.COLOUR_SIZE + " to match the stored frames: " + spec.size());
        }
        String actual = hash(dir.resolve(spec.file()));
        if (!actual.equalsIgnoreCase(spec.sha256())) {
            throw new IllegalArgumentException(
                spec.file() + " has SHA-256 " + actual + ", encoder.json says " + spec.sha256());
        }
        return spec;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (!node.isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return node.asString();
    }

    private static int whole(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (!node.isInt()) {
            throw new IllegalArgumentException(field + " must be a whole number");
        }
        return node.intValue();
    }

    private static List<Double> numbers(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of numbers");
        }
        List<Double> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isNumber()) {
                throw new IllegalArgumentException(field + " must be an array of numbers");
            }
            values.add(element.doubleValue());
        }
        return values;
    }

    private static String hash(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }
}
