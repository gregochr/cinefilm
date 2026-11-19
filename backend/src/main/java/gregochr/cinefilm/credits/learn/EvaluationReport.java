package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.CreditsCalibration.Choice;
import gregochr.cinefilm.credits.learn.CreditsCalibration.Counts;
import gregochr.cinefilm.credits.learn.CreditsSegmenter.Verdict;
import gregochr.cinefilm.credits.learn.FilmScore.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The files the evaluation tool writes besides {@code look.json} ({@code docs/engineering/credits-classifier-plan.md},
 * "L3c specification", {@code CreditsEvaluateMain}): {@code evaluation.csv}, {@code calibration.json} and
 * {@code summary.json}'s blocks. Pure: it
 * turns scored rows into text and JSON.
 */
final class EvaluationReport {

    /** The columns of {@code evaluation.csv}, exactly. */
    static final String HEADER = "mediaFileId,side,fold,verdict,t,f,covered,aligned,earlyError,goalError,openingAtT,"
        + "marginBand,plexOpening,v2Verdict,v2Error,run,leadingIsland\n";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** The group of films with no Plex opening type. */
    private static final String NONE = "none";

    private EvaluationReport() {
    }

    /** One film's row. */
    record Row(long id, Side side, Integer fold, Result result, Optional<FrameKind> plexOpening,
               Boolean leadingIsland, String v2Verdict, OptionalLong v2Error, String run) {
    }

