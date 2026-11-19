package gregochr.cinefilm.credits.learn;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run of the credits training tool ({@code docs/engineering/credits-classifier-plan.md}, "L3b specification",
 * {@code CreditsTrainMain}): it reads a features run's {@code features.csv}, fits the five shipping models, the ten
 * pair models and the ten triple models, and writes {@code model.json}, {@code oof-models.json},
 * {@code oof-logloss.json} and {@code nested-models.json} into
 * {@code <out>/train-<yyyyMMdd'T'HHmmss'Z'>/} (made with {@code createDirectory}, so a run never overwrites an
 * earlier one). Everything is computed before anything is written, so a refused run leaves no file. No database, no
 * network, never a Spring bean.
 *
 * <p><strong>Only development rows are read.</strong> The first row whose side is not {@code dev}, or whose fold is
 * empty, stops the run; there is no flag that allows one. Rows must be in the order {@link CreditsFeaturesJob} writes
 * them: {@code (mediaFileId, kind, offsetMs)}, each strictly after the one before, so a fit does not depend on how a
 * file was assembled.
 *
 * <p>Fold {@code k}'s out-of-fold prediction for a row is the mean of the four pair models {@code {k, j}}, none of
 * which saw a row of fold {@code k}. The triple model {@code {k, j, m}} is trained on the rows in none of the three
 * folds; {@code CreditsEvaluateMain} scores a fold's films with the mean of the triples that skip it.
 */
final class CreditsTrainJob {

    /** The number of cross-validation folds. */
    static final int FOLDS = 5;

    private static final String COARSE = FilmCheck.COARSE;
    private static final String DENSE = FilmCheck.DENSE;
    private static final double CLAMP = 1e-15;
    private static final int P10 = 1;
    private static final int P50 = 2;
    private static final int P90 = 3;
    private static final int BANDS = 11;
    private static final int LIT_TEXT_MIN_BANDS = 3;
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final GitCommit gitCommit;
    private final Fitter fitter;

    CreditsTrainJob(Clock clock, GitCommit gitCommit) {
        this(clock, gitCommit, GradientBoostedTrees::fit);
    }

    CreditsTrainJob(Clock clock, GitCommit gitCommit, Fitter fitter) {
        this.clock = clock;
        this.gitCommit = gitCommit;
        this.fitter = fitter;
    }

    /** Fits one model; the seam that lets a test see exactly which rows each model was given. */
    @FunctionalInterface
    interface Fitter {
        /**
         * Fits a model.
         *
         * @param rows the feature rows
         * @param labels one label per row, 1 for credits and 0 for film
         * @param params the settings
         * @return the model
         */
        GradientBoostedTrees fit(double[][] rows, int[] labels, GbtParams params);
    }

    /**
     * A row that is kept for training.
     *
     * @param fold its fold, 0 to 4
     * @param coarse whether it is a coarse frame
     * @param label 1 for credits, 0 for film
     * @param features its {@link CreditsFeatures#COUNT} features
     */
    private record TrainRow(int fold, boolean coarse, int label, double[] features) {
    }

