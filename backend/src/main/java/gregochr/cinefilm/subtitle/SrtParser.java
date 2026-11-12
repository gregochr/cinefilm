package gregochr.cinefilm.subtitle;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an {@code .srt} file the way a hand-edited one needs: the encoding is detected (a UTF-8 or UTF-16 byte-order
 * mark, else valid UTF-8, else Windows-1252), line endings may be CRLF, LF or CR, the milliseconds may follow a comma
 * or a dot, cue numbers may be missing or wrong, cues may be out of order (they are sorted) or overlap (kept and
 * counted), a block with no usable timing line is skipped and counted, and markup tags are dropped. A file over 1 MB
 * is not read, and at most 5,000 cues are kept. Pure: bytes in, cues and a report out.
 */
public final class SrtParser {

    /** The largest file read, in bytes. */
    public static final int MAX_BYTES = 1024 * 1024;

    /** The most cues kept from one file. */
    public static final int MAX_CUES = 5000;

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final Pattern TIMING = Pattern.compile("^\\s*(\\d{1,3}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})"
        + "\\s*-->\\s*(\\d{1,3}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})(?:\\s.*)?$");
    private static final Pattern NUMBER_LINE = Pattern.compile("^\\d+$");
    /** HTML-style tags ({@code <i>}, {@code </font>}) and ASS overrides ({@code {\an8}}), but not a lone "5 < 6". */
    private static final Pattern TAGS = Pattern.compile("</?[A-Za-z][^>]*>|\\{\\\\[^}]*}");

    private SrtParser() {
    }

    /**
     * How a file was read.
     *
     * @param encoding {@code UTF-8}, {@code UTF-16} or {@code Windows-1252}
     * @param skippedBlocks blocks with no usable timing line, or whose cue had no text or ended before it started
     * @param overlaps cues that start before the previous cue (in time order) has ended
     * @param zeroLength cues that end when they start
     * @param reordered true when the cues were out of order in the file and have been sorted
     * @param truncated true when there were more than {@link #MAX_CUES} cues and the rest were dropped
     * @param tooLarge true when the file was over {@link #MAX_BYTES} and was not read
     * @param cues how many cues were kept
     */
    public record Report(String encoding, int skippedBlocks, int overlaps, int zeroLength, boolean reordered,
                         boolean truncated, boolean tooLarge, int cues) {

        /** The report in plain words for the owner, for example "3 blocks skipped, 2 cues overlap". */
        public String describe() {
            List<String> notes = new ArrayList<>();
            if (tooLarge) {
                notes.add("file is larger than 1 MB, so it was not read");
            }
            if (!"UTF-8".equals(encoding)) {
                notes.add("read as " + encoding);
            }
            if (skippedBlocks > 0) {
                notes.add(skippedBlocks + (skippedBlocks == 1 ? " block" : " blocks") + " skipped");
            }
            if (reordered) {
                notes.add("cues were out of order and have been sorted");
            }
            if (overlaps > 0) {
                notes.add(overlaps + (overlaps == 1 ? " cue overlaps" : " cues overlap"));
            }
            if (zeroLength > 0) {
                notes.add(zeroLength + (zeroLength == 1 ? " cue has" : " cues have") + " no duration");
            }
            if (truncated) {
                notes.add("only the first 5,000 cues were read");
            }
            if (cues == 0 && !tooLarge) {
                notes.add("no usable cues");
            }
            return notes.isEmpty() ? "No problems found" : String.join(", ", notes);
        }
    }

    /**
     * The cues of a file and how it was read.
     *
     * @param cues the cues in time order
     * @param report how the file was read
     */
    public record Parsed(List<SubtitleCue> cues, Report report) {
    }

    /** A timing line's position in its block and its matched times. */
    private record Timing(int line, Matcher times) {
    }

    /** Bytes decoded to text and the name of the encoding that worked. */
    private record Decoded(String text, String encoding) {
    }

