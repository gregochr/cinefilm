package gregochr.cinefilm.journey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contract's worked example for a train's file (a made-up request) as an editable JSON tree. It is read from the
 * resource {@code journey/train-file-example.json}, never copied into Java: a later test of the Admin page holds the
 * same file.
 */
final class TrainFileSamples {

    /** A plain mapper: strictness must come from {@link TrainFileReader}, not from this mapper's settings. */
    static final JsonMapper JSON = JsonMapper.builder().build();

    private TrainFileSamples() {
    }

    /** A fresh copy of the example request. */
    static ObjectNode example() {
        try (InputStream in = TrainFileSamples.class.getResourceAsStream("/journey/train-file-example.json")) {
            return (ObjectNode) JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example's calls, to edit. */
    static ArrayNode calls(ObjectNode root) {
        return (ArrayNode) root.get("calls");
    }

    /** The example's first call. */
    static ObjectNode firstCall(ObjectNode root) {
        return (ObjectNode) calls(root).get(0);
    }

    /** The tree as the text a client would send. */
    static String text(JsonNode tree) {
        return JSON.writeValueAsString(tree);
    }

    /** The tree read with the strict reader and a plain mapper. */
    static TrainFile read(JsonNode tree) {
        return new TrainFileReader(JSON).read(text(tree));
    }
}