    /** The two hashes {@code summary.json} carries. */
    private record Hashes(String datasetSha256, String labelsSha256) {
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return what was written: the run directory, {@code model.json}, {@code oof-models.json},
     *     {@code oof-logloss.json} and {@code nested-models.json}
     * @throws IOException when an input cannot be read or the output cannot be written
     * @throws IllegalArgumentException when a file is not what a features run writes, a row is a test row or has no
     *     fold, rows are out of order, a fold has no coarse row left or a model cannot be fitted (nothing is written)
     */
    List<Path> run(CreditsTrainOptions options) throws IOException {
        Hashes hashes = readSummary(options.summary());
        String manifestSha = hex(digest().digest(Files.readAllBytes(options.manifest())));
        List<TrainRow> rows = readRows(options.features(), options);
        for (int k = 0; k < FOLDS; k++) {
            int fold = k;
            if (rows.stream().noneMatch(row -> row.fold() == fold && row.coarse())) {
                throw new IllegalArgumentException("fold " + k + " has no coarse row left to train on");
            }
        }
        GradientBoostedTrees[] shipping = new GradientBoostedTrees[FOLDS];
        for (int k = 0; k < FOLDS; k++) {
            shipping[k] = fit(rows, Set.of(k), options.params());
        }
        Map<String, GradientBoostedTrees> pairs = new LinkedHashMap<>();
        for (int k = 0; k < FOLDS; k++) {
            for (int j = k + 1; j < FOLDS; j++) {
                pairs.put(k + "," + j, fit(rows, Set.of(k, j), options.params()));
            }
        }
        Map<String, GradientBoostedTrees> triples = new LinkedHashMap<>();
        for (int k = 0; k < FOLDS; k++) {
            for (int j = k + 1; j < FOLDS; j++) {
                for (int m = j + 1; m < FOLDS; m++) {
                    triples.put(k + "," + j + "," + m, fit(rows, Set.of(k, j, m), options.params()));
                }
            }
        }
        ObjectNode logLoss = outOfFold(rows, pairs);

        Path out = options.out().toAbsolutePath().normalize();
        Files.createDirectories(out);
        Path runDir = out.resolve("train-" + STAMP.format(clock.instant()));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        Path model = runDir.resolve("model.json");
        Files.write(model, MAPPER.writeValueAsBytes(modelJson(options, hashes, manifestSha, shipping)));
        Path oofModels = runDir.resolve("oof-models.json");
        Files.write(oofModels, MAPPER.writeValueAsBytes(modelsJson(pairs)));
        Path oofLogLoss = runDir.resolve("oof-logloss.json");
        Files.write(oofLogLoss, MAPPER.writeValueAsBytes(logLoss));
        Path nestedModels = runDir.resolve("nested-models.json");
        Files.write(nestedModels, MAPPER.writeValueAsBytes(modelsJson(triples)));
        return List.of(runDir, model, oofModels, oofLogLoss, nestedModels);
    }

    // ---- Reading ------------------------------------------------------------------------------------------------

