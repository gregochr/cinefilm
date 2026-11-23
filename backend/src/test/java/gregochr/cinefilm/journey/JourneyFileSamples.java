package gregochr.cinefilm.journey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contract's worked example (a made-up request) as an editable JSON tree, for the journey import tests. It is
 * read from the resource {@code journey/import-request-example.json}, never copied into Java: a later test of the
 * Admin page's reader holds the same file.
 */
final class JourneyFileSamples {

    /** A plain mapper: strictness must come from {@link JourneyFileReader}, not from this mapper's settings. */
    static final JsonMapper JSON = JsonMapper.builder().build();

    private JourneyFileSamples() {
    }

    /** A fresh copy of the example request. */
    static ObjectNode example() {
        try (InputStream in = JourneyFileSamples.class.getResourceAsStream("/journey/import-request-example.json")) {
            return (ObjectNode) JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example's first round: every field of a sample is filled. */
    static ObjectNode firstSample(ObjectNode root) {
        return (ObjectNode) root.get("samples").get(0);
    }

    /** The first modem of the example's first round: every field of a modem is filled. */
    static ObjectNode firstModem(ObjectNode root) {
        return (ObjectNode) firstSample(root).get("modems").get(0);
    }

    /** The samples of a request tree, to edit. */
    static ArrayNode samples(ObjectNode root) {
        return (ArrayNode) root.get("samples");
    }

    /** The tree as the text a client would send. */
    static String text(JsonNode tree) {
        return JSON.writeValueAsString(tree);
    }

    /** The tree read with the strict reader and a plain mapper. */
    static JourneyFile read(JsonNode tree) {
        return new JourneyFileReader(JSON).read(text(tree));
    }
}
