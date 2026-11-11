package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VttWriterTest {

    @Test
    void writesAHeaderAndOneBlockPerCueWithDotMilliseconds() {
        String vtt = VttWriter.write(List.of(new SubtitleCue(36 * 60_000 + 36_778, 36 * 60_000 + 40_239, "A\nB"),
            new SubtitleCue(3_723_004, 3_724_000, "C")), 0);

        assertThat(vtt).isEqualTo(
            "WEBVTT\n\n00:36:36.778 --> 00:36:40.239\nA\nB\n\n01:02:03.004 --> 01:02:04.000\nC\n");
    }

    @Test
    void noCuesIsJustTheHeader() {
        assertThat(VttWriter.write(List.of(), 5000)).isEqualTo("WEBVTT\n");
    }

    @Test
    void ampersandsAndAngleBracketsInTextAreEscapedSoNothingBecomesMarkup() {
        String vtt = VttWriter.write(List.of(new SubtitleCue(0, 1000, "<script>x</script> & 5 > 3 --> 4")), 0);

        assertThat(vtt).contains("&lt;script&gt;x&lt;/script&gt; &amp; 5 &gt; 3 --&gt; 4");
        assertThat(vtt).doesNotContain("<script>");
    }

    @Test
    void aPositiveOffsetMovesEveryTimeLater() {
        String vtt = VttWriter.write(List.of(new SubtitleCue(1000, 2000, "A")), 250);

        assertThat(vtt).contains("00:00:01.250 --> 00:00:02.250");
    }

    @Test
    void aNegativeOffsetMovesEveryTimeEarlierAndAStartBeforeZeroStartsAtZero() {
        String vtt = VttWriter.write(List.of(new SubtitleCue(1000, 3000, "A")), -2000);

        assertThat(vtt).contains("00:00:00.000 --> 00:00:01.000");
    }

    @Test
    void aCueThatIsOverByZeroAfterTheOffsetIsDroppedAndOneThatEndsAMillisecondLaterStays() {
        List<SubtitleCue> cues = List.of(new SubtitleCue(1000, 2000, "Gone"), new SubtitleCue(1000, 2001, "Stays"));

        String vtt = VttWriter.write(cues, -2000);

        assertThat(vtt).doesNotContain("Gone").contains("Stays").contains("00:00:00.000 --> 00:00:00.001");
    }

    @Test
    void aCueWithNoDurationIsShownForOneSecond() {
        assertThat(VttWriter.write(List.of(new SubtitleCue(5000, 5000, "A")), 0))
            .contains("00:00:05.000 --> 00:00:06.000");
    }

    @Test
    void aZeroLengthCueAtZeroAfterTheOffsetIsKeptButOneBeforeZeroIsDropped() {
        List<SubtitleCue> cues = List.of(new SubtitleCue(500, 500, "Edge"), new SubtitleCue(400, 400, "Before"));

        String vtt = VttWriter.write(cues, -500);

        assertThat(vtt).contains("Edge").contains("00:00:00.000 --> 00:00:01.000").doesNotContain("Before");
    }
}
