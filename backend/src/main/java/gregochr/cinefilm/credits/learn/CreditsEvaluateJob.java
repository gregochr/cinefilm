package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.CreditsCalibration.Choice;
import gregochr.cinefilm.credits.learn.CreditsCalibration.DevFilm;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import gregochr.cinefilm.credits.learn.EmbeddingCursor.FilmEmbeddings;
import gregochr.cinefilm.credits.learn.EvaluationReport.Row;
import gregochr.cinefilm.credits.learn.FeatureRunReader.FilmRows;
import gregochr.cinefilm.credits.learn.FilmScore.Film;
import gregochr.cinefilm.credits.learn.FilmScore.Prediction;
import gregochr.cinefilm.credits.learn.FilmScore.Result;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntToDoubleFunction;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run of the credits evaluation tool ({@code docs/engineering/credits-classifier-plan.md}, "L3c specification",
 * {@code CreditsEvaluateMain}). It scores every row of every film with the shipping segmenter code on stored
 * features, chooses a training run and the six segmenter numbers by the nested rule
 * ({@link CreditsCalibration}), and writes {@code evaluation.csv}, {@code calibration.json}, {@code summary.json} and,
 * with {@code --look=L5}, {@code look.json} into a new {@code <out>/eval-<stamp>/}. Everything is computed before
 * anything is written, so a refused run leaves no file. Single-threaded and deterministic. Never a Spring bean.
 *
 * <p>A development film of fold {@code k} is scored by the four pair models without {@code k}, with outer fold
 * {@code k}'s choice, which was made on the other folds' films. Test films are scored only with {@code --look=L5},
 * by the five shipping models of the final choice's run, with the final choice.
 *
 * <p>The runs of one evaluation are all model A ({@code "gbt"}, scored on the feature rows) or all model B
 * ({@code "logreg"}, scored on the embeddings of {@code --embeddings}, read by {@link EmbeddingCursor} in step with
 * the films).
 */
final class CreditsEvaluateJob {

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final String LOOK = "L5";

    private final Clock clock;
    private final GitCommit gitCommit;

    CreditsEvaluateJob(Clock clock, GitCommit gitCommit) {
        this.clock = clock;
        this.gitCommit = gitCommit;
    }

    /**
     * A film's side and fold, Plex's facts about it and its stored frames, without its rows. A test film also keeps,
     * for each run, the mean of that run's five shipping models' predictions; a development film keeps none.
     */
    private record Scene(Side side, Integer fold, FrameLabelRecord label, Film film, List<Prediction> shipping) {
    }

    /** A version 2 baseline row's key. */
    private record Key(long mediaFileId, String fingerprint) {
    }

    /** A version 2 baseline verdict, and its offset when it is {@code FOUND}. */
    private record Baseline(String verdict, OptionalLong offsetMs) {
    }

    /** Takes a film's rows and, for model B, its embeddings (null for model A). */
    private interface EmbeddedSink {
        void accept(FilmRows rows, FilmEmbeddings embeddings);
    }

