package gregochr.cinefilm.credits.learn;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * {@code embeddings.bin}, the file that holds one run's embeddings
 * ({@code docs/engineering/credits-classifier-plan.md}, "{@code EmbeddingFile}"). Binary, little-endian. The
 * header is the 8 ASCII bytes {@code CFEMB001}, an {@code int} dimension and a {@code long} row count. Each record
 * is a {@code long} media file id, a {@code byte} kind (0 coarse, 1 dense), a {@code long} offset in milliseconds
 * and {@code dimension} floats: {@code 17 + 4 × dimension} bytes.
 * {@link Writer} appends records and writes the row count into the header when it is closed; {@link Reader} is a
 * sequential cursor that refuses a file that is not exactly what the writer makes.
 */
final class EmbeddingFile {

    /** The kind byte of a coarse frame. */
    static final int COARSE = 0;
    /** The kind byte of a dense frame. */
    static final int DENSE = 1;

    private static final byte[] MAGIC = "CFEMB001".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES + Long.BYTES;
    private static final long ROWS_AT = MAGIC.length + Integer.BYTES;
    private static final int RECORD_FIXED_BYTES = Long.BYTES + 1 + Long.BYTES;
    private static final int BUFFER_BYTES = 1 << 16;

    private EmbeddingFile() {
    }

    /**
     * The size of one record.
     *
     * @param dimension the length of an embedding
     * @return {@code 17 + 4 × dimension}
     */
    static long recordBytes(int dimension) {
        return RECORD_FIXED_BYTES + (long) Float.BYTES * dimension;
    }