    private static Hashes readSummary(Path summary) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(summary));
        JsonNode version = root.get("featureVersion");
        if (version == null || !version.isInt() || version.intValue() != CreditsFeatures.FEATURE_VERSION) {
            throw new IllegalArgumentException("summary.json has feature version " + version
                + ", the code has " + CreditsFeatures.FEATURE_VERSION);
        }
        return new Hashes(text(root, "datasetSha256"), text(root, "labelsSha256"));
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("summary.json has no text field " + name);
        }
        return value.stringValue();
    }

    /**
     * Reads {@code features.csv}, one line at a time, and keeps the rows that train: the left-out, the rows with no
     * time features and, without {@code --with-dense}, the dense rows are dropped. With {@code --lit-text-variant},
     * a {@code LIT_OPENING} or {@code LIT_RUN} row that looks like lit text is credits before the drops.
     */
    private static List<TrainRow> readRows(Path features, CreditsTrainOptions options) throws IOException {
        List<TrainRow> kept = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(features)) {
            List<String> header = fields(reader.readLine());
            if (!FeatureRows.COLUMNS.equals(header)) {
                throw new IllegalArgumentException("features.csv does not start with the features header");
            }
            long[] previous = null;
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                List<String> row = fields(line);
                if (row.isEmpty()) {
                    continue;
                }
                String where = "features.csv line " + number + ": ";
                if (row.size() != FeatureRows.COLUMNS.size()) {
                    throw new IllegalArgumentException(where + "expected " + FeatureRows.COLUMNS.size()
                        + " fields, found " + row.size());
                }
                if (!row.get(2).equals(Side.DEV.json()) || row.get(1).isEmpty()) {
                    throw new IllegalArgumentException(where
                        + "a row that is not a development row with a fold is refused; nothing was written");
                }
                previous = checkOrder(row, previous, where);
                TrainRow train = train(row, options, where);
                if (train != null) {
                    kept.add(train);
                }
            }
        }
        return kept;
    }

    private static List<String> fields(String line) {
        List<List<String>> parsed = line == null ? List.of() : CsvReader.parse(line);
        return parsed.isEmpty() ? List.of() : parsed.getFirst();
    }

    /** The row's {@code (mediaFileId, kind, offsetMs)}, which must be strictly after the previous row's. */
    private static long[] checkOrder(List<String> row, long[] previous, String where) {
        String kind = row.get(3);
        if (!kind.equals(COARSE) && !kind.equals(DENSE)) {
            throw new IllegalArgumentException(where + "kind must be coarse or dense: " + kind);
        }
        long[] key = {number(row.get(0), "mediaFileId", where), kind.equals(COARSE) ? 0 : 1,
            number(row.get(4), "offsetMs", where)};
        if (previous != null && compare(previous, key) >= 0) {
            throw new IllegalArgumentException(where
                + "rows are not in (mediaFileId, kind, offsetMs) order, each after the one before");
        }
        return key;
    }

    private static int compare(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static long number(String text, String name, String where) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + name + " is not a whole number: " + text, e);
        }
    }

    /** The row to train on, or null when it is dropped. */
    private static TrainRow train(List<String> row, CreditsTrainOptions options, String where) {
        long fold = number(row.get(1), "fold", where);
        if (fold < 0 || fold >= FOLDS) {
            throw new IllegalArgumentException(where + "fold must be 0 to " + (FOLDS - 1) + ": " + fold);
        }
        double[] features = features(row, where);
        int label = label(row.get(5), features, options.litTextVariant(), where);
        boolean coarse = row.get(3).equals(COARSE);
        if (label < 0 || !CreditsFeatures.hasTimeFeatures(features) || !coarse && !options.withDense()) {
            return null;
        }
        return new TrainRow((int) fold, coarse, label, features);
    }

    private static double[] features(List<String> row, String where) {
        double[] features = new double[CreditsFeatures.COUNT];
        for (int i = 0; i < features.length; i++) {
            String text = row.get(FeatureRows.COLUMNS.size() - features.length + i);
            if (text.isEmpty() && (i == CreditsFeatures.MAD || i == CreditsFeatures.INK_CHANGE)) {
                features[i] = Double.NaN;
                continue;
            }
            try {
                features[i] = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + CreditsFeatures.COLUMNS.get(i)
                    + " is not a number: " + text, e);
            }
            if (!Double.isFinite(features[i])) {
                throw new IllegalArgumentException(where + CreditsFeatures.COLUMNS.get(i) + " is not finite");
            }
        }
        return features;
    }

    /** 1 for credits, 0 for film, -1 for a left-out row. */
    private static int label(String text, double[] features, boolean litText, String where) {
        if (text.equals("credits")) {
            return 1;
        }
        if (text.equals("film")) {
            return 0;
        }
        LeftOutReason reason;
        try {
            reason = LeftOutReason.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "unknown label: " + text, e);
        }
        boolean lit = reason == LeftOutReason.LIT_OPENING || reason == LeftOutReason.LIT_RUN;
        return litText && lit && isLitText(features) ? 1 : -1;
    }

    /** A frame {@link OpeningType#frameKind} calls {@code LIT} that has at least three text bands. */
    private static boolean isLitText(double[] f) {
        return OpeningType.frameKind(f[0], (int) f[P10], (int) f[P50], (int) f[P90]) == FrameKind.LIT
            && f[BANDS] >= LIT_TEXT_MIN_BANDS;
    }

    // ---- Fitting ------------------------------------------------------------------------------------------------

    private GradientBoostedTrees fit(List<TrainRow> rows, Set<Integer> held, GbtParams params) {
        List<TrainRow> train = rows.stream().filter(row -> !held.contains(row.fold())).toList();
        double[][] features = new double[train.size()][];
        int[] labels = new int[train.size()];
        for (int i = 0; i < features.length; i++) {
            features[i] = train.get(i).features();
            labels[i] = train.get(i).label();
        }
        return fitter.fit(features, labels, params);
    }

    // ---- Out-of-fold log loss -----------------------------------------------------------------------------------

    /**
     * Each fold's coarse rows are predicted by the mean of the four pair models whose pair holds that fold.
     *
     * @return {@code {"folds": {"0": {"rows", "logLoss"}, ...}, "pooled": {"rows", "logLoss"}}}
     */
    private static ObjectNode outOfFold(List<TrainRow> rows, Map<String, GradientBoostedTrees> pairs) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode folds = result.putObject("folds");
        double pooledSum = 0;
        int pooledRows = 0;
        for (int k = 0; k < FOLDS; k++) {
            double sum = 0;
            int count = 0;
            for (TrainRow row : rows) {
                if (row.fold() == k && row.coarse()) {
                    sum += loss(prediction(pairs, k, row.features()), row.label());
                    count++;
                }
            }
            ObjectNode fold = folds.putObject(Integer.toString(k));
            fold.put("rows", count);
            fold.put("logLoss", sum / count);
            pooledSum += sum;
            pooledRows += count;
        }
        ObjectNode pooled = result.putObject("pooled");
        pooled.put("rows", pooledRows);
        pooled.put("logLoss", pooledSum / pooledRows);
        return result;
    }

    private static double prediction(Map<String, GradientBoostedTrees> pairs, int k, double[] features) {
        double sum = 0;
        for (int j = 0; j < FOLDS; j++) {
            if (j != k) {
                sum += pairs.get(Math.min(j, k) + "," + Math.max(j, k)).predict(features);
            }
        }
        return sum / (FOLDS - 1);
    }

    /**
     * The natural-log loss of one prediction.
     *
     * @param p the probability of credits, clamped to {@code [1e-15, 1 - 1e-15]}
     * @param label 1 for credits, 0 for film
     * @return {@code -ln p} for credits, {@code -ln(1 - p)} for film
     */
    static double loss(double p, int label) {
        double clamped = Math.min(1 - CLAMP, Math.max(CLAMP, p));
        return -Math.log(label == 1 ? clamped : 1 - clamped);
    }

    // ---- Writing ------------------------------------------------------------------------------------------------

    /** The models as one object, each under its key ({@code "k,j"} or {@code "k,j,m"}), in the order given. */
    private static ObjectNode modelsJson(Map<String, GradientBoostedTrees> models) {
        ObjectNode json = MAPPER.createObjectNode();
        models.forEach((key, value) -> json.set(key, MAPPER.readTree(value.toJson())));
        return json;
    }

    private ObjectNode modelJson(CreditsTrainOptions options, Hashes hashes, String manifestSha,
                                 GradientBoostedTrees[] shipping) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "gbt");
        root.put("featureVersion", CreditsFeatures.FEATURE_VERSION);
        GbtParams params = options.params();
        ObjectNode p = root.putObject("params");
        p.put("trees", params.trees());
        p.put("depth", params.depth());
        p.put("learningRate", params.learningRate());
        p.put("bins", params.bins());
        p.put("minLeaf", params.minLeaf());
        p.put("lambda", params.lambda());
        root.put("withDense", options.withDense());
        root.put("litTextVariant", options.litTextVariant());
        ArrayNode models = root.putArray("models");
        for (GradientBoostedTrees model : shipping) {
            models.add(MAPPER.readTree(model.toJson()));
        }
        root.put("toolGitCommit", gitCommit.commit());
        root.put("datasetSha256", hashes.datasetSha256());
        root.put("labelsSha256", hashes.labelsSha256());
        root.put("manifestSha256", manifestSha);
        return root;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
