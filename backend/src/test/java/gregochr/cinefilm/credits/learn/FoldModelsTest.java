package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class FoldModelsTest {

    /** A model that only remembers which folds it was fitted without. */
    private record Fake(Set<Integer> held) {
    }

    private final List<Set<Integer>> fitted = new ArrayList<>();
    /** The models that predicted each row, by the row's index. */
    private final Map<Integer, List<Set<Integer>>> predictedBy = new HashMap<>();

    private Fake fit(Set<Integer> held) {
        fitted.add(new TreeSet<>(held));
        return new Fake(new TreeSet<>(held));
    }

    private double predict(Fake model, TrainRows.Kept row) {
        predictedBy.computeIfAbsent(row.index(), key -> new ArrayList<>()).add(model.held());
        // A pair model {a, b} says 0.1 + 0.1a + 0.02b.
        List<Integer> folds = List.copyOf(model.held());
        return 0.1 + 0.1 * folds.get(0) + 0.02 * folds.get(1);
    }

    private static TrainRows.Kept row(int index, int fold, boolean coarse, int label) {
        return new TrainRows.Kept(index, fold, coarse, label, new double[0]);
    }

    /** Fold 0 holds a credits and a film row and one more credits row; the others a credits and a film row. */
    private static List<TrainRows.Kept> kept() {
        List<TrainRows.Kept> kept = new ArrayList<>();
        int index = 0;
        for (int k = 0; k < FoldModels.FOLDS; k++) {
            kept.add(row(index++, k, true, 1));
            kept.add(row(index++, k, true, 0));
            if (k == 0) {
                kept.add(row(index++, k, true, 1));
            }
            kept.add(row(index++, k, false, 1));
        }
        return kept;
    }

    @Test
    void theModelsAreFittedShippingThenPairsThenTriplesInTheExistingOrder() {
        FoldModels<Fake> models = FoldModels.build(kept(), this::fit, this::predict);

        assertThat(fitted).containsExactly(
            Set.of(0), Set.of(1), Set.of(2), Set.of(3), Set.of(4),
            Set.of(0, 1), Set.of(0, 2), Set.of(0, 3), Set.of(0, 4), Set.of(1, 2), Set.of(1, 3), Set.of(1, 4),
            Set.of(2, 3), Set.of(2, 4), Set.of(3, 4),
            Set.of(0, 1, 2), Set.of(0, 1, 3), Set.of(0, 1, 4), Set.of(0, 2, 3), Set.of(0, 2, 4), Set.of(0, 3, 4),
            Set.of(1, 2, 3), Set.of(1, 2, 4), Set.of(1, 3, 4), Set.of(2, 3, 4));
        assertThat(models.shipping()).extracting(Fake::held).containsExactly(
            Set.of(0), Set.of(1), Set.of(2), Set.of(3), Set.of(4));
        assertThat(models.pairs().keySet()).containsExactly("0,1", "0,2", "0,3", "0,4", "1,2", "1,3", "1,4", "2,3",
            "2,4", "3,4");
        assertThat(models.triples().keySet()).containsExactly("0,1,2", "0,1,3", "0,1,4", "0,2,3", "0,2,4", "0,3,4",
            "1,2,3", "1,2,4", "1,3,4", "2,3,4");
    }

    @Test
    void eachKeyHoldsTheModelFittedWithoutThoseFolds() {
        FoldModels<Fake> models = FoldModels.build(kept(), this::fit, this::predict);

        models.pairs().forEach((key, model) -> assertThat(key)
            .isEqualTo(String.join(",", model.held().stream().map(String::valueOf).toList())));
        models.triples().forEach((key, model) -> assertThat(key)
            .isEqualTo(String.join(",", model.held().stream().map(String::valueOf).toList())));
        assertThat(models.pairs().get("1,3").held()).containsExactly(1, 3);
        assertThat(models.triples().get("0,2,4").held()).containsExactly(0, 2, 4);
    }

    @Test
    void aRowIsPredictedOnlyByTheFourPairModelsHoldingItsFoldAndDenseRowsNotAtAll() {
        FoldModels.build(kept(), this::fit, this::predict);

        // Fold 0: indices 0, 1, 2 coarse and 3 dense; fold 1: 4, 5 coarse and 6 dense.
        assertThat(predictedBy.get(0)).containsExactly(Set.of(0, 1), Set.of(0, 2), Set.of(0, 3), Set.of(0, 4));
        assertThat(predictedBy.get(5)).containsExactly(Set.of(0, 1), Set.of(1, 2), Set.of(1, 3), Set.of(1, 4));
        assertThat(predictedBy.get(3)).isNull();
        assertThat(predictedBy.get(6)).isNull();
        assertThat(predictedBy).hasSize(11);
    }

    @Test
    void theLogLossIsPerFoldAndPooledOverTheCoarseRows() {
        FoldModels<Fake> models = FoldModels.build(kept(), this::fit, this::predict);

        // The mean of the four pairs holding fold k: 0.15, 0.225, 0.28, 0.315, 0.33.
        double[] meanP = {0.15, 0.225, 0.28, 0.315, 0.33};
        double[] rowsPerFold = {3, 2, 2, 2, 2};
        double pooled = 0;
        for (int k = 0; k < FoldModels.FOLDS; k++) {
            double sum = -Math.log(meanP[k]) - Math.log(1 - meanP[k]) + (k == 0 ? -Math.log(meanP[k]) : 0);
            var fold = models.logLoss().get("folds").get(Integer.toString(k));
            assertThat(fold.get("rows").intValue()).isEqualTo((int) rowsPerFold[k]);
            assertThat(fold.get("logLoss").doubleValue()).as("fold %d", k)
                .isCloseTo(sum / rowsPerFold[k], within(1e-12));
            pooled += sum;
        }
        assertThat(models.logLoss().get("folds").get("0").get("logLoss").doubleValue())
            .isCloseTo((-2 * Math.log(0.15) - Math.log(0.85)) / 3, within(1e-12))
            .isCloseTo(1.31892, within(1e-5));
        assertThat(models.logLoss().get("folds").get("2").get("logLoss").doubleValue())
            .isCloseTo(0.8007348714, within(1e-9));
        assertThat(models.logLoss().get("pooled").get("rows").intValue()).isEqualTo(11);
        assertThat(models.logLoss().get("pooled").get("logLoss").doubleValue()).isCloseTo(pooled / 11, within(1e-12));
        assertThat(models.logLoss().propertyNames()).containsExactly("folds", "pooled");
        assertThat(models.logLoss().get("folds").propertyNames()).containsExactly("0", "1", "2", "3", "4");
        assertThat(models.logLoss().get("pooled").propertyNames()).containsExactly("rows", "logLoss");
    }

    @Test
    void theLossIsMinusTheLogOfTheProbabilityOfTheLabelClampedToOneE15() {
        assertThat(FoldModels.loss(0.8, 1)).isCloseTo(0.2231435513142097, within(1e-15));
        assertThat(FoldModels.loss(0.8, 0)).isCloseTo(1.6094379124341003, within(1e-15));
        assertThat(FoldModels.loss(0, 1)).isCloseTo(-Math.log(1e-15), within(1e-12));
        assertThat(FoldModels.loss(2, 0)).isCloseTo(-Math.log(1 - (1 - 1e-15)), within(1e-12));
    }

    @Test
    void aFoldWhoseOnlyRowIsDenseIsRefusedBeforeAnyFit() {
        List<TrainRows.Kept> kept = new ArrayList<>(kept());
        kept.removeIf(row -> row.fold() == 4 && row.coarse());

        assertThatThrownBy(() -> FoldModels.build(kept, this::fit, this::predict))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("fold 4 has no coarse row left to train on");
        assertThat(fitted).isEmpty();
    }

    @Test
    void aFoldWithNoRowsAtAllIsRefusedBeforeAnyFit() {
        List<TrainRows.Kept> kept = new ArrayList<>(kept());
        kept.removeIf(row -> row.fold() == 2);

        assertThatThrownBy(() -> FoldModels.build(kept, this::fit, this::predict))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("fold 2 has no coarse row left to train on");
        assertThat(fitted).isEmpty();
    }
}