    /** Runs the tool. */
    List<Path> run(CreditsEvaluateOptions options) throws IOException {
        Path dir = options.features();
        CreditsTrainJob.Hashes hashes = CreditsTrainJob.readSummary(dir.resolve("summary.json"));
        if (options.look() && !Files.isRegularFile(dir.resolve("look.json"))) {
            throw new IllegalArgumentException("--look=L5 needs a features run made with --look=L5: no look.json in "
                + dir);
        }
        Map<Long, FrameLabelRecord> labels = FeatureRunReader.labels(dir.resolve("frame-labels.json"));
        List<TrainedRun> runs = runs(options.train(), hashes);
        requireEmbeddingsFor(options, runs);
        Map<Key, Baseline> baseline = options.baseline() == null ? Map.of() : baseline(options.baseline());
        List<DevFilm> development = new ArrayList<>();
        List<Scene> test = new ArrayList<>();
        Map<DevFilm, Scene> scenes = new IdentityHashMap<>();
        EmbeddedSink sink = (rows, embeddings) -> {
            Film stored = film(rows, labels.get(rows.id()));
            if (rows.side() == Side.DEV) {
                Scene scene = new Scene(rows.side(), rows.fold(), labels.get(rows.id()), stored, List.of());
                DevFilm film = devFilm(rows, embeddings, stored, runs);
                development.add(film);
                scenes.put(film, scene);
            } else {
                test.add(new Scene(rows.side(), rows.fold(), labels.get(rows.id()), stored,
                    shippingPredictions(rows, embeddings, runs)));
            }
        };
        scoreFilms(options, dir, hashes, runs, sink);
        List<String> names = runs.stream().map(TrainedRun::name).toList();
        List<Choice> outer = new ArrayList<>();
        List<Row> devRows = new ArrayList<>();
        for (int k = 0; k < CreditsCalibration.FOLDS; k++) {
            Choice choice = CreditsCalibration.chooseOuter(k, development, runs.size());
            outer.add(choice);
            for (DevFilm film : development) {
                if (film.fold() == k) {
                    Result result = CreditsCalibration.score(choice, film, CreditsCalibration.heldOutSet(k));
                    devRows.add(row(scenes.get(film), result, names.get(choice.run()),
                        baseline));
                }
            }
        }
        Choice last = CreditsCalibration.chooseFinal(development, runs.size());
        List<Row> testRows = new ArrayList<>();
        String shippingName = names.get(last.run());
        for (Scene scene : test) {
            testRows.add(row(scene, FilmScore.of(scene.film(), scene.shipping().get(last.run()), last.params()),
                shippingName, baseline));
        }
        return write(options, hashes, names, outer, last, devRows, testRows);
    }

    // ---- Reading ------------------------------------------------------------------------------------------------

    private static List<TrainedRun> runs(List<Path> dirs, CreditsTrainJob.Hashes hashes) throws IOException {
        List<TrainedRun> runs = new ArrayList<>();
        for (Path dir : dirs) {
            TrainedRun run = TrainedRun.load(dir, hashes);
            if (!runs.isEmpty() && !runs.getFirst().manifestSha256().equals(run.manifestSha256())) {
                throw new IllegalArgumentException(run.name() + ": manifestSha256 differs from "
                    + runs.getFirst().name() + "'s");
            }
            if (!runs.isEmpty() && !runs.getFirst().type().equals(run.type())) {
                throw new IllegalArgumentException(run.name() + ": the training runs are of more than one type: "
                    + runs.getFirst().name() + " is " + runs.getFirst().type() + " and " + run.name() + " is "
                    + run.type() + "; evaluate one type at a time");
            }
            runs.add(run);
        }
        return runs;
    }

    /** Model B is scored on embeddings and model A on feature rows: each needs, or refuses, {@code --embeddings}. */
    private static void requireEmbeddingsFor(CreditsEvaluateOptions options, List<TrainedRun> runs) {
        boolean logreg = runs.getFirst().type().equals(TrainedRun.LOGREG);
        if (logreg && options.embeddings() == null) {
            throw new IllegalArgumentException("the training runs are logreg, which are scored on embeddings: "
                + "pass --embeddings <embeddings run>");
        }
        if (!logreg && options.embeddings() != null) {
            throw new IllegalArgumentException("--embeddings is for logreg runs, but the training runs are "
                + runs.getFirst().type());
        }
    }

    /** Hands each film of the features run to the sink, with its embeddings when the runs are model B's. */
    private static void scoreFilms(CreditsEvaluateOptions options, Path dir, CreditsTrainJob.Hashes hashes,
                                   List<TrainedRun> runs, EmbeddedSink sink) throws IOException {
        Path csv = dir.resolve("features.csv");
        if (options.embeddings() == null) {
            FeatureRunReader.forEachFilm(csv, options.look(), rows -> sink.accept(rows, null));
            return;
        }
        try (EmbeddingCursor cursor = EmbeddingCursor.open(options.embeddings(), dir, hashes, options.look(),
            runs)) {
            FeatureRunReader.forEachFilm(csv, options.look(), rows -> sink.accept(rows, cursor.film(rows)));
            cursor.finish();
        }
    }

