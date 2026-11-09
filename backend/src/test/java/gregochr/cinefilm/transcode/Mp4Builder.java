package gregochr.cinefilm.transcode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Builds tiny MP4 files byte by byte, so the reader is tested against exactly the boxes each case needs. */
final class Mp4Builder {

    private Mp4Builder() {
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] u32(long value) {
        return ByteBuffer.allocate(4).putInt((int) value).array();
    }

    static byte[] u64(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    static byte[] type(String type) {
        return type.getBytes(StandardCharsets.US_ASCII);
    }

    /** A box with a 32-bit size. */
    static byte[] box(String type, byte[]... children) {
        byte[] payload = concat(children);
        return concat(u32(8 + payload.length), type(type), payload);
    }

    /** A box with the 64-bit size form (size field 1, then the real size after the type). */
    static byte[] box64(String type, byte[]... children) {
        byte[] payload = concat(children);
        return concat(u32(1), type(type), u64(16 + payload.length), payload);
    }

    /** A box whose size field is 0: it extends to the end of its parent or file. */
    static byte[] boxToEnd(String type, byte[]... children) {
        return concat(u32(0), type(type), concat(children));
    }

    /** A box whose header claims {@code claimed} bytes whatever it holds. */
    static byte[] boxClaiming(long claimed, String type, byte[]... children) {
        return concat(u32(claimed), type(type), concat(children));
    }

    static byte[] fullBox(String type, int version, byte[]... payload) {
        return box(type, new byte[] {(byte) version, 0, 0, 0}, concat(payload));
    }

    static byte[] ftyp() {
        return box("ftyp", type("isom"), u32(512), type("isom"), type("mp42"));
    }

    static byte[] mdat(int bytes) {
        return box("mdat", new byte[bytes]);
    }

    static byte[] mvhd(long timescale) {
        return fullBox("mvhd", 0, u32(0), u32(0), u32(timescale), u32(0), new byte[80]);
    }

    static byte[] mdhd(int version, long timescale) {
        if (version == 1) {
            return fullBox("mdhd", 1, u64(0), u64(0), u32(timescale), u64(0), new byte[4]);
        }
        return fullBox("mdhd", 0, u32(0), u32(0), u32(timescale), u32(0), new byte[4]);
    }

    static byte[] hdlr(String handler) {
        return fullBox("hdlr", 0, u32(0), type(handler), new byte[12], new byte[1]);
    }

    static byte[] stss(int... samples) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int sample : samples) {
            out.writeBytes(u32(sample));
        }
        return fullBox("stss", 0, u32(samples.length), out.toByteArray());
    }

    /** Run-length (count, value) pairs for stts and ctts. */
    static byte[] pairs(String type, int version, long[]... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long[] entry : entries) {
            out.writeBytes(u32(entry[0]));
            out.writeBytes(u32(entry[1]));
        }
        return fullBox(type, version, u32(entries.length), out.toByteArray());
    }

    static byte[] stts(long[]... entries) {
        return pairs("stts", 0, entries);
    }

    static byte[] ctts(int version, long[]... entries) {
        return pairs("ctts", version, entries);
    }

    /** Edit list; each entry is {segmentDuration, mediaTime}. */
    static byte[] elst(int version, long[]... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long[] entry : entries) {
            if (version == 1) {
                out.writeBytes(u64(entry[0]));
                out.writeBytes(u64(entry[1]));
            } else {
                out.writeBytes(u32(entry[0]));
                out.writeBytes(u32(entry[1]));
            }
            out.writeBytes(u32(0x00010000));
        }
        return fullBox("elst", version, u32(entries.length), out.toByteArray());
    }

    static byte[] stbl(byte[]... tables) {
        return box("stbl", tables);
    }

    static byte[] trak(String handler, byte[] mdhd, byte[] stbl, byte[]... edts) {
        byte[] mdia = box("mdia", mdhd, hdlr(handler), box("minf", stbl));
        return edts.length == 0 ? box("trak", mdia) : box("trak", box("edts", edts), mdia);
    }

    /** The common video track: timescale 24000, samples of 1001 ticks, keyframes every 24 samples. */
    static byte[] videoTrack(byte[]... edts) {
        return trak("vide", mdhd(0, 24_000), stbl(stss(1, 25, 49, 73), stts(new long[] {240, 1001})), edts);
    }

    static byte[] moov(byte[]... children) {
        return box("moov", mvhd(1000), concat(children));
    }

    /** ftyp, a small mdat, then the moov. */
    static byte[] moovAtEnd(byte[] moov) {
        return concat(ftyp(), mdat(100), moov);
    }

    /** ftyp, the moov, then a small mdat. */
    static byte[] moovFirst(byte[] moov) {
        return concat(ftyp(), moov, mdat(100));
    }
}
