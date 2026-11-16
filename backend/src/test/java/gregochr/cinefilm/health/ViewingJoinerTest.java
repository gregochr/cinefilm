package gregochr.cinefilm.health;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.health.ViewingJoiner.Part;
import gregochr.cinefilm.health.ViewingJoiner.Viewing;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The four rules that make several playbacks one viewing, at their exact edges. */
class ViewingJoinerTest {

    private static final Instant T0 = Instant.parse("2024-03-31T10:00:00Z");
    private static final String MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130";
    private static final String IPAD = "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X)";
    private static final String OPERATOR = "AS398830 Icomera US, Inc.";
    private static final long SIX_HOURS_MS = Duration.ofHours(6).toMillis();

    /** A part on film 1 on the Mac, on the train, that ran from {@code start} to {@code end}, without positions. */
    private static Part part(String id, Instant start, Instant end) {
        return new Part(id, 1L, "The Rock", start, end, 100_000, 0, 0, 900L, false, "train", OPERATOR, MAC, null,
            null, null, Map.of());
    }

    private static Part with(Part p, long filmId, String environment, String operator, String userAgent) {
        return new Part(p.playbackId(), filmId, p.filmTitle(), p.startedAt(), p.updatedAt(), p.watchedMs(),
            p.stallCount(), p.stallMs(), p.startupMs(), p.hasProblem(), environment, operator, userAgent, p.touch(),
            p.startPositionMs(), p.endPositionMs(), p.rungMs());
    }

    private static Part at(Part p, Long startPosition, Long endPosition) {
        return new Part(p.playbackId(), p.filmId(), p.filmTitle(), p.startedAt(), p.updatedAt(), p.watchedMs(),
            p.stallCount(), p.stallMs(), p.startupMs(), p.hasProblem(), p.environment(), p.operator(),
            p.userAgent(), p.touch(), startPosition, endPosition, p.rungMs());
    }

    private static List<List<String>> ids(List<Viewing> viewings) {
        return viewings.stream().map(v -> v.parts().stream().map(Part::playbackId).toList()).toList();
    }

    private static Part first() {
        return part("a", T0, T0.plusSeconds(600));
    }

    private static Part after(Duration gap) {
        return part("b", first().updatedAt().plus(gap), first().updatedAt().plus(gap).plusSeconds(600));
    }

    @Test
    void noRowsGiveNoViewings() {
        assertThat(ViewingJoiner.join(List.of())).isEmpty();
    }

    @Test
    void aSingleRowIsAOnePartViewing() {
        List<Viewing> viewings = ViewingJoiner.join(List.of(first()));

        assertThat(ids(viewings)).containsExactly(List.of("a"));
        assertThat(viewings.get(0).first().playbackId()).isEqualTo("a");
        assertThat(viewings.get(0).last().playbackId()).isEqualTo("a");
    }

    // rule 3: the gap

    @Test
    void aGapOfExactlySixHoursJoins() {
        assertThat(ids(ViewingJoiner.join(List.of(first(), after(Duration.ofHours(6))))))
            .containsExactly(List.of("a", "b"));
    }