    /** {@code evaluation.csv}: the header, then one line per row, in the order given. */
    static String csv(List<Row> rows) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (Row row : rows) {
            Result r = row.result();
            List<String> fields = new ArrayList<>();
            fields.add(Long.toString(row.id()));
            fields.add(row.side().json());
            fields.add(row.fold() == null ? null : row.fold().toString());
            fields.add(r.verdict().name());
            fields.add(cell(r.t()));
            fields.add(cell(r.f()));
            fields.add(r.covered().map(String::valueOf).orElse(null));
            fields.add(r.aligned().map(String::valueOf).orElse(null));
            fields.add(cell(r.earlyError()));
            fields.add(cell(r.goalError()));
            fields.add(r.openingAtT().map(Enum::name).orElse(null));
            fields.add(r.marginBand().map(band -> band.name().toLowerCase(Locale.ROOT)).orElse(null));
            fields.add(row.plexOpening().map(Enum::name).orElse(null));
            fields.add(row.v2Verdict());
            fields.add(cell(row.v2Error()));
            fields.add(row.run());
            fields.add(row.leadingIsland() == null ? null : row.leadingIsland().toString());
            csv.append(CsvWriter.row(fields));
        }
        return csv.toString();
    }

    private static String cell(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : null;
    }

    /** {@code calibration.json}: where each fold's calibrated values are stored. */
    static ObjectNode calibration(List<String> runs, List<Choice> outer, Choice last, String toolGitCommit,
                                  CreditsTrainJob.Hashes hashes) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode grid = root.putObject("grid");
        numbers(grid.putArray("lambda"), CreditsCalibration.LAMBDAS);
        numbers(grid.putArray("delta"), CreditsCalibration.DELTAS);
        ArrayNode gaps = grid.putArray("gapFrames");
        for (int gap : CreditsCalibration.GAP_FRAMES) {
            gaps.add(gap);
        }
        numbers(grid.putArray("theta"), CreditsCalibration.THETAS);
        numbers(grid.putArray("sigma"), CreditsCalibration.SIGMAS);
        numbers(grid.putArray("thetaDense"), CreditsCalibration.THETA_DENSES);
        grid.put("points", CreditsCalibration.POINTS);
        ObjectNode folds = root.putObject("outer");
        for (int k = 0; k < outer.size(); k++) {
            choice(folds.putObject(Integer.toString(k)), runs, outer.get(k));
        }
        choice(root.putObject("final"), runs, last);
        root.put("toolGitCommit", toolGitCommit);
        root.put("datasetSha256", hashes.datasetSha256());
        root.put("labelsSha256", hashes.labelsSha256());
        return root;
    }

    private static void numbers(ArrayNode array, double[] values) {
        for (double value : values) {
            array.add(value);
        }
    }

    private static void choice(ObjectNode node, List<String> runs, Choice choice) {
        node.put("run", runs.get(choice.run()));
        SegmenterParams p = choice.params();
        ObjectNode params = node.putObject("params");
        params.put("lambda", p.lambda());
        params.put("delta", p.delta());
        params.put("gapFrames", p.gapFrames());
        params.put("sigma", p.sigma());
        params.put("theta", p.theta());
        params.put("thetaDense", p.thetaDense());
        Counts c = choice.counts();
        ObjectNode counts = node.putObject("counts");
        counts.put("early15", c.early15());
        counts.put("early5", c.early5());
        counts.put("violations", c.violations());
        counts.put("within10", c.within10());
        counts.put("within60", c.within60());
        counts.put("films", c.films());
    }

    /**
     * One side's block of {@code summary.json}: the early counts by the opening at {@code t} and the margin band, over
     * {@code FOUND} films; and the goal shares by Plex's opening type ({@code DARK}, {@code PLATE}, {@code LIT} and
     * {@code none}), each over every film of that type.
     *
     * @param rows the side's rows
     * @return the JSON object
     */
    static ObjectNode block(List<Row> rows) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("films", rows.size());
        ObjectNode early = block.putObject("early");
        for (FrameKind opening : FrameKind.values()) {
            ObjectNode byBand = early.putObject(opening.name());
            for (FilmScore.Band band : FilmScore.Band.values()) {
                List<Result> found = rows.stream().map(Row::result)
                    .filter(r -> r.verdict() == Verdict.FOUND && r.openingAtT().orElseThrow() == opening
                        && r.marginBand().orElseThrow() == band)
                    .toList();
                ObjectNode group = byBand.putObject(band.name().toLowerCase(Locale.ROOT));
                group.put("found", found.size());
                group.put("earlyBelow15s", count(found, e -> e < CreditsCalibration.EARLY_FAR_MS));
                group.put("earlyBelow5s", count(found, e -> e < CreditsCalibration.EARLY_NEAR_MS));
            }
        }
        ObjectNode goals = block.putObject("goals");
        for (FrameKind opening : FrameKind.values()) {
            goal(goals.putObject(opening.name()), rows, row -> row.plexOpening().equals(Optional.of(opening)));
        }
        goal(goals.putObject(NONE), rows, row -> row.plexOpening().isEmpty());
        return block;
    }

    private static int count(List<Result> found, LongPredicate early) {
        return (int) found.stream().filter(r -> early.test(r.earlyError().getAsLong())).count();
    }

    /** The counts and shares of one Plex opening type: within 10 s, and within 60 s or not found. */
    private static void goal(ObjectNode node, List<Row> all, Predicate<Row> type) {
        List<Result> results = all.stream().filter(type).map(Row::result).toList();
        int within10 = 0;
        int within60 = 0;
        for (Result r : results) {
            boolean found = r.verdict() == Verdict.FOUND;
            long error = found ? Math.abs(r.goalError().getAsLong()) : Long.MAX_VALUE;
            if (error <= CreditsCalibration.GOAL_NEAR_MS) {
                within10++;
            }
            if (!found || error <= CreditsCalibration.GOAL_FAR_MS) {
                within60++;
            }
        }
        node.put("films", results.size());
        node.put("within10s", within10);
        node.put("within60sOrNotFound", within60);
        share(node, "share10s", within10, results.size());
        share(node, "share60sOrNotFound", within60, results.size());
    }

    private static void share(ObjectNode node, String name, int count, int films) {
        if (films == 0) {
            node.putNull(name);
        } else {
            node.put(name, (double) count / films);
        }
    }
}
