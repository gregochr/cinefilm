package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.CreditsCalibration.Choice;
import gregochr.cinefilm.credits.learn.CreditsCalibration.DevFilm;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
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

    /** A film's rows, Plex's facts about it and its stored frames. */
    private record Scene(FilmRows rows, FrameLabelRecord label, Film film) {
    }

    /** A version 2 baseline row's key. */
    private record Key(long mediaFileId, String fingerprint) {
    }

    /** A version 2 baseline verdict, and its offset when it is {@code FOUND}. */
    private record Baseline(String verdict, OptionalLong offsetMs) {
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
        Map<Key, Baseline> baseline = options.baseline() == null ? Map.of() : baseline(options.baseline());
        List<DevFilm> development = new ArrayList<>();
        List<Scene> test = new ArrayList<>();
        Map<DevFilm, Scene> scenes = new IdentityHashMap<>();
        for (FilmRows rows : FeatureRunReader.read(dir.resolve("features.csv"), options.look())) {
            Scene scene = scene(rows, labels.get(rows.id()));
            if (rows.side() == Side.DEV) {
                DevFilm film = devFilm(rows, scene.film(), runs);
                development.add(film);
                scenes.put(film, scene);
            } else {
                test.add(scene);
            }
        }
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
        TrainedRun shipping = runs.get(last.run());
        for (Scene scene : test) {
            Prediction prediction = Prediction.ensemble(shipping.shipping().stream()
                .map(model -> predict(model, scene.rows())).toList());
            testRows.add(row(scene, FilmScore.of(scene.film(), prediction, last.params()), shipping.name(),
                baseline));
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
            runs.add(run);
        }
        return runs;
    }

    private static Scene scene(FilmRows rows, FrameLabelRecord label) {
        if (label == null || label.targetStartMs() == null) {
            throw new IllegalArgumentException("film " + rows.id() + " has no target in frame-labels.json");
        }
        return new Scene(rows, label, rows.film(label.targetStartMs(), Optional.ofNullable(label.openingType())));
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
    private static DevFilm devFilm(FilmRows rows, Film film, List<TrainedRun> runs) {
        int fold = rows.fold();
        List<Map<Integer, Prediction>> perRun = new ArrayList<>();
        for (TrainedRun run : runs) {
            Map<String, Prediction> single = new HashMap<>();
            Map<Integer, Prediction> sets = new LinkedHashMap<>();
            sets.put(CreditsCalibration.heldOutSet(fold), ensemble(run, CreditsCalibration.heldOutSet(fold), rows,
                single));
            for (int k = 0; k < CreditsCalibration.FOLDS; k++) {
                if (k != fold) {
                    int set = CreditsCalibration.innerSet(k, fold);
                    sets.put(set, ensemble(run, set, rows, single));
                }
            }
            perRun.add(sets);
        }
        return new DevFilm(fold, film, perRun);
    }

    /** The mean of the models of a set: pair models for one fold, triple models for two. */
    private static Prediction ensemble(TrainedRun run, int set, FilmRows rows, Map<String, Prediction> single) {
        Map<String, GradientBoostedTrees> models = Integer.bitCount(set) == 1 ? run.pairs() : run.triples();
        List<Prediction> members = new ArrayList<>();
        for (String key : CreditsCalibration.memberKeys(set)) {
            members.add(single.computeIfAbsent(key, k -> predict(models.get(k), rows)));
        }
        return Prediction.ensemble(members);
    }

    /** One model's {@code p} at every frame; NaN at a dense frame with no time features. */
    private static Prediction predict(GradientBoostedTrees model, FilmRows rows) {
        double[] coarse = rows.coarse().stream().mapToDouble(features -> p(model, features)).toArray();
        double[] dense = rows.dense().stream().mapToDouble(features -> p(model, features)).toArray();
        return new Prediction(coarse, dense, model.base());
    }

    /** {@code p} of a frame, NaN for a frame with no time features (a film of one coarse frame has one). */
    private static double p(GradientBoostedTrees model, double[] features) {
        return CreditsFeatures.hasTimeFeatures(features) ? model.predict(features) : Double.NaN;
    }

    private static Row row(Scene scene, Result result, String run, Map<Key, Baseline> baseline) {
        FrameLabelRecord label = scene.label();
        Baseline v2 = baseline.get(new Key(label.mediaFileId(), label.fingerprint()));
        return new Row(label.mediaFileId(), scene.rows().side(), scene.rows().fold(), result,
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
