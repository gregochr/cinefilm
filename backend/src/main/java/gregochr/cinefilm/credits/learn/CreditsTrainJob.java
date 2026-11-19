package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
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
    static final int FOLDS = FoldModels.FOLDS;

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

    /** The two hashes {@code summary.json} carries. */
    record Hashes(String datasetSha256, String labelsSha256) {
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
        TrainRows rows = TrainRows.read(options.features(), options.withDense(), options.litTextVariant());
        FoldModels<GradientBoostedTrees> models = FoldModels.build(rows.kept(),
            held -> fit(rows.kept(), held, options.params()), (model, row) -> model.predict(row.features()));
        ObjectNode logLoss = models.logLoss();

        Path out = options.out().toAbsolutePath().normalize();
        Files.createDirectories(out);
        Path runDir = out.resolve("train-" + STAMP.format(clock.instant()));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        Path model = runDir.resolve("model.json");
        Files.write(model, MAPPER.writeValueAsBytes(modelJson(options, hashes, manifestSha, models.shipping())));
        Path oofModels = runDir.resolve("oof-models.json");
        Files.write(oofModels, MAPPER.writeValueAsBytes(modelsJson(models.pairs())));
        Path oofLogLoss = runDir.resolve("oof-logloss.json");
        Files.write(oofLogLoss, MAPPER.writeValueAsBytes(logLoss));
        Path nestedModels = runDir.resolve("nested-models.json");
        Files.write(nestedModels, MAPPER.writeValueAsBytes(modelsJson(models.triples())));
        return List.of(runDir, model, oofModels, oofLogLoss, nestedModels);
    }

    // ---- Reading ------------------------------------------------------------------------------------------------

    static Hashes readSummary(Path summary) throws IOException {
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

    // ---- Fitting ------------------------------------------------------------------------------------------------

    private GradientBoostedTrees fit(List<TrainRows.Kept> rows, Set<Integer> held, GbtParams params) {
        List<TrainRows.Kept> train = rows.stream().filter(row -> !held.contains(row.fold())).toList();
        double[][] features = new double[train.size()][];
        int[] labels = new int[train.size()];
        for (int i = 0; i < features.length; i++) {
            features[i] = train.get(i).features();
            labels[i] = train.get(i).label();
        }
        return fitter.fit(features, labels, params);
    }

    /**
     * The natural-log loss of one prediction.
     *
     * @param p the probability of credits, clamped to {@code [1e-15, 1 - 1e-15]}
     * @param label 1 for credits, 0 for film
     * @return {@code -ln p} for credits, {@code -ln(1 - p)} for film
     */
    static double loss(double p, int label) {
        return FoldModels.loss(p, label);
    }

    // ---- Writing ------------------------------------------------------------------------------------------------

    /** The models as one object, each under its key ({@code "k,j"} or {@code "k,j,m"}), in the order given. */
    private static ObjectNode modelsJson(Map<String, GradientBoostedTrees> models) {
        ObjectNode json = MAPPER.createObjectNode();
        models.forEach((key, value) -> json.set(key, MAPPER.readTree(value.toJson())));
        return json;
    }

    private ObjectNode modelJson(CreditsTrainOptions options, Hashes hashes, String manifestSha,
                                 List<GradientBoostedTrees> shipping) {
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
