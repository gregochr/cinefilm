package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the tiny encoder fixture is, and how to copy it with a different {@code encoder.json}. */
final class TinyFixture {

    /** The committed {@code encoder.json}'s SHA-256 field: the hash of the committed {@code tiny.onnx}. */
    static final String SHA256 = "92b1c24252cb0492554d2ff99742f5b7c218cc1a64bfec136a6810732d7687a6";

    private TinyFixture() {
    }

    /**
     * The committed fixture directory.
     *
     * @return {@code credits/learn/encoder-tiny} on the test classpath
     */
    static Path dir() {
        try {
            return Path.of(TinyFixture.class.getResource("/credits/learn/encoder-tiny/tiny.onnx").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A copy of the fixture with another {@code encoder.json}.
     *
     * @param target an empty directory
     * @param json the new {@code encoder.json}
     * @return the directory
     * @throws IOException when a file cannot be written
     */
    static Path withJson(Path target, String json) throws IOException {
        Files.copy(dir().resolve("tiny.onnx"), target.resolve("tiny.onnx"));
        Files.writeString(target.resolve("encoder.json"), json);
        return target;
    }

    /**
     * The committed {@code encoder.json} with one field's text replaced.
     *
     * @param field the field, such as {@code "size": 224}
     * @param replacement what to put instead
     * @return the changed JSON
     * @throws IOException when the file cannot be read
     */
    static String json(String field, String replacement) throws IOException {
        String json = Files.readString(dir().resolve("encoder.json"));
        if (!json.contains(field)) {
            throw new IllegalArgumentException(field + " is not in the fixture's encoder.json");
        }
        return json.replace(field, replacement);
    }
}