    /**
     * Starts a new file. It must not exist yet.
     *
     * @param file where to write
     * @param dimension the length of an embedding, at least 1
     * @return the writer; close it to finish the file
     * @throws IOException when the file cannot be created
     * @throws IllegalArgumentException when {@code dimension} is below 1
     */
    static Writer create(Path file, int dimension) throws IOException {
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be at least 1: " + dimension);
        }
        return new Writer(FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), dimension);
    }

    /**
     * Opens a file for reading, after checking the header and the length.
     *
     * @param file the file
     * @return the cursor, before the first record; close it when done
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when the magic is wrong, the dimension is below 1, the row count is negative
     *     or the file's length is not the header plus {@code rows} records
     */
    static Reader open(Path file) throws IOException {
        long length = Files.size(file);
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEADER_BYTES);
        }
        Header header = header(head, length);
        DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
        in.skipNBytes(HEADER_BYTES);
        return new Reader(in, header.dimension(), header.rows(), header.record());
    }

    /** What the header says: the dimension, the row count and the size of one record. */
    private record Header(int dimension, long rows, int record) {
    }

    private static Header header(byte[] head, long length) {
        if (head.length < HEADER_BYTES) {
            throw new IllegalArgumentException("the file is too short for a header: " + length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("the file does not start with CFEMB001");
        }
        int dimension = buffer.getInt();
        long rows = buffer.getLong();
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be at least 1: " + dimension);
        }
        if (rows < 0) {
            throw new IllegalArgumentException("rows must not be negative: " + rows);
        }
        long expected;
        int record;
        try {
            record = Math.toIntExact(recordBytes(dimension));
            expected = Math.addExact(HEADER_BYTES, Math.multiplyExact(rows, (long) record));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("the header's dimension " + dimension + " and rows " + rows
                + " are larger than any file", e);
        }
        if (length != expected) {
            throw new IllegalArgumentException("the file is " + length + " bytes, the header (dimension " + dimension
                + ", rows " + rows + ") needs " + expected);
        }
        return new Header(dimension, rows, record);
    }

    /** Appends records. Not thread-safe. */
    static final class Writer implements AutoCloseable {

        private final FileChannel channel;
        private final int dimension;
        private final int record;
        private final ByteBuffer buffer;
        private long rows;
        private boolean closed;

        private Writer(FileChannel channel, int dimension) {
            this.channel = channel;
            this.dimension = dimension;
            this.record = Math.toIntExact(recordBytes(dimension));
            this.buffer = ByteBuffer.allocate(Math.max(BUFFER_BYTES, record)).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(MAGIC).putInt(dimension).putLong(0L);
        }

        /**
         * Appends one record.
         *
         * @param mediaFileId the film's media file id
         * @param kind {@link EmbeddingFile#COARSE} or {@link EmbeddingFile#DENSE}
         * @param offsetMs the frame's offset in milliseconds
         * @param values the embedding, {@code dimension} finite floats
         * @throws IOException when the file cannot be written
         * @throws IllegalArgumentException when the kind is not 0 or 1, or the embedding has the wrong length or a
         *     NaN or infinite value (nothing is written)
         * @throws IllegalStateException when the writer is closed
         */
        void append(long mediaFileId, int kind, long offsetMs, float[] values) throws IOException {
            if (closed) {
                throw new IllegalStateException("the writer is closed");
            }
            if (kind != COARSE && kind != DENSE) {
                throw new IllegalArgumentException("kind must be 0 or 1: " + kind);
            }
            if (values.length != dimension) {
                throw new IllegalArgumentException(
                    "the embedding has " + values.length + " values, the file takes " + dimension);
            }
            for (float value : values) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("the embedding holds a NaN or infinite value");
                }
            }
            if (buffer.remaining() < record) {
                flush();
            }
            buffer.putLong(mediaFileId).put((byte) kind).putLong(offsetMs);
            for (float value : values) {
                buffer.putFloat(value);
            }
            rows++;
        }

        /**
         * The number of records appended so far.
         *
         * @return the count
         */
        long rows() {
            return rows;
        }

        /**
         * Writes what is buffered and the row count into the header, then closes the file. A second call does
         * nothing.
         *
         * @throws IOException when the file cannot be written
         */
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try (channel) {
                flush();
                ByteBuffer count = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(rows);
                count.flip();
                channel.write(count, ROWS_AT);
            }
        }

        private void flush() throws IOException {
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            buffer.clear();
        }
    }

    /** A sequential cursor over a file's records. Not thread-safe. */
    static final class Reader implements AutoCloseable {

        private final DataInputStream in;
        private final int dimension;
        private final long rows;
        private final byte[] bytes;
        private final ByteBuffer view;
        private final float[] values;
        private long read;
        private long mediaFileId;
        private int kind;
        private long offsetMs;

        private Reader(DataInputStream in, int dimension, long rows, int record) {
            this.in = in;
            this.dimension = dimension;
            this.rows = rows;
            this.bytes = new byte[record];
            this.view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            this.values = new float[dimension];
        }

        /**
         * The length of an embedding.
         *
         * @return the header's dimension
         */
        int dimension() {
            return dimension;
        }

        /**
         * The number of records in the file.
         *
         * @return the header's row count
         */
        long rows() {
            return rows;
        }

        /**
         * Moves to the next record.
         *
         * @return false at the end of the file
         * @throws IOException when the file cannot be read
         * @throws IllegalArgumentException when the record's kind is not 0 or 1 or it holds a NaN or infinite float
         */
        boolean next() throws IOException {
            if (read == rows) {
                return false;
            }
            in.readFully(bytes);
            view.clear();
            long id = view.getLong();
            int recordKind = view.get();
            long offset = view.getLong();
            read++;
            if (recordKind != COARSE && recordKind != DENSE) {
                throw new IllegalArgumentException("record " + read + " has kind " + recordKind);
            }
            for (int i = 0; i < dimension; i++) {
                values[i] = view.getFloat();
                if (!Float.isFinite(values[i])) {
                    throw new IllegalArgumentException("record " + read + " holds a NaN or infinite value");
                }
            }
            mediaFileId = id;
            kind = recordKind;
            offsetMs = offset;
            return true;
        }

        /**
         * The current record's media file id.
         *
         * @return the id
         */
        long mediaFileId() {
            return mediaFileId;
        }

        /**
         * The current record's kind.
         *
         * @return {@link EmbeddingFile#COARSE} or {@link EmbeddingFile#DENSE}
         */
        int kind() {
            return kind;
        }

        /**
         * The current record's offset.
         *
         * @return milliseconds
         */
        long offsetMs() {
            return offsetMs;
        }

        /**
         * The current record's embedding.
         *
         * @return a copy of its {@code dimension} floats
         */
        float[] vector() {
            return values.clone();
        }

        /**
         * Closes the file.
         *
         * @throws IOException when it cannot be closed
         */
        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