    @Test
    void aGapOfSixHoursAndOneMillisecondDoesNotJoin() {
        assertThat(ids(ViewingJoiner.join(List.of(first(), after(Duration.ofHours(6).plusMillis(1))))))
            .containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void aGapOfZeroJoins() {
        assertThat(ids(ViewingJoiner.join(List.of(first(), after(Duration.ZERO)))))
            .containsExactly(List.of("a", "b"));
    }

    @Test
    void aNegativeGapDoesNotJoin() {
        assertThat(ids(ViewingJoiner.join(List.of(first(), after(Duration.ofMillis(-1))))))
            .containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void theSixHourLimitIsSixHoursInMilliseconds() {
        assertThat(ViewingJoiner.MAX_GAP.toMillis()).isEqualTo(SIX_HOURS_MS);
    }

    // rule 4: positions

    @Test
    void positionsExactlyTwoMinutesApartJoinEvenOnAnotherNetwork() {
        Part a = at(first(), 0L, 1_000_000L);
        Part b = at(with(after(Duration.ofSeconds(6)), 1, "home", "AS5089 Virgin Media Limited", MAC), 1_120_000L,
            2_000_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a", "b"));
    }

    @Test
    void positionsExactlyTwoMinutesApartJoinWhenTheNewPartStartsEarlier() {
        Part a = at(first(), 0L, 1_000_000L);
        Part b = at(after(Duration.ofSeconds(6)), 880_000L, 2_000_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a", "b"));
    }

    @Test
    void positionsTwoMinutesAndOneMillisecondApartDoNotJoin() {
        Part a = at(first(), 0L, 1_000_000L);
        Part ahead = at(after(Duration.ofSeconds(6)), 1_120_001L, 2_000_000L);
        Part behind = at(after(Duration.ofSeconds(6)), 879_999L, 2_000_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, ahead)))).containsExactly(List.of("a"), List.of("b"));
        assertThat(ids(ViewingJoiner.join(List.of(a, behind)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void aRewatchFromTheStartDoesNotJoinWhenPositionsAreKnown() {
        Part a = at(first(), 0L, 6_000_000L);
        Part b = at(after(Duration.ofMinutes(30)), 0L, 6_000_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void positionsOnTheOldPartsEndOnlyFallBackToTheNetworkRule() {
        Part a = at(first(), 0L, 1_000_000L);
        Part sameNetwork = at(after(Duration.ofSeconds(6)), null, null);
        Part otherNetwork = at(with(after(Duration.ofSeconds(6)), 1, "home", OPERATOR, MAC), null, null);

        assertThat(ids(ViewingJoiner.join(List.of(a, sameNetwork)))).containsExactly(List.of("a", "b"));
        assertThat(ids(ViewingJoiner.join(List.of(a, otherNetwork)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void positionsOnTheNewPartOnlyFallBackToTheNetworkRule() {
        Part a = at(first(), null, null);
        Part sameNetwork = at(after(Duration.ofSeconds(6)), 5_000_000L, 6_000_000L);
        Part otherNetwork = at(with(after(Duration.ofSeconds(6)), 1, "train", "AS5089 Virgin Media Limited", MAC),
            5_000_000L, 6_000_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, sameNetwork)))).containsExactly(List.of("a", "b"));
        assertThat(ids(ViewingJoiner.join(List.of(a, otherNetwork)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void aMissingEndPositionWithAStartPositionUsesTheNetworkRule() {
        Part a = at(first(), 0L, null);
        Part b = at(after(Duration.ofSeconds(6)), 5_000_000L, 6_000_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a", "b"));
    }

    @Test
    void withoutPositionsADifferentEnvironmentDoesNotJoin() {
        Part b = with(after(Duration.ofSeconds(6)), 1, "home", OPERATOR, MAC);

        assertThat(ids(ViewingJoiner.join(List.of(first(), b)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void withoutPositionsADifferentOperatorDoesNotJoin() {
        Part b = with(after(Duration.ofSeconds(6)), 1, "train", "AS5089 Virgin Media Limited", MAC);

        assertThat(ids(ViewingJoiner.join(List.of(first(), b)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void withoutPositionsTwoMissingOperatorsAreTheSameNetwork() {
        Part a = with(first(), 1, "train", null, MAC);
        Part b = with(after(Duration.ofSeconds(6)), 1, "train", null, MAC);

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a", "b"));
    }

    @Test
    void withoutPositionsAMissingOperatorAgainstANamedOneIsAnotherNetwork() {
        Part b = with(after(Duration.ofSeconds(6)), 1, "train", null, MAC);

        assertThat(ids(ViewingJoiner.join(List.of(first(), b)))).containsExactly(List.of("a"), List.of("b"));
    }

    // rules 1 and 2: film and device

    @Test
    void aDifferentFilmDoesNotJoin() {
        Part b = with(after(Duration.ofSeconds(6)), 2, "train", OPERATOR, MAC);

        assertThat(ids(ViewingJoiner.join(List.of(first(), b)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void aDifferentUserAgentDoesNotJoin() {
        Part b = with(after(Duration.ofSeconds(6)), 1, "train", OPERATOR, IPAD);

        assertThat(ids(ViewingJoiner.join(List.of(first(), b)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void twoEmptyUserAgentsDoNotJoin() {
        Part a = with(first(), 1, "train", OPERATOR, "");
        Part b = with(after(Duration.ofSeconds(6)), 1, "train", OPERATOR, "");

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void twoMissingUserAgentsDoNotJoin() {
        Part a = with(first(), 1, "train", OPERATOR, null);
        Part b = with(after(Duration.ofSeconds(6)), 1, "train", OPERATOR, null);

        assertThat(ids(ViewingJoiner.join(List.of(a, b)))).containsExactly(List.of("a"), List.of("b"));
    }

    // chains

    @Test
    void threePartsInAChainAreOneViewingInOrder() {
        Part a = at(first(), 0L, 1_000_000L);
        Part b = at(part("b", T0.plusSeconds(606), T0.plusSeconds(1_200)), 1_000_000L, 2_000_000L);
        Part c = at(part("c", T0.plusSeconds(1_206), T0.plusSeconds(1_800)), 2_050_000L, 3_000_000L);

        List<Viewing> viewings = ViewingJoiner.join(List.of(a, b, c));

        assertThat(ids(viewings)).containsExactly(List.of("a", "b", "c"));
        assertThat(viewings.get(0).watchedMs()).isEqualTo(300_000);
        assertThat(viewings.get(0).last().updatedAt()).isEqualTo(T0.plusSeconds(1_800));
    }

    @Test
    void aShortPartBetweenTwoLongOnesStillJoinsThem() {
        Part a = new Part("a", 1L, "The Rock", T0, T0.plusSeconds(600), 1_800_000, 0, 0, 900L, false, "train",
            OPERATOR, MAC, null, null, null, Map.of());
        Part b = new Part("b", 1L, "The Rock", T0.plusSeconds(606), T0.plusSeconds(726), 60_000, 0, 0, 900L, false,
            "train", OPERATOR, MAC, null, null, null, Map.of());
        Part c = new Part("c", 1L, "The Rock", T0.plusSeconds(732), T0.plusSeconds(2_000), 1_800_000, 0, 0, 900L,
            false, "train", OPERATOR, MAC, null, null, null, Map.of());

        List<Viewing> viewings = ViewingJoiner.join(List.of(a, b, c));

        assertThat(ids(viewings)).containsExactly(List.of("a", "b", "c"));
        assertThat(viewings.get(0).watchedMs()).isEqualTo(3_660_000);
    }

    @Test
    void aPartIsTestedAgainstTheLastPartNotTheFirst() {
        // c is 7 hours after a but only 3 hours after b, which joined a: still one viewing.
        Part a = part("a", T0, T0.plusSeconds(600));
        Part b = part("b", T0.plus(Duration.ofHours(3)), T0.plus(Duration.ofHours(4)));
        Part c = part("c", T0.plus(Duration.ofHours(7)), T0.plus(Duration.ofHours(8)));

        assertThat(ids(ViewingJoiner.join(List.of(a, b, c)))).containsExactly(List.of("a", "b", "c"));
    }

    @Test
    void aRowThatFailsStartsANewViewingAndTheNextOneJoinsThatNotTheOldOne() {
        // b is a rewatch from the start (positions); c picks up where b stopped and is also near a's end.
        Part a = at(first(), 0L, 1_000_000L);
        Part b = at(part("b", T0.plusSeconds(700), T0.plusSeconds(1_300)), 0L, 500_000L);
        Part c = at(part("c", T0.plusSeconds(1_306), T0.plusSeconds(1_900)), 500_000L, 900_000L);

        assertThat(ids(ViewingJoiner.join(List.of(a, b, c)))).containsExactly(List.of("a"), List.of("b", "c"));
    }

    @Test
    void otherFilmsAndDevicesInBetweenDoNotBreakAChain() {
        Part a = first();
        Part other = with(part("x", T0.plusSeconds(100), T0.plusSeconds(400)), 2, "train", OPERATOR, MAC);
        Part ipad = with(part("y", T0.plusSeconds(200), T0.plusSeconds(500)), 1, "train", OPERATOR, IPAD);
        Part b = after(Duration.ofSeconds(6));

        assertThat(ids(ViewingJoiner.join(List.of(a, other, ipad, b))))
            .containsExactly(List.of("a", "b"), List.of("x"), List.of("y"));
    }

    // the 12 November pair

    @Test
    void theTwelfthOfNovemberPairIsOneViewingOf138Minutes() {
        Part rockA = new Part("fb22ac5e-0000-4000-8000-000000000001", 1L, "The Rock",
            Instant.parse("2026-11-12T17:23:30.519Z"), Instant.parse("2026-11-12T17:56:47.761Z"), 32 * 60_000L, 4,
            20_000, 6_300L, true, "train", OPERATOR, MAC, null, null, null, Map.of());
        Part rockB = new Part("323111c2-0000-4000-8000-000000000002", 1L, "The Rock",
            Instant.parse("2026-11-12T17:56:53.956Z"), Instant.parse("2026-11-12T20:33:53.080Z"), 106 * 60_000L, 155,
            2_011_000, 6_300L, true, "train", OPERATOR, MAC, null, null, null, Map.of());

        List<Viewing> viewings = ViewingJoiner.join(List.of(rockA, rockB));

        assertThat(viewings).hasSize(1);
        assertThat(viewings.get(0).parts()).extracting(Part::playbackId)
            .containsExactly("fb22ac5e-0000-4000-8000-000000000001", "323111c2-0000-4000-8000-000000000002");
        assertThat(viewings.get(0).watchedMs()).isEqualTo(138 * 60_000L);
        assertThat(viewings.get(0).hasProblem()).isTrue();
    }

    @Test
    void aViewingHasAProblemOnlyWhenAPartDoes() {
        Part smooth = part("b", T0.plusSeconds(606), T0.plusSeconds(1_200));

        assertThat(ViewingJoiner.join(List.of(first(), smooth)).get(0).hasProblem()).isFalse();
    }
}
