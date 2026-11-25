package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Scaling stored cues: rounding to the millisecond, order, end times and text. */
class CueScalerTest {

    private static String srt(SubtitleCue... cues) {
        return SrtParser.format(List.of(cues));
    }

    @Test
    void aFactorOfOneReturnsTheTextUnchanged() {
        String text = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

        assertThat(CueScaler.scale(text, 1.0)).isSameAs(text);
    }

    @Test
    void everyStartAndEndIsMultipliedAndTheTextIsKept() {
        String scaled = CueScaler.scale(srt(new SubtitleCue(10_000, 12_000, "Hello\nthere"),
            new SubtitleCue(3_600_000, 3_605_000, "Later")), 0.5);

        assertThat(SrtParser.cues(scaled)).containsExactly(new SubtitleCue(5_000, 6_000, "Hello\nthere"),
            new SubtitleCue(1_800_000, 1_802_500, "Later"));
    }

    @Test
    void timesAreRoundedToTheNearestMillisecond() {
        String scaled = CueScaler.scale(srt(new SubtitleCue(1_001, 1_003, "a")), 0.5);

        assertThat(SrtParser.cues(scaled)).containsExactly(new SubtitleCue(501, 502, "a"));
    }

    @Test
    void theOrderOfTheCuesIsKeptAndEveryEndStaysAfterItsStart() {
        double k = 23.976 / 25;
        String scaled = CueScaler.scale(srt(new SubtitleCue(1_000, 1_001, "one"), new SubtitleCue(1_002, 1_900, "two"),
            new SubtitleCue(5_400_000, 5_400_900, "three")), k);

        List<SubtitleCue> cues = SrtParser.cues(scaled);
        assertThat(cues).extracting(SubtitleCue::text).containsExactly("one", "two", "three");
        assertThat(cues).extracting(SubtitleCue::startMs).isSorted();
        assertThat(cues).extracting(SubtitleCue::startMs).containsExactly(959L, 961L, 5_178_816L);
        assertThat(cues).extracting(SubtitleCue::endMs).containsExactly(960L, 1_822L, 5_179_679L);
    }

    @Test
    void textWithNoCuesScalesToNothing() {
        assertThat(CueScaler.scale("", 0.5)).isEmpty();
    }
}
