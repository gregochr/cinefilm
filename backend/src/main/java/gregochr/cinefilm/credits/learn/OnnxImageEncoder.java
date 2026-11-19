package gregochr.cinefilm.credits.learn;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import gregochr.cinefilm.credits.RgbFrame;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * An {@link ImageEncoder} over ONNX Runtime. It holds nothing but the session: one intra-op thread, one inter-op
 * thread, sequential execution, the CPU arena off and the default graph optimisation level, the same everywhere
 * the encoder is used. Not thread-safe. ONNX Runtime 1.30 and later ship no macOS x86-64 natives, so this class is
 * tested on Linux only (CI) and is the one class the local per-class coverage floor excludes.
 */
final class OnnxImageEncoder implements ImageEncoder {

    private final EncoderSpec spec;
    private final OrtEnvironment environment;
    private final OrtSession session;

    /**
     * Opens the session.
     *
     * @param spec the encoder's description
     * @param dir the directory of the ONNX file
     * @throws IllegalArgumentException when the model has no input or no output of the spec's names
     * @throws IllegalStateException when ONNX Runtime cannot open the file
     */
    OnnxImageEncoder(EncoderSpec spec, Path dir) {
        this.spec = spec;
        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setCPUArenaAllocator(false);
            this.session = environment.createSession(dir.resolve(spec.file()).toString(), options);
        } catch (OrtException e) {
            throw new IllegalStateException("cannot open " + spec.file() + ": " + e.getMessage(), e);
        }
        if (!session.getInputNames().contains(spec.input()) || !session.getOutputNames().contains(spec.output())) {
            close();
            throw new IllegalArgumentException(spec.file() + " has no input " + spec.input() + " and output "
                + spec.output() + ": it has " + session.getInputNames() + " and " + session.getOutputNames());
        }
    }

    @Override
    public float[][] encode(List<RgbFrame> frames) {
        int n = frames.size();
        float[] tensor = EncoderInput.tensor(frames, spec);
        long[] shape = {n, 3, spec.size(), spec.size()};
        try (OnnxTensor in = OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), shape);
             OrtSession.Result result = session.run(Map.of(spec.input(), in))) {
            OnnxValue value = result.get(spec.output()).orElseThrow();
            if (!(value instanceof OnnxTensor out)
                || !Arrays.equals(out.getInfo().getShape(), new long[] {n, spec.dimension()})) {
                throw new IllegalStateException("the output is not a [" + n + ", " + spec.dimension() + "] tensor");
            }
            FloatBuffer flat = out.getFloatBuffer();
            float[][] rows = new float[n][spec.dimension()];
            for (float[] row : rows) {
                flat.get(row);
                for (float v : row) {
                    if (!Float.isFinite(v)) {
                        throw new IllegalStateException("the output holds a NaN or infinite value");
                    }
                }
            }
            return rows;
        } catch (OrtException e) {
            throw new IllegalStateException("the encoder failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("cannot close the session: " + e.getMessage(), e);
        }
    }
}
