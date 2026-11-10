package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Joining the subtitle files of a split film's parts: the cues of each later part move by that part's start. */
class VttShifterTest {

    private static final String PART_1 = "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nHello\n\n2\n00:01:00.000 --> "
        + "00:01:01.000\nAgain\n";

    @Test
    void theFirstPartIsKeptAsItIsAndALaterPartsCuesAreShiftedByItsStart() {
        String part2 = "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nPart two\n";

        assertThat(VttShifter.join(List.of(PART_1, part2), List.of(0L, 6_353_420L))).isEqualTo(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nHello\n\n2\n00:01:00.000 --> 00:01:01.000\nAgain\n\n"
                + "01:45:54.420 --> 01:45:55.920\nPart two\n");
    }

    @Test
    void timesWithoutHoursAreReadAndWrittenWithHours() {
        String part2 = "WEBVTT\n\n01:02.500 --> 01:03.000\nShort form\n";

        assertThat(VttShifter.join(List.of("WEBVTT\n", part2), List.of(0L, 3_600_000L))).isEqualTo(
            "WEBVTT\n\n01:01:02.500 --> 01:01:03.000\nShort form\n");
    }

    @Test
    void timesWithHoursBeyondTwoDigitsAndMillisecondsCarryCorrectly() {
        String part2 = "WEBVTT\n\n99:59:59.999 --> 100:00:00.000\nLong\n";

        assertThat(VttShifter.join(List.of("WEBVTT\n", part2), List.of(0L, 1L))).isEqualTo(
            "WEBVTT\n\n100:00:00.000 --> 100:00:00.001\nLong\n");
    }

    @Test
    void cueSettingsAfterTheEndTimeAreKept() {
        String part2 = "WEBVTT\n\n00:00:05.000 --> 00:00:07.000 align:start position:10% line:90%\nSettings\n";

        assertThat(VttShifter.join(List.of("WEBVTT\n", part2), List.of(0L, 10_000L))).isEqualTo(
            "WEBVTT\n\n00:00:15.000 --> 00:00:17.000 align:start position:10% line:90%\nSettings\n");
    }

    @Test
    void aMultiLineCueKeepsAllItsTextLines() {
        String part2 = "WEBVTT\n\n00:00:05.000 --> 00:00:07.000\nLine one\nLine two\n- Line three\n";

        assertThat(VttShifter.join(List.of("WEBVTT\n", part2), List.of(0L, 1_000L))).isEqualTo(
            "WEBVTT\n\n00:00:06.000 --> 00:00:08.000\nLine one\nLine two\n- Line three\n");
    }

    @Test
    void aLaterPartsHeaderNotesStylesAndCueIdentifiersAreDropped() {
        String part2 = "WEBVTT - second\n\nNOTE written by ffmpeg\n\nSTYLE\n::cue { color: red }\n\n"
            + "intro\n00:00:00.500 --> 00:00:01.500\nFirst\n\nREGION\nid:r1\n\n"
            + "7\n00:00:02.000 --> 00:00:03.000\nSecond\n";

        assertThat(VttShifter.join(List.of(PART_1, part2), List.of(0L, 60_000L))).isEqualTo(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500\nHello\n\n2\n00:01:00.000 --> 00:01:01.000\nAgain\n\n"
                + "00:01:00.500 --> 00:01:01.500\nFirst\n\n00:01:02.000 --> 00:01:03.000\nSecond\n");
    }

    @Test
    void windowsLineEndingsAreAccepted() {
        String part2 = "WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nCRLF\r\n";

        assertThat(VttShifter.join(List.of("WEBVTT\r\n\r\n00:00:00.000 --> 00:00:01.000\r\nA\r\n", part2),
            List.of(0L, 2_000L))).isEqualTo(
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nA\n\n00:00:03.000 --> 00:00:04.000\nCRLF\n");
    }

    @Test
    void threePartsAreEachShiftedByTheirOwnStart() {
        String second = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nTwo\n";
        String third = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nThree\n";

        assertThat(VttShifter.join(List.of("WEBVTT\n", second, third), List.of(0L, 10_000L, 25_000L))).isEqualTo(
            "WEBVTT\n\n00:00:11.000 --> 00:00:12.000\nTwo\n\n00:00:26.000 --> 00:00:27.000\nThree\n");
    }

    @Test
    void aLaterPartWithNoCuesAddsNothing() {
        assertThat(VttShifter.join(List.of(PART_1, "WEBVTT\n\n"), List.of(0L, 5_000L))).isEqualTo(PART_1);
    }

    @Test
    void aTimingLineIsOnlyRecognisedWhenBothTimesAreWellFormed() {
        String part2 = "WEBVTT\n\n00:00:01.000 --> later\nNot a cue\n";

        assertThat(VttShifter.join(List.of("WEBVTT\n", part2), List.of(0L, 1_000L))).isEqualTo("WEBVTT\n");
    }
}