    /**
     * Reads the bytes of an {@code .srt} file.
     *
     * @param bytes the file's content
     * @return the cues and the report; a file over {@link #MAX_BYTES} has no cues
     */
    public static Parsed parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            return new Parsed(List.of(), new Report("UTF-8", 0, 0, 0, false, false, true, 0));
        }
        Decoded decoded = decode(bytes);
        return parseText(decoded.text(), decoded.encoding());
    }

    /**
     * The cues of text this class wrote with {@link #format}.
     *
     * @param normalisedSrt the stored SRT text
     * @return the cues in time order
     */
    public static List<SubtitleCue> cues(String normalisedSrt) {
        return parseText(normalisedSrt, "UTF-8").cues();
    }

    /**
     * Writes cues as plain SRT: numbered from 1, {@code hh:mm:ss,mmm} times, a blank line after each.
     *
     * @param cues the cues in time order
     * @return the SRT text
     */
    public static String format(List<SubtitleCue> cues) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            out.append(i + 1).append('\n').append(time(cue.startMs())).append(" --> ").append(time(cue.endMs()))
                .append('\n').append(cue.text()).append("\n\n");
        }
        return out.toString();
    }

    private static String time(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60,
            ms % 1000);
    }

    private static Decoded decode(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), "UTF-8");
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), "UTF-16");
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), "UTF-16");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new Decoded(text, "UTF-8");
        } catch (CharacterCodingException e) {
            return new Decoded(new String(bytes, WINDOWS_1252), "Windows-1252");
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (i >= bytes.length || (bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static Parsed parseText(String text, String encoding) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<SubtitleCue> cues = new ArrayList<>();
        int[] skipped = {0};
        List<String> block = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                readBlock(block, cues, skipped);
                block.clear();
            } else {
                block.add(line);
            }
        }
        readBlock(block, cues, skipped);
        boolean reordered = false;
        for (int i = 1; i < cues.size(); i++) {
            reordered |= cues.get(i).startMs() < cues.get(i - 1).startMs();
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::startMs).thenComparingLong(SubtitleCue::endMs));
        boolean truncated = cues.size() > MAX_CUES;
        List<SubtitleCue> kept = truncated ? new ArrayList<>(cues.subList(0, MAX_CUES)) : cues;
        int overlaps = 0;
        int zeroLength = 0;
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0 && kept.get(i).startMs() < kept.get(i - 1).endMs()) {
                overlaps++;
            }
            if (kept.get(i).endMs() == kept.get(i).startMs()) {
                zeroLength++;
            }
        }
        return new Parsed(List.copyOf(kept),
            new Report(encoding, skipped[0], overlaps, zeroLength, reordered, truncated, false, kept.size()));
    }

    /** Reads one blank-line-separated block: a number line (optional), the timing line, the text. */
    private static void readBlock(List<String> block, List<SubtitleCue> cues, int[] skipped) {
        if (block.isEmpty()) {
            return;
        }
        List<Timing> timings = new ArrayList<>();
        for (int i = 0; i < block.size(); i++) {
            Matcher matcher = TIMING.matcher(block.get(i));
            if (matcher.matches()) {
                timings.add(new Timing(i, matcher));
            }
        }
        if (timings.isEmpty()) {
            skipped[0]++;
            return;
        }
        for (int j = 0; j < timings.size(); j++) {
            int from = timings.get(j).line() + 1;
            int to = j + 1 < timings.size() ? timings.get(j + 1).line() : block.size();
            // A missing blank line before the next cue leaves that cue's number at the end of this one's text.
            if (j + 1 < timings.size() && to > from && NUMBER_LINE.matcher(block.get(to - 1).strip()).matches()) {
                to--;
            }
            SubtitleCue cue = cue(timings.get(j).times(), block.subList(from, to));
            if (cue == null) {
                skipped[0]++;
            } else {
                cues.add(cue);
            }
        }
    }

    /** The cue of a timing line and its text lines, or null when it ends before it starts or has no text. */
    private static SubtitleCue cue(Matcher matcher, List<String> textLines) {
        long start = millis(matcher, 1);
        long end = millis(matcher, 5);
        List<String> kept = new ArrayList<>();
        for (String line : textLines) {
            String cleaned = TAGS.matcher(line).replaceAll("").strip();
            if (!cleaned.isEmpty()) {
                kept.add(cleaned);
            }
        }
        if (end < start || kept.isEmpty()) {
            return null;
        }
        return new SubtitleCue(start, end, String.join("\n", kept));
    }

    private static long millis(Matcher matcher, int firstGroup) {
        long hours = Long.parseLong(matcher.group(firstGroup));
        long minutes = Long.parseLong(matcher.group(firstGroup + 1));
        long seconds = Long.parseLong(matcher.group(firstGroup + 2));
        long fraction = Long.parseLong((matcher.group(firstGroup + 3) + "00").substring(0, 3));
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction;
    }
}
