package gregochr.cinefilm.transcode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads the video keyframe times of an MP4 or MOV file from its {@code moov} sample tables, without decoding: the
 * first video track's {@code stss} (sync samples), {@code stts} (decode times), {@code ctts} (composition offsets),
 * {@code mdhd} (timescale) and {@code elst} (the first edit's start offset). The file is read with a
 * {@link FileChannel}: top-level box headers are walked (seeking over {@code mdat} by its 32 or 64-bit size, so a
 * {@code moov} at the end of the file costs a handful of small reads) and only the {@code moov} box is read, whole.
 *
 * <p>The file is untrusted (SECURITY.md rule 4), so every size is bounded before it is used: the top-level walk
 * stops after {@link #MAX_TOP_LEVEL_BOXES} boxes, {@code moov} may be at most {@link #MAX_MOOV_BYTES}, each child
 * box must fit inside its parent, a level holds at most {@link #MAX_BOXES_PER_LEVEL} boxes, a table's entry count
 * must be at most {@link #MAX_ENTRIES} and fit in its box, and arithmetic is overflow-checked. Nesting is fixed by
 * the path the reader follows ({@code moov/trak/mdia/minf/stbl}), so there is no recursion to bound. Only the first
 * edit with media is applied; later edits (rare outside editing tools) are ignored.
 */
@Component
public class Mp4SampleTableReader {

    /** The largest {@code moov} box that will be read into memory. */
    static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;

    /** Top-level boxes inspected before giving up on finding {@code moov}. */
    static final int MAX_TOP_LEVEL_BOXES = 1000;

    /** Boxes inspected inside one parent box. */
    static final int MAX_BOXES_PER_LEVEL = 10_000;

    /** The most entries a sample table may declare (a three hour film has about 260,000 samples). */
    static final int MAX_ENTRIES = 4_000_000;

    private static final int TYPE_FTYP = fourcc("ftyp");
    private static final int TYPE_MOOV = fourcc("moov");
    private static final int TYPE_MVHD = fourcc("mvhd");
    private static final int TYPE_TRAK = fourcc("trak");
    private static final int TYPE_MDIA = fourcc("mdia");
    private static final int TYPE_MDHD = fourcc("mdhd");
    private static final int TYPE_HDLR = fourcc("hdlr");
    private static final int TYPE_MINF = fourcc("minf");
    private static final int TYPE_STBL = fourcc("stbl");
    private static final int TYPE_STSS = fourcc("stss");
    private static final int TYPE_STTS = fourcc("stts");
    private static final int TYPE_CTTS = fourcc("ctts");
    private static final int TYPE_EDTS = fourcc("edts");
    private static final int TYPE_ELST = fourcc("elst");
    private static final int HANDLER_VIDEO = fourcc("vide");

    /** Box types a media file may start with (ftyp for MP4, anything a QuickTime MOV may begin with). */
    private static final Set<Integer> FIRST_BOXES = Set.of(TYPE_FTYP, TYPE_MOOV, fourcc("mdat"), fourcc("free"),
        fourcc("wide"), fourcc("skip"), fourcc("pnot"));

    /** A box's payload: the bytes after its header, as offsets into the {@code moov} buffer. */
    private record Range(int start, int end) {
        int length() {
            return end - start;
        }
    }

    /** Video track timing: {@code mediaTime} and {@code delayMs} come from the first edit. */
    private record Timing(long timescale, long mediaTime, long delayMs) {
    }

    private static int fourcc(String type) {
        return ByteBuffer.wrap(type.getBytes(StandardCharsets.US_ASCII)).getInt();
    }

    /**
     * The presentation times of the video keyframes of the first video track.
     *
     * @param file the MP4 or MOV file
     * @return keyframe times in milliseconds (sorted, without duplicates, never empty) and the average frame rate
     * @throws KeyframeIndexException when the file is unreadable, not an MP4, malformed, has no video track or no
     *     sync sample table, or any size in it is out of bounds
     */
    public KeyframeIndex index(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer moov = readMoov(channel);
            return parse(moov);
        } catch (IOException e) {
            throw new KeyframeIndexException("Cannot read the file's boxes: " + e.getMessage(), e);
        } catch (ArithmeticException | IndexOutOfBoundsException e) {
            throw new KeyframeIndexException("Malformed MP4 sample tables", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new KeyframeIndexException(message);
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, long length) throws IOException {
        channel.position(position);
        return ByteBuffer.wrap(Channels.newInputStream(channel).readNBytes(Math.toIntExact(length)));
    }

    /** Walks the top-level boxes and returns the payload of {@code moov}, wherever in the file it is. */
    private static ByteBuffer readMoov(FileChannel channel) throws IOException {
        long fileSize = channel.size();
        long position = 0;
        for (int boxes = 0; boxes < MAX_TOP_LEVEL_BOXES && position + 8 <= fileSize; boxes++) {
            ByteBuffer header = read(channel, position, Math.min(16, fileSize - position));
            long size = header.getInt(0) & 0xFFFFFFFFL;
            int type = header.getInt(4);
            int headerSize = 8;
            if (size == 1) {
                require(header.limit() >= 16, "Truncated 64-bit box header");
                size = header.getLong(8);
                headerSize = 16;
            } else if (size == 0) {
                size = fileSize - position;
            }
            require(boxes > 0 || FIRST_BOXES.contains(type), "Not an MP4 file");
            require(size >= headerSize, "Box smaller than its own header");
            if (type == TYPE_MOOV) {
                require(size - headerSize <= MAX_MOOV_BYTES, "moov box is too large");
                require(position + size <= fileSize, "moov box is truncated");
                return read(channel, position + headerSize, size - headerSize);
            }
            position = Math.addExact(position, size);
        }
        throw new KeyframeIndexException("No moov box found");
    }

    private static KeyframeIndex parse(ByteBuffer moov) {
        Range root = new Range(0, moov.limit());
        Range track = videoTrack(moov, root);
        Range mdia = only(moov, track, TYPE_MDIA, "video track has no mdia");
        Range stbl = only(moov, only(moov, mdia, TYPE_MINF, "video track has no minf"), TYPE_STBL,
            "video track has no stbl");
        Timing timing = timing(moov, root, track, mdia);
        List<Long> sync = syncSamples(moov, only(moov, stbl, TYPE_STSS, "video track has no sync sample table"));
        Range stts = only(moov, stbl, TYPE_STTS, "video track has no stts");
        Range ctts = optional(moov, stbl, TYPE_CTTS);
        List<Long> keyframes = new ArrayList<>(sync.size());
        Walker decode = new Walker(moov, stts);
        Walker offsets = ctts == null ? null : new Walker(moov, ctts);
        for (long sample : sync) {
            long cts = decode.decodeTime(sample);
            if (offsets != null) {
                cts = Math.addExact(cts, offsets.offsetAt(sample));
            }
            long ms = Math.addExact(toMs(Math.subtractExact(cts, timing.mediaTime()), timing.timescale()),
                timing.delayMs());
            if (ms >= 0) {
                keyframes.add(ms);
            }
        }
        List<Long> sorted = keyframes.stream().sorted().distinct().toList();
        require(!sorted.isEmpty(), "No keyframes found");
        return new KeyframeIndex(sorted, averageFps(moov, stts, timing.timescale()));
    }

    /** Samples per second over the whole track from the run-length {@code stts} table; null when it has no time. */
    private static Double averageFps(ByteBuffer moov, Range stts, long timescale) {
        int entries = entryCount(moov, stts, 8);
        long samples = 0;
        long ticks = 0;
        for (int i = 0; i < entries; i++) {
            long count = moov.getInt(stts.start() + 8 + i * 8) & 0xFFFFFFFFL;
            long delta = moov.getInt(stts.start() + 12 + i * 8) & 0xFFFFFFFFL;
            samples = Math.addExact(samples, count);
            ticks = Math.addExact(ticks, Math.multiplyExact(count, delta));
        }
        return ticks == 0 ? null : (double) samples * timescale / ticks;
    }

    private static long toMs(long ticks, long timescale) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(ticks, 1000L), timescale / 2), timescale);
    }

    /** The children of {@code parent} with the given type, in file order, with every size checked. */
    private static List<Range> children(ByteBuffer buffer, Range parent, int type) {
        List<Range> found = new ArrayList<>();
        int position = parent.start();
        for (int boxes = 0; position < parent.end(); boxes++) {
            require(boxes < MAX_BOXES_PER_LEVEL, "Too many boxes");
            require(parent.end() - position >= 8, "Truncated box header");
            long size = buffer.getInt(position) & 0xFFFFFFFFL;
            int boxType = buffer.getInt(position + 4);
            int headerSize = 8;
            if (size == 1) {
                require(parent.end() - position >= 16, "Truncated 64-bit box header");
                size = buffer.getLong(position + 8);
                headerSize = 16;
            } else if (size == 0) {
                size = parent.end() - position;
            }
            require(size >= headerSize && size <= parent.end() - position, "Box size is out of bounds");
            if (boxType == type) {
                found.add(new Range(position + headerSize, position + (int) size));
            }
            position += (int) size;
        }
        return found;
    }

    private static Range optional(ByteBuffer buffer, Range parent, int type) {
        List<Range> found = children(buffer, parent, type);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Range only(ByteBuffer buffer, Range parent, int type, String missing) {
        Range found = optional(buffer, parent, type);
        require(found != null, missing);
        return found;
    }

    private static Range videoTrack(ByteBuffer moov, Range root) {
        for (Range track : children(moov, root, TYPE_TRAK)) {
            Range mdia = optional(moov, track, TYPE_MDIA);
            Range hdlr = mdia == null ? null : optional(moov, mdia, TYPE_HDLR);
            if (hdlr != null && hdlr.length() >= 12 && moov.getInt(hdlr.start() + 8) == HANDLER_VIDEO) {
                return track;
            }
        }
        throw new KeyframeIndexException("No video track");
    }

    private static long timescale(ByteBuffer buffer, Range box) {
        require(box.length() > 0, "Header box is too short");
        int offset = buffer.get(box.start()) == 1 ? 20 : 12;
        require(box.length() >= offset + 4, "Header box is too short");
        long timescale = buffer.getInt(box.start() + offset) & 0xFFFFFFFFL;
        require(timescale > 0, "Timescale is zero");
        return timescale;
    }

    private static Timing timing(ByteBuffer moov, Range root, Range track, Range mdia) {
        Range mdhd = only(moov, mdia, TYPE_MDHD, "video track has no mdhd");
        long mediaScale = timescale(moov, mdhd);
        Range edts = optional(moov, track, TYPE_EDTS);
        Range elst = edts == null ? null : optional(moov, edts, TYPE_ELST);
        if (elst == null) {
            return new Timing(mediaScale, 0, 0);
        }
        require(elst.length() >= 8, "elst is too short");
        boolean wide = moov.get(elst.start()) == 1;
        int entrySize = wide ? 20 : 12;
        long count = moov.getInt(elst.start() + 4) & 0xFFFFFFFFL;
        require(count <= MAX_ENTRIES && 8 + count * entrySize <= elst.length(), "elst entries do not fit");
        long delayTicks = 0;
        for (int i = 0; i < count; i++) {
            int at = elst.start() + 8 + i * entrySize;
            long duration = wide ? moov.getLong(at) : moov.getInt(at) & 0xFFFFFFFFL;
            long mediaTime = wide ? moov.getLong(at + 8) : moov.getInt(at + 4);
            if (mediaTime >= 0) {
                return new Timing(mediaScale, mediaTime, delayMs(moov, root, delayTicks));
            }
            delayTicks = Math.addExact(delayTicks, duration);
        }
        return new Timing(mediaScale, 0, 0);
    }

    /** The length of the leading empty edits in milliseconds; they are in the movie's timescale. */
    private static long delayMs(ByteBuffer moov, Range root, long ticks) {
        if (ticks == 0) {
            return 0;
        }
        return toMs(ticks, timescale(moov, only(moov, root, TYPE_MVHD, "moov has no mvhd")));
    }

    private static List<Long> syncSamples(ByteBuffer moov, Range stss) {
        int count = entryCount(moov, stss, 4);
        List<Long> samples = new ArrayList<>(count);
        long previous = 0;
        for (int i = 0; i < count; i++) {
            long sample = moov.getInt(stss.start() + 8 + i * 4) & 0xFFFFFFFFL;
            require(sample > previous, "Sync samples are not increasing");
            samples.add(sample);
            previous = sample;
        }
        return samples;
    }

    /** Validates a table's entry count against the cap and the bytes the box really has; returns the count. */
    private static int entryCount(ByteBuffer moov, Range table, int entrySize) {
        require(table.length() >= 8, "Table box is too short");
        long count = moov.getInt(table.start() + 4) & 0xFFFFFFFFL;
        require(count <= MAX_ENTRIES && 8 + count * entrySize <= table.length(), "Table entries do not fit");
        return (int) count;
    }

    /**
     * Walks a run-length table of (count, value) pairs ({@code stts}, {@code ctts}) forwards, for samples asked for
     * in increasing order.
     */
    private static final class Walker {
        private final ByteBuffer buffer;
        private final int entries;
        private final int first;
        private int index;
        private long sampleBase;
        private long timeBase;

        private Walker(ByteBuffer buffer, Range table) {
            this.buffer = buffer;
            this.entries = entryCount(buffer, table, 8);
            this.first = table.start() + 8;
        }

        private long count() {
            return buffer.getInt(first + index * 8) & 0xFFFFFFFFL;
        }

        private int rawValue() {
            return buffer.getInt(first + index * 8 + 4);
        }

        /** Moves to the entry holding the 1-based sample; false when the table ends before it. */
        private boolean seek(long sample) {
            while (index < entries && sample > sampleBase + count()) {
                sampleBase = Math.addExact(sampleBase, count());
                timeBase = Math.addExact(timeBase, Math.multiplyExact(count(), rawValue() & 0xFFFFFFFFL));
                index++;
            }
            return index < entries;
        }

        /** The decode time of the sample in {@code stts} ticks. */
        private long decodeTime(long sample) {
            require(seek(sample), "Sync sample lies beyond the stts table");
            return Math.addExact(timeBase, Math.multiplyExact(sample - 1 - sampleBase, rawValue() & 0xFFFFFFFFL));
        }

        /** The signed composition offset of the sample; 0 when the {@code ctts} table ends before it. */
        private long offsetAt(long sample) {
            return seek(sample) ? rawValue() : 0;
        }
    }
}
