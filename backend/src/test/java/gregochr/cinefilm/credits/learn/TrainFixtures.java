package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Synthetic inputs of the training tool: rows of {@code features.csv}, a summary and a manifest. */
final class TrainFixtures {

    static final String MANIFEST_TEXT = "{\"films\":[]}";

    private TrainFixtures() {
    }

    /** A frame {@link OpeningType#frameKind} calls LIT, with time features; {@code id} goes in {@code plain}. */
    static double[] lit(double id, double mean, double bands) {
        double[] f = new double[CreditsFeatures.COUNT];
        f[0] = mean;
        f[1] = 10;
        f[2] = 100;
        f[3] = 200;
        f[4] = id;
        f[11] = bands;
        f[CreditsFeatures.MAD] = 1;
        f[CreditsFeatures.INK_CHANGE] = 1;
        return f;
    }

    /** A LIT frame with a mean of 100. */
    static double[] lit(double id, double bands) {
        return lit(id, 100, bands);
    }

    /** A bright flat frame: PLATE. */
    static double[] plate(double id, double bands) {
        double[] f = lit(id, 200, bands);
        f[1] = 100;
        f[2] = 105;
        f[3] = 110;
        return f;
    }

    /** A black frame: DARK. */
    static double[] dark(double id, double bands) {
        double[] f = lit(id, 0, bands);
        f[1] = 0;
        f[2] = 0;
        f[3] = 0;
        return f;
    }

    /** The same frame without its time features. */
    static double[] noTime(double[] features) {
        double[] f = features.clone();
        f[CreditsFeatures.MAD] = Double.NaN;
        f[CreditsFeatures.INK_CHANGE] = Double.NaN;
        return f;
    }

    /** A development row of film {@code id}. */
    static String row(long id, int fold, String kind, long offsetMs, String label, double[] features) {
        return FeatureRows.row(id, fold, Side.DEV, kind, offsetMs, label, features);
    }

    /** A whole {@code features.csv}: the header, then the rows. */
    static String csv(String... rows) {
        return FeatureRows.header() + String.join("", rows);
    }

    /** Writes the three input files and returns them: features, summary and manifest. */
    static Path[] inputs(Path dir, String featuresCsv) throws IOException {
        Path features = Files.writeString(dir.resolve("features.csv"), featuresCsv);
        Path summary = Files.writeString(dir.resolve("summary.json"),
            "{\"featureVersion\":" + CreditsFeatures.FEATURE_VERSION
                + ",\"datasetSha256\":\"ds-hash\",\"labelsSha256\":\"labels-hash\"}");
        Path manifest = Files.writeString(dir.resolve("split.json"), MANIFEST_TEXT);
        return new Path[] {features, summary, manifest};
    }

    /** The SHA-256 of {@link #MANIFEST_TEXT}, in hex. */
    static String manifestSha() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(MANIFEST_TEXT.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The command line for {@link #inputs}, with extra arguments. */
    static String[] args(Path[] inputs, Path out, String... extra) {
        String[] base = {"--features", inputs[0].toString(), "--summary", inputs[1].toString(), "--manifest",
            inputs[2].toString(), "--out", out.toString()};
        String[] all = Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }
}
