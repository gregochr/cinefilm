package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StillsPickerTest {

    private static List<Long> range(long from, long to, long step) {
        List<Long> offsets = new ArrayList<>();
        for (long offset = from; offset <= to; offset += step) {
            offsets.add(offset);
        }
        return offsets;
    }

    private static List<Long> offsets(List<StillsPicker.Pick> picks) {
        return picks.stream().map(StillsPicker.Pick::offsetMs).toList();
    }

    @Test
    void theNearestFramesAreChosenInTheFixedOrder() {
        List<StillsPicker.Pick> picks = StillsPicker.pick(range(70_000, 130_000, 500), 100_000);

        assertThat(picks).extracting(StillsPicker.Pick::seconds).containsExactly(-30, -20, -10, -5, 0, 5, 10, 20, 30);
        assertThat(offsets(picks)).containsExactly(70_000L, 80_000L, 90_000L, 95_000L, 100_000L, 105_000L, 110_000L,
            120_000L, 130_000L);
        assertThat(picks).extracting(StillsPicker.Pick::fromTargetMs).containsExactly(-30_000L, -20_000L, -10_000L,
            -5_000L, 0L, 5_000L, 10_000L, 20_000L, 30_000L);
    }

    @Test
    void aFrameNotOnTheSecondGoesToTheNearerSide() {
        // Wanted 95 s: 94.6 s is 400 ms away, 95.5 s is 500 ms away.
        List<StillsPicker.Pick> picks = StillsPicker.pick(List.of(94_600L, 95_500L), 100_000);

        assertThat(picks.get(3).offsetMs()).isEqualTo(94_600L);
        assertThat(picks.get(3).fromTargetMs()).isEqualTo(-5_400L);
    }

    @Test
    void aTieGoesToTheEarlierFrameWhateverTheOrderOfTheList() {
        // Wanted 95 s: 94 s and 96 s are both 1 s away.
        assertThat(StillsPicker.pick(List.of(94_000L, 96_000L), 100_000).get(3).offsetMs()).isEqualTo(94_000L);
        assertThat(StillsPicker.pick(List.of(96_000L, 94_000L), 100_000).get(3).offsetMs()).isEqualTo(94_000L);
    }

    @Test
    void aTargetTenSecondsIntoTheFilmClampsTheEarlyPicksToTheFirstFrame() {
        // The dense range starts at 0 (not at -20 s), so the wanted -30 s and -20 s have no frame of their own.
        List<StillsPicker.Pick> picks = StillsPicker.pick(range(0, 40_000, 1_000), 10_000);

        assertThat(offsets(picks)).containsExactly(0L, 0L, 0L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 40_000L);
        assertThat(picks.getFirst().fromTargetMs()).isEqualTo(-10_000L);
    }

    @Test
    void noDenseFramesGiveNoPicks() {
        assertThat(StillsPicker.pick(List.of(), 100_000)).isEmpty();
    }

    @Test
    void aSingleFrameIsPickedForEveryTime() {
        assertThat(offsets(StillsPicker.pick(List.of(7L), 100_000))).containsOnly(7L).hasSize(9);
    }

    @Test
    void theBaseNameCarriesTheSignAndTheSeconds() {
        assertThat(new StillsPicker.Pick(-30, 0, 0).baseName()).isEqualTo("d-30s");
        assertThat(new StillsPicker.Pick(-5, 0, 0).baseName()).isEqualTo("d-5s");
        assertThat(new StillsPicker.Pick(0, 0, 0).baseName()).isEqualTo("d+0s");
        assertThat(new StillsPicker.Pick(30, 0, 0).baseName()).isEqualTo("d+30s");
    }
}
