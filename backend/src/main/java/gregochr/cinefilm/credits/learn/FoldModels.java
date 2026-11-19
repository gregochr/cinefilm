package gregochr.cinefilm.credits.learn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The fold structure of a training run, for any model type: five shipping models, ten pair models and ten triple
 * models, and the out-of-fold log loss ({@code docs/engineering/credits-classifier-plan.md}, "The training scaffold
 * (L4b-4a)").
 *
 * <p>The shipping model {@code k} is fitted without fold {@code k}; the pair model {@code "k,j"} (k below j) without
 * folds {@code k} and {@code j}; the triple model {@code "k,j,m"} without those three. Fold {@code k}'s out-of-fold
 * prediction for a row is the mean of the four pair models whose pair holds {@code k}, none of which saw a row of
 * fold {@code k}.
 *
 * @param shipping the five shipping models, by the fold each leaves out
 * @param pairs the ten pair models, keyed {@code "k,j"}, in the order fitted
 * @param triples the ten triple models, keyed {@code "k,j,m"}, in the order fitted
 * @param logLoss {@code {"folds": {"0": {"rows", "logLoss"}, ...}, "pooled": {"rows", "logLoss"}}}, over the coarse
 *     kept rows
 * @param <M> the model type
 */
record FoldModels<M>(List<M> shipping, Map<String, M> pairs, Map<String, M> triples, ObjectNode logLoss) {

    /** The number of cross-validation folds. */
    static final int FOLDS = 5;

    private static final double CLAMP = 1e-15;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Fits a model on every kept row outside the folds given. */
    @FunctionalInterface
    interface Fit<M> {
        /**
         * Fits a model.
         *
         * @param held the folds left out
         * @return the model
         */
        M without(Set<Integer> held);
    }

    /** Predicts the probability of credits for a kept row. */
    @FunctionalInterface
    interface Predict<M> {
        /**
         * Predicts one row.
         *
         * @param model the model
         * @param row the kept row
         * @return the probability of credits
         */
        double of(M model, TrainRows.Kept row);
    }

    /**
     * Fits the shipping, pair and triple models, in that order (each by ascending fold numbers), then scores the
     * coarse kept rows out of fold. Every fold must have a coarse row.
     *
     * @param kept the kept rows
     * @param fit fits a model without a set of folds
     * @param predict predicts a kept row
     * @param <M> the model type
     * @return the models and the out-of-fold log loss
     * @throws IllegalArgumentException when a fold has no coarse kept row (nothing is fitted)
     */
    static <M> FoldModels<M> build(List<TrainRows.Kept> kept, Fit<M> fit, Predict<M> predict) {
        for (int k = 0; k < FOLDS; k++) {
            int fold = k;
            if (kept.stream().noneMatch(row -> row.fold() == fold && row.coarse())) {
                throw new IllegalArgumentException("fold " + k + " has no coarse row left to train on");
            }
        }
        List<M> shipping = new ArrayList<>();
        for (int[] held : combinations(1)) {
            shipping.add(fit.without(set(held)));
        }
        Map<String, M> pairs = fitAll(2, fit);
        Map<String, M> triples = fitAll(3, fit);
        return new FoldModels<>(shipping, pairs, triples, outOfFold(kept, pairs, predict));
    }

    /** Fits a model without each combination of {@code size} folds, keyed by the folds joined with commas. */
    private static <M> Map<String, M> fitAll(int size, Fit<M> fit) {
        Map<String, M> models = new LinkedHashMap<>();
        for (int[] held : combinations(size)) {
            models.put(Arrays.stream(held).mapToObj(Integer::toString).collect(Collectors.joining(",")),
                fit.without(set(held)));
        }
        return models;
    }

    private static Set<Integer> set(int[] folds) {
        return Arrays.stream(folds).boxed().collect(Collectors.toSet());
    }

    /** The ascending combinations of {@code size} folds, in lexicographic order. */
    private static List<int[]> combinations(int size) {
        List<int[]> out = new ArrayList<>();
        combine(new int[size], 0, 0, out);
        return out;
    }

    private static void combine(int[] chosen, int filled, int start, List<int[]> out) {
        if (filled == chosen.length) {
            out.add(chosen.clone());
            return;
        }
        for (int fold = start; fold < FOLDS; fold++) {
            chosen[filled] = fold;
            combine(chosen, filled + 1, fold + 1, out);
        }
    }

    private static <M> ObjectNode outOfFold(List<TrainRows.Kept> kept, Map<String, M> pairs, Predict<M> predict) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode folds = result.putObject("folds");
        double pooledSum = 0;
        int pooledRows = 0;
        for (int k = 0; k < FOLDS; k++) {
            double sum = 0;
            int count = 0;
            for (TrainRows.Kept row : kept) {
                if (row.fold() == k && row.coarse()) {
                    sum += loss(prediction(pairs, k, row, predict), row.label());
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

    private static <M> double prediction(Map<String, M> pairs, int k, TrainRows.Kept row, Predict<M> predict) {
        double sum = 0;
        for (int j = 0; j < FOLDS; j++) {
            if (j != k) {
                sum += predict.of(pairs.get(Math.min(j, k) + "," + Math.max(j, k)), row);
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
}