    private static Film film(FilmRows rows, FrameLabelRecord label) {
        if (label == null || label.targetStartMs() == null) {
            throw new IllegalArgumentException("film " + rows.id() + " has no target in frame-labels.json");
        }
        return rows.film(label.targetStartMs(), Optional.ofNullable(label.openingType()));
    }

    /**
     * Reads the version 2 snapshot csv: {@code media_file_id}, {@code fingerprint}, {@code verdict} and
     * {@code offset_ms} by name, whatever else it holds.
     */
    private static Map<Key, Baseline> baseline(Path file) throws IOException {
        List<List<String>> rows = CsvReader.parse(Files.readString(file));
        List<String> header = rows.isEmpty() ? List.of() : rows.getFirst();
        int id = column(header, "media_file_id");
        int fingerprint = column(header, "fingerprint");
        int verdict = column(header, "verdict");
        int offset = column(header, "offset_ms");
        Map<Key, Baseline> baseline = new HashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("the baseline has a row of " + row.size() + " fields, not "
                    + header.size());
            }
            boolean found = row.get(verdict).equals(Verdict.FOUND.name());
            Key key = new Key(Long.parseLong(row.get(id)), row.get(fingerprint));
            Baseline value = new Baseline(row.get(verdict),
                found ? OptionalLong.of(Long.parseLong(row.get(offset))) : OptionalLong.empty());
            if (baseline.put(key, value) != null) {
                throw new IllegalArgumentException("the baseline repeats media file " + key.mediaFileId());
            }
        }
        return baseline;
    }

    private static int column(List<String> header, String name) {
        int at = header.indexOf(name);
        if (at < 0) {
            throw new IllegalArgumentException("the baseline has no column " + name);
        }
        return at;
    }

    // ---- Scoring ------------------------------------------------------------------------------------------------

    /**
     * The film's prediction for each set a choice may ask for, per run: its own fold's out-of-fold ensemble (the
     * pair models holding that fold) and, for each other fold {@code k}, the triple models holding both.
     */
    private static DevFilm devFilm(FilmRows rows, FilmEmbeddings embeddings, Film film, List<TrainedRun> runs) {
        int fold = rows.fold();
        List<Map<Integer, Prediction>> perRun = new ArrayList<>();
        for (TrainedRun run : runs) {
            Map<String, Prediction> single = new HashMap<>();
            Map<Integer, Prediction> sets = new LinkedHashMap<>();
            sets.put(CreditsCalibration.heldOutSet(fold), ensemble(run, CreditsCalibration.heldOutSet(fold), rows,
                embeddings, single));
            for (int k = 0; k < CreditsCalibration.FOLDS; k++) {
                if (k != fold) {
                    int set = CreditsCalibration.innerSet(k, fold);
                    sets.put(set, ensemble(run, set, rows, embeddings, single));
                }
            }
            perRun.add(sets);
        }
        return new DevFilm(fold, film, perRun);
    }

    /** The mean of the models of a set: pair models for one fold, triple models for two. */
    private static Prediction ensemble(TrainedRun run, int set, FilmRows rows, FilmEmbeddings embeddings,
                                       Map<String, Prediction> single) {
        Map<String, FrameModel> models = Integer.bitCount(set) == 1 ? run.pairs() : run.triples();
        List<Prediction> members = new ArrayList<>();
        for (String key : CreditsCalibration.memberKeys(set)) {
            members.add(single.computeIfAbsent(key, k -> predict(models.get(k), rows, embeddings)));
        }
        return Prediction.ensemble(members);
    }

    /** For each run, the mean of its five shipping models' predictions: what a test film is scored by. */
    private static List<Prediction> shippingPredictions(FilmRows rows, FilmEmbeddings embeddings,
                                                        List<TrainedRun> runs) {
        return runs.stream().map(run -> Prediction.ensemble(run.shipping().stream()
            .map(model -> predict(model, rows, embeddings)).toList())).toList();
    }

    /**
     * One model's {@code p} at every frame; NaN at a frame with no time features, whichever the model. Model A
     * predicts from the feature row, model B from the frame's embedding.
     *
     * @param model the model
     * @param rows the film's rows
     * @param embeddings the film's embeddings, which only model B reads; null for model A
     * @return the predictions
     */
    static Prediction predict(FrameModel model, FilmRows rows, FilmEmbeddings embeddings) {
        return switch (model) {
            case GradientBoostedTrees trees -> new Prediction(
                scores(rows.coarse(), i -> trees.predict(rows.coarse().get(i))),
                scores(rows.dense(), i -> trees.predict(rows.dense().get(i))), trees.base());
            case LogisticRegression regression -> new Prediction(
                scores(rows.coarse(), i -> regression.predict(embeddings.coarse()[i])),
                scores(rows.dense(), i -> regression.predict(embeddings.dense()[i])), regression.base());
        };
    }

    /** {@code p} of each frame, NaN for a frame with no time features (a film of one coarse frame has one). */
    private static double[] scores(List<double[]> features, IntToDoubleFunction score) {
        double[] p = new double[features.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = CreditsFeatures.hasTimeFeatures(features.get(i)) ? score.applyAsDouble(i) : Double.NaN;
        }
        return p;
    }

    private static Row row(Scene scene, Result result, String run, Map<Key, Baseline> baseline) {
        FrameLabelRecord label = scene.label();
        Baseline v2 = baseline.get(new Key(label.mediaFileId(), label.fingerprint()));
        return new Row(label.mediaFileId(), scene.side(), scene.fold(), result,
            Optional.ofNullable(label.openingType()), label.leadingIsland(),
            v2 == null ? null : v2.verdict(),
            v2 == null || v2.offsetMs().isEmpty() ? OptionalLong.empty()
                : OptionalLong.of(v2.offsetMs().getAsLong() - label.targetStartMs()), run);
    }

    // ---- Writing ------------------------------------------------------------------------------------------------

    private List<Path> write(CreditsEvaluateOptions options, CreditsTrainJob.Hashes hashes, List<String> names,
                             List<Choice> outer, Choice last, List<Row> devRows,
                             List<Row> testRows)
        throws IOException {
        List<Row> all = new ArrayList<>(devRows);
        all.addAll(testRows);
        all.sort(Comparator.comparingLong(Row::id));
        Instant now = clock.instant();
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("toolGitCommit", gitCommit.commit());
        summary.put("generatedAt", now.toString());
        summary.put("datasetSha256", hashes.datasetSha256());
        summary.put("labelsSha256", hashes.labelsSha256());
        summary.set("development", EvaluationReport.block(devRows));
        if (options.look()) {
            summary.set("test", EvaluationReport.block(testRows));
        }
        Path out = options.out().toAbsolutePath().normalize();
        Files.createDirectories(out);
        Path runDir = out.resolve("eval-" + STAMP.format(now));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        List<Path> written = new ArrayList<>(List.of(runDir));
        written.add(Files.writeString(runDir.resolve("evaluation.csv"), EvaluationReport.csv(all)));
        written.add(Files.write(runDir.resolve("calibration.json"), MAPPER.writeValueAsBytes(
            EvaluationReport.calibration(names, outer, last, gitCommit.commit(), hashes))));
        written.add(Files.write(runDir.resolve("summary.json"), MAPPER.writeValueAsBytes(summary)));
        if (options.look()) {
            written.add(Files.write(runDir.resolve("look.json"), MAPPER.writeValueAsBytes(
                new CreditsFeaturesJob.LookLog(LOOK, now.toString(), gitCommit.commit(), testRows.size()))));
        }
        return written;
    }
}
