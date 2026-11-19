package gregochr.cinefilm.credits.learn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the tiny ONNX fixture, {@code encoder-tiny/tiny.onnx}, in the protobuf wire format directly (no protobuf
 * dependency): one {@code ReduceMean} node (axes 2 and 3, keepdims 0) from {@code pixel_values}
 * {@code [N, 3, 224, 224]} to {@code image_embeds} {@code [N, 3]}, the mean of each channel. ir_version 7, one
 * opset import (default domain, version 13). {@code TinyOnnxTest} proves the committed file equals {@link #bytes()};
 * to rewrite it, run {@code main} with the file's path.
 */
final class TinyOnnx {

    private static final int VARINT = 0;
    private static final int LEN = 2;

    private TinyOnnx() {
    }

    /**
     * The model's bytes.
     *
     * @return a serialised ONNX ModelProto
     */
    static byte[] bytes() {
        return model(false);
    }

    /**
     * The same model with a {@code Cast} node (to DOUBLE, ONNX type 11) after the mean, so the output is a double
     * tensor. For the test that an encoder refuses a non-float output; written to a temporary file, never committed.
     *
     * @return a serialised ONNX ModelProto
     */
    static byte[] doubleOutputBytes() {
        return model(true);
    }

    private static byte[] model(boolean cast) {
        byte[] axes = concat(string(1, "axes"), varint(20, 7), varint(8, 2), varint(8, 3));
        byte[] keepdims = concat(string(1, "keepdims"), varint(20, 2), varint(3, 0));
        byte[] mean = concat(string(1, "pixel_values"), string(2, cast ? "mean_float" : "image_embeds"),
            string(3, "mean"), string(4, "ReduceMean"), message(5, axes), message(5, keepdims));
        byte[] nodes = message(1, mean);
        if (cast) {
            byte[] to = concat(string(1, "to"), varint(20, 2), varint(3, 11));
            nodes = concat(nodes, message(1, concat(string(1, "mean_float"), string(2, "image_embeds"),
                string(3, "cast"), string(4, "Cast"), message(5, to))));
        }
        byte[] input = valueInfo("pixel_values", 1, dimParam("N"), dimValue(3), dimValue(224), dimValue(224));
        byte[] output = valueInfo("image_embeds", cast ? 11 : 1, dimParam("N"), dimValue(3));
        byte[] graph = concat(nodes, string(2, "tiny-encoder"), message(11, input), message(12, output));
        byte[] opset = varint(2, 13);
        return concat(varint(1, 7), string(2, "cinefilm-test"), message(7, graph), message(8, opset));
    }

    /**
     * Writes the model.
     *
     * @param args the path to write
     * @throws IOException when the file cannot be written
     */
    public static void main(String[] args) throws IOException {
        Files.write(Path.of(args[0]), bytes());
    }

    private static byte[] valueInfo(String name, int elemType, byte[]... dims) {
        byte[] shape = new byte[0];
        for (byte[] dim : dims) {
            shape = concat(shape, message(1, dim));
        }
        byte[] tensorType = concat(varint(1, elemType), message(2, shape));
        return concat(string(1, name), message(2, message(1, tensorType)));
    }

    private static byte[] dimValue(long value) {
        return varint(1, value);
    }

    private static byte[] dimParam(String name) {
        return string(2, name);
    }

    private static byte[] varint(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rawVarint((long) field << 3 | VARINT));
        out.writeBytes(rawVarint(value));
        return out.toByteArray();
    }

    private static byte[] string(int field, String text) {
        return message(field, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] message(int field, byte[] body) {
        return concat(rawVarint((long) field << 3 | LEN), rawVarint(body.length), body);
    }

    private static byte[] rawVarint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long rest = value;
        while (rest >= 0x80) {
            out.write((int) (rest & 0x7F | 0x80));
            rest >>>= 7;
        }
        out.write((int) rest);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
