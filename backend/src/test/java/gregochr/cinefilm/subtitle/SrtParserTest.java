package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SrtParser.Parsed;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SrtParserTest {

    private static final String BASIC = "1\n00:36:36,778 --> 00:36:40,239\nHe is behind you.\n\n"
        + "2\n00:36:41,000 --> 00:36:43,500\nLine one\nLine two\n";

    private static Parsed parse(String text) {
        return SrtParser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] withBom(byte[] bom, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bom);
        out.writeBytes(content);
        return out.toByteArray();
    }

    @Test
    void readsNumberedCuesWithMultiLineText() {
        Parsed parsed = parse(BASIC);

        assertThat(parsed.cues()).containsExactly(
            new SubtitleCue(36 * 60_000 + 36_778, 36 * 60_000 + 40_239, "He is behind you."),
            new SubtitleCue(36 * 60_000 + 41_000, 36 * 60_000 + 43_500, "Line one\nLine two"));
        assertThat(parsed.report().encoding()).isEqualTo("UTF-8");
        assertThat(parsed.report().describe()).isEqualTo("No problems found");
        assertThat(parsed.report().cues()).isEqualTo(2);
    }

    @Test
    void aUtf8ByteOrderMarkIsDroppedAndTheTextReadAsUtf8() {
        byte[] bytes = withBom(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            "1\n00:00:01,000 --> 00:00:02,000\nCafé — “quoted”\n".getBytes(StandardCharsets.UTF_8));

        Parsed parsed = SrtParser.parse(bytes);

        assertThat(parsed.cues()).containsExactly(new SubtitleCue(1000, 2000, "Café — “quoted”"));
        assertThat(parsed.report().encoding()).isEqualTo("UTF-8");
    }

    @Test
    void utf16WithAByteOrderMarkInEitherByteOrderIsRead() {
        String text = "1\r\n00:00:01,000 --> 00:00:02,000\r\nНаш друг\r\n";
        byte[] little = withBom(new byte[] {(byte) 0xFF, (byte) 0xFE}, text.getBytes(StandardCharsets.UTF_16LE));
        byte[] big = withBom(new byte[] {(byte) 0xFE, (byte) 0xFF}, text.getBytes(StandardCharsets.UTF_16BE));

        for (byte[] bytes : List.of(little, big)) {
            Parsed parsed = SrtParser.parse(bytes);
            assertThat(parsed.cues()).containsExactly(new SubtitleCue(1000, 2000, "Наш друг"));
            assertThat(parsed.report().encoding()).isEqualTo("UTF-16");
            assertThat(parsed.report().describe()).isEqualTo("read as UTF-16");
        }
    }

    @Test
    void textThatIsNotValidUtf8IsReadAsWindows1252() {
        byte[] bytes = "1\n00:00:01,000 --> 00:00:02,000\nCafé – déjà vu “quoted”\n"
            .getBytes(Charset.forName("windows-1252"));

        Parsed parsed = SrtParser.parse(bytes);

        assertThat(parsed.cues()).containsExactly(
            new SubtitleCue(1000, 2000, "Café – déjà vu “quoted”"));
        assertThat(parsed.report().encoding()).isEqualTo("Windows-1252");
        assertThat(parsed.report().describe()).isEqualTo("read as Windows-1252");
    }

    @Test
    void aSingleByteOrderMarkByteIsNotABom() {
        assertThat(SrtParser.parse(new byte[] {(byte) 0xFF}).report().encoding()).isEqualTo("Windows-1252");
        assertThat(SrtParser.parse(new byte[] {(byte) 0xFE}).report().encoding()).isEqualTo("Windows-1252");
        assertThat(SrtParser.parse(new byte[] {(byte) 0xEF, (byte) 0xBB}).report().encoding())
            .isEqualTo("Windows-1252");
    }

    @Test
    void aFileThatStartsWithPartOfAByteOrderMarkButIsNotOneIsStillText() {
        byte[] almostUtf8 = "\u00ef\u0041".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] almostUtf8Longer = {(byte) 0xEF, (byte) 0xBB, 0x41};
        byte[] almostUtf16 = {(byte) 0xFF, 0x41};
        byte[] almostUtf16Big = {(byte) 0xFE, 0x41};

        for (byte[] bytes : List.of(almostUtf8, almostUtf8Longer, almostUtf16, almostUtf16Big)) {
            assertThat(SrtParser.parse(bytes).report().encoding()).isNotEqualTo("UTF-16");
        }
        assertThat(SrtParser.parse(almostUtf8Longer).report().encoding()).isEqualTo("Windows-1252");
    }

    @Test
    void crlfLfAndLoneCrLineEndingsReadTheSame() {
        List<SubtitleCue> expected = parse(BASIC).cues();

        assertThat(parse(BASIC.replace("\n", "\r\n")).cues()).isEqualTo(expected);
        assertThat(parse(BASIC.replace("\n", "\r")).cues()).isEqualTo(expected);
    }

    @Test
    void aDotBeforeTheMillisecondsAndShortFractionsAreAccepted() {
        Parsed parsed = parse("1\n00:00:01.5 --> 00:00:02.25\nA\n\n2\n0:0:3,007 --> 0:0:4,1\nB\n");

        assertThat(parsed.cues()).containsExactly(new SubtitleCue(1500, 2250, "A"),
            new SubtitleCue(3007, 4100, "B"));
    }

    @Test
    void numbersMayBeMissingOrWrongAndCueSettingsAfterTheTimesAreIgnored() {
        Parsed parsed = parse("00:00:01,000 --> 00:00:02,000\nNo number\n\n"
            + "99\n00:00:03,000 --> 00:00:04,000 X1:100 X2:200\nWrong number\n\n"
            + "junk\n00:00:05,000 --> 00:00:06,000\nA junk line first\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text)
            .containsExactly("No number", "Wrong number", "A junk line first");
        assertThat(parsed.report().skippedBlocks()).isZero();
    }

    @Test
    void cuesOutOfOrderAreSortedAndTheReportSaysSo() {
        Parsed parsed = parse("1\n00:00:09,000 --> 00:00:10,000\nLate\n\n2\n00:00:01,000 --> 00:00:02,000\nEarly\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("Early", "Late");
        assertThat(parsed.report().reordered()).isTrue();
        assertThat(parsed.report().describe()).isEqualTo("cues were out of order and have been sorted");
    }

    @Test
    void cuesInOrderAreNotReportedAsReordered() {
        assertThat(parse(BASIC).report().reordered()).isFalse();
        assertThat(parse("1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:01,000 --> 00:00:03,000\nB\n")
            .report().reordered()).isFalse();
    }

    @Test
    void sortingKeepsTheEarlierEndFirstWhenTwoCuesStartTogether() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:05,000\nLong\n\n2\n00:00:01,000 --> 00:00:02,000\nShort\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("Short", "Long");
    }

    @Test
    void overlappingCuesAreKeptAndCounted() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:05,000\nA\n\n2\n00:00:04,999 --> 00:00:06,000\nB\n\n"
            + "3\n00:00:06,000 --> 00:00:07,000\nC\n");

        assertThat(parsed.cues()).hasSize(3);
        assertThat(parsed.report().overlaps()).isEqualTo(1);
        assertThat(parsed.report().describe()).isEqualTo("1 cue overlaps");
    }

    @Test
    void severalOverlappingCuesArePlural() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:09,000\nA\n\n2\n00:00:02,000 --> 00:00:09,000\nB\n\n"
            + "3\n00:00:03,000 --> 00:00:09,000\nC\n");

        assertThat(parsed.report().overlaps()).isEqualTo(2);
        assertThat(parsed.report().describe()).isEqualTo("2 cues overlap");
    }

    @Test
    void aBlockHoldingTwoTimingLinesWithNoTextBetweenThemGivesTheTextToTheLastCue() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:02,000\n00:00:03,000 --> 00:00:04,000\nOnly text\n");

        assertThat(parsed.cues()).containsExactly(new SubtitleCue(3000, 4000, "Only text"));
        assertThat(parsed.report().skippedBlocks()).isEqualTo(1);
    }

    @Test
    void aBlockHoldingTwoCuesWhoseTextDoesNotEndInANumberKeepsAllTheText() {
        Parsed parsed = parse("00:00:01,000 --> 00:00:02,000\nFirst\nline two\n"
            + "00:00:03,000 --> 00:00:04,000\nSecond\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("First\nline two", "Second");
    }

    @Test
    void zeroLengthCuesAreKeptAndCounted() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:01,000\nA\n\n2\n00:00:03,000 --> 00:00:03,000\nB\n");

        assertThat(parsed.cues()).hasSize(2);
        assertThat(parsed.report().zeroLength()).isEqualTo(2);
        assertThat(parsed.report().describe()).isEqualTo("2 cues have no duration");
        assertThat(parse("1\n00:00:01,000 --> 00:00:01,000\nA\n").report().describe())
            .isEqualTo("1 cue has no duration");
    }

    @Test
    void blocksWithNoTimingLineAreSkippedAndCounted() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:02,000\nGood\n\nthis is not a cue\n\n"
            + "3\n00:00:xx,000 --> 00:00:04,000\nBad times\n\n4\n00:00:05,000 --> 00:00:06,000\nGood too\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("Good", "Good too");
        assertThat(parsed.report().skippedBlocks()).isEqualTo(2);
        assertThat(parsed.report().describe()).isEqualTo("2 blocks skipped");
    }

    @Test
    void oneSkippedBlockIsSingular() {
        assertThat(parse("garbage\n\n1\n00:00:01,000 --> 00:00:02,000\nA\n").report().describe())
            .isEqualTo("1 block skipped");
    }

    @Test
    void aCueThatEndsBeforeItStartsOrHasNoTextIsSkippedAndCounted() {
        Parsed parsed = parse("1\n00:00:05,000 --> 00:00:04,000\nBackwards\n\n"
            + "2\n00:00:06,000 --> 00:00:07,000\n\n3\n00:00:08,000 --> 00:00:09,000\n<i></i>\n\n"
            + "4\n00:00:10,000 --> 00:00:11,000\nFine\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("Fine");
        assertThat(parsed.report().skippedBlocks()).isEqualTo(3);
    }

    @Test
    void aMissingBlankLineBetweenTwoCuesStillGivesTwoCues() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:02,000\nFirst\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("First", "Second");
    }

    @Test
    void aTextLineThatIsOnlyDigitsStaysWhenNoCueFollowsInTheBlock() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:02,000\nIn the year\n1999\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("In the year\n1999");
    }

    @Test
    void markupTagsAreDroppedAndTheTextKept() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:02,000\n<i>Italic</i> and <font color=\"#fff\">white</font>"
            + "\n{\\an8}Top line\n  <b> </b>\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("Italic and white\nTop line");
    }

    @Test
    void textThatLooksLikeMarkupAfterEscapingIsNotInterpretedHere() {
        Parsed parsed = parse("1\n00:00:01,000 --> 00:00:02,000\n5 < 6 & 7 > 3\n");

        assertThat(parsed.cues()).extracting(SubtitleCue::text).containsExactly("5 < 6 & 7 > 3");
    }

    @Test
    void anEmptyFileHasNoCuesAndIsUnusable() {
        Parsed parsed = SrtParser.parse(new byte[0]);

        assertThat(parsed.cues()).isEmpty();
        assertThat(parsed.report().describe()).isEqualTo("no usable cues");
    }

    @Test
    void aFileOfExactlyOneMegabyteIsReadAndOneByteMoreIsNot() {
        String cue = "1\n00:00:01,000 --> 00:00:02,000\nA\n";
        byte[] exact = new byte[SrtParser.MAX_BYTES];
        java.util.Arrays.fill(exact, (byte) ' ');
        byte[] head = cue.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, exact, 0, head.length);

        Parsed read = SrtParser.parse(exact);
        Parsed refused = SrtParser.parse(new byte[SrtParser.MAX_BYTES + 1]);

        assertThat(read.cues()).hasSize(1);
        assertThat(read.report().tooLarge()).isFalse();
        assertThat(refused.cues()).isEmpty();
        assertThat(refused.report().tooLarge()).isTrue();
        assertThat(refused.report().describe()).isEqualTo("file is larger than 1 MB, so it was not read");
    }

    @Test
    void atMostFiveThousandCuesAreKeptAndTheReportSaysSo() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < SrtParser.MAX_CUES + 1; i++) {
            text.append(i + 1).append('\n').append(SrtParserTest.stamp(i * 2)).append(" --> ")
                .append(SrtParserTest.stamp(i * 2 + 1)).append("\nLine ").append(i).append("\n\n");
        }

        Parsed parsed = parse(text.toString());

        assertThat(parsed.cues()).hasSize(SrtParser.MAX_CUES);
        assertThat(parsed.cues().get(SrtParser.MAX_CUES - 1).text()).isEqualTo("Line 4999");
        assertThat(parsed.report().truncated()).isTrue();
        assertThat(parsed.report().describe()).isEqualTo("only the first 5,000 cues were read");
    }

    @Test
    void exactlyFiveThousandCuesAreNotTruncated() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < SrtParser.MAX_CUES; i++) {
            text.append(i + 1).append('\n').append(stamp(i * 2)).append(" --> ").append(stamp(i * 2 + 1))
                .append("\nLine ").append(i).append("\n\n");
        }

        Parsed parsed = parse(text.toString());

        assertThat(parsed.cues()).hasSize(SrtParser.MAX_CUES);
        assertThat(parsed.report().truncated()).isFalse();
    }

    private static String stamp(int seconds) {
        return String.format("%02d:%02d:%02d,000", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    @Test
    void theReportListsEveryProblemInOneLine() {
        byte[] bytes = ("2\n00:00:05,000 --> 00:00:05,000\nB\n\nbad\n\n1\n00:00:01,000 --> 00:00:06,000\nA\n")
            .getBytes(Charset.forName("windows-1252"));
        byte[] odd = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, odd, 0, bytes.length);
        odd[bytes.length] = (byte) 0xE9;

        assertThat(SrtParser.parse(odd).report().describe()).isEqualTo("read as Windows-1252, 1 block skipped, "
            + "cues were out of order and have been sorted, 1 cue overlaps, 1 cue has no duration");
    }

    @Test
    void formatWritesNumberedCommaSrtThatReadsBackToTheSameCues() {
        List<SubtitleCue> cues = parse(BASIC).cues();

        String text = SrtParser.format(cues);

        assertThat(text).isEqualTo("1\n00:36:36,778 --> 00:36:40,239\nHe is behind you.\n\n"
            + "2\n00:36:41,000 --> 00:36:43,500\nLine one\nLine two\n\n");
        assertThat(SrtParser.cues(text)).isEqualTo(cues);
    }

    @Test
    void formatWritesHoursAndAnEmptyListAsNothing() {
        assertThat(SrtParser.format(List.of(new SubtitleCue(3_723_004, 3_724_000, "A"))))
            .isEqualTo("1\n01:02:03,004 --> 01:02:04,000\nA\n\n");
        assertThat(SrtParser.format(List.of())).isEmpty();
    }

    @Test
    void storedTextWithMoreThanAMegabyteOfCuesIsStillRead() {
        List<SubtitleCue> cues = SrtParser.cues("1\n00:00:01,000 --> 00:00:02,000\n" + "x".repeat(SrtParser.MAX_BYTES)
            + "\n\n");

        assertThat(cues).hasSize(1);
    }
}
