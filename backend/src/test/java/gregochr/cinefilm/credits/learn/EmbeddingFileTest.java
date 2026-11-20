package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmbeddingFileTest {

    @TempDir
    Path temp;

    private int files;

    /** A header, then {@code body} bytes; each record in {@code body} is the caller's business. */
    private static byte[] file(String magic, int dimension, long rows, byte[] body) {
        ByteBuffer buffer = ByteBuffer.allocate(20 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(magic.getBytes(StandardCharsets.US_ASCII)).putInt(dimension).putLong(rows).put(body);
        return buffer.array();
    }

    private static byte[] record(long id, int kind, long offset, float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(17 + 4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(id).put((byte) kind).putLong(offset);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private Path write(byte[] bytes) throws IOException {
        Path path = temp.resolve("e" + files++ + ".bin");
        Files.write(path, bytes);
        return path;
    }

    private void refused(byte[] bytes, String message) throws IOException {
        Path path = write(bytes);
        assertThatThrownBy(() -> EmbeddingFile.open(path)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
    }

    @Test
    void recordsComeBackInOrderWithEveryFieldAndTheHeaderHoldsTheRowCount() throws IOException {
        Path path = temp.resolve("embeddings.bin");
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(path, 3)) {
            writer.append(7L, EmbeddingFile.DENSE, 5_000L, new float[] {1.5f, -2f, 0f});
            writer.append(Long.MAX_VALUE, EmbeddingFile.COARSE, 1_234_567_890_123L, new float[] {0.25f, 3f, -0.5f});
            assertThat(writer.rows()).isEqualTo(2);
        }

        byte[] bytes = Files.readAllBytes(path);
        assertThat(bytes).hasSize(20 + 2 * 29);
        ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(bytes, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("CFEMB001");
        assertThat(raw.getInt(8)).isEqualTo(3);
        assertThat(raw.getLong(12)).isEqualTo(2);
        assertThat(Arrays.copyOfRange(bytes, 20, 49)).containsExactly(record(7L, 1, 5_000L, 1.5f, -2f, 0f));
        assertThat(Arrays.copyOfRange(bytes, 49, 78))
            .containsExactly(record(Long.MAX_VALUE, 0, 1_234_567_890_123L, 0.25f, 3f, -0.5f));
        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThat(reader.dimension()).isEqualTo(3);
            assertThat(reader.rows()).isEqualTo(2);
            assertThat(reader.next()).isTrue();
            assertThat(reader.mediaFileId()).isEqualTo(7L);
            assertThat(reader.kind()).isEqualTo(EmbeddingFile.DENSE);
            assertThat(reader.offsetMs()).isEqualTo(5_000L);
            assertThat(reader.vector()).containsExactly(1.5f, -2f, 0f);
            assertThat(reader.next()).isTrue();
            assertThat(reader.mediaFileId()).isEqualTo(Long.MAX_VALUE);
            assertThat(reader.kind()).isEqualTo(EmbeddingFile.COARSE);
            assertThat(reader.offsetMs()).isEqualTo(1_234_567_890_123L);
            assertThat(reader.vector()).containsExactly(0.25f, 3f, -0.5f);
            assertThat(reader.next()).isFalse();
            assertThat(reader.next()).isFalse();
        }
    }

    @Test
    void aHandMadeRecordIsReadWithItsIdAndOffsetNotSwappedAndLittleEndian() throws IOException {
        Path path = write(file("CFEMB001", 2, 1, record(0x0102030405060708L, 1, 5_000L, 1.5f, -2f)));

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThat(reader.next()).isTrue();
            assertThat(reader.mediaFileId()).isEqualTo(0x0102030405060708L);
            assertThat(reader.offsetMs()).isEqualTo(5_000L);
            assertThat(reader.kind()).isEqualTo(1);
            assertThat(reader.vector()).containsExactly(1.5f, -2f);
        }
    }

    @Test
    void theEncodeSeamWritesLittleEndianWhateverTheBuffersOrderAndAdvancesIt() {
        ByteBuffer into = ByteBuffer.allocate(29).order(ByteOrder.BIG_ENDIAN);

        EmbeddingFile.encode(7L, (byte) 1, 5_000L, new float[] {1.5f, -2f, 0f}, into);

        assertThat(into.position()).isEqualTo(29);
        assertThat(into.array()).containsExactly(record(7L, 1, 5_000L, 1.5f, -2f, 0f));
    }

    @Test
    void aDigestFedThroughAppendIsTheSha256OfTheRecordBytesInTheFile() throws Exception {
        Path path = temp.resolve("digest.bin");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(path, 2)) {
            writer.append(1L, EmbeddingFile.COARSE, 0L, new float[] {1f, 2f}, digest);
            writer.append(2L, EmbeddingFile.DENSE, 5_000L, new float[] {3f, 4f}, null);
            writer.append(3L, EmbeddingFile.DENSE, 10_000L, new float[] {5f, 6f}, digest);
            assertThatThrownBy(() -> writer.append(4L, 9, 0L, new float[] {1f, 1f}, digest))
                .isInstanceOf(IllegalArgumentException.class);
        }

        byte[] bytes = Files.readAllBytes(path);
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update(bytes, 20, 25);
        expected.update(bytes, 70, 25);
        assertThat(digest.digest()).isEqualTo(expected.digest());
    }

    @Test
    void aDigestSeesTheBytesAcrossAWriteBufferFlush() throws Exception {
        Path path = temp.resolve("digest-big.bin");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(path, 512)) {
            for (int r = 0; r < 100; r++) {
                float[] values = new float[512];
                values[r] = r;
                writer.append(r, 0, r, values, digest);
            }
        }

        byte[] bytes = Files.readAllBytes(path);
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update(bytes, 20, bytes.length - 20);
        assertThat(digest.digest()).isEqualTo(expected.digest());
    }

    @Test
    void aDimensionWhoseRecordIsTooBigIsRefusedBeforeTheFileIsCreated() throws IOException {
        Path path = temp.resolve("huge.bin");

        assertThatThrownBy(() -> EmbeddingFile.create(path, 262_140)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("dimension 262140 makes a record of 1048577 bytes, more than 1048576");
        assertThatThrownBy(() -> EmbeddingFile.create(path, Integer.MAX_VALUE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("dimension 2147483647 makes a record of 8589934605 bytes, more than 1048576");
        assertThat(path).doesNotExist();
        EmbeddingFile.create(path, 262_139).close();
        assertThat(Files.size(path)).isEqualTo(20);
    }

    @Test
    void aFileLargerThanTheWriteBufferSurvivesTheRoundTripAtTheProductDimension() throws IOException {
        Path path = temp.resolve("big.bin");
        int rows = 100;
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(path, 512)) {
            for (int r = 0; r < rows; r++) {
                float[] values = new float[512];
                values[r] = r + 1;
                values[511] = -r;
                writer.append(r, r % 2, r * 5_000L, values);
            }
        }

        assertThat(Files.size(path)).isEqualTo(20L + rows * 2_065L);
        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            for (int r = 0; r < rows; r++) {
                assertThat(reader.next()).isTrue();
                float[] values = reader.vector();
                assertThat(reader.mediaFileId()).isEqualTo(r);
                assertThat(reader.kind()).isEqualTo(r % 2);
                assertThat(reader.offsetMs()).isEqualTo(r * 5_000L);
                assertThat(values[r]).isEqualTo(r + 1f);
                assertThat(values[511]).isEqualTo(-r);
            }
            assertThat(reader.next()).isFalse();
        }
    }

    @Test
    void aWriterThatIsClosedWithNoRecordsMakesAnEmptyFileAndASecondCloseDoesNothing() throws IOException {
        Path path = temp.resolve("empty.bin");
        EmbeddingFile.Writer writer = EmbeddingFile.create(path, 2);
        writer.close();
        writer.close();

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThat(reader.rows()).isZero();
            assertThat(reader.next()).isFalse();
        }
    }

    @Test
    void theVectorIsACopyTheCallerCannotUseToChangeTheCursor() throws IOException {
        Path path = write(file("CFEMB001", 1, 1, record(1, 0, 0, 4f)));
        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            reader.next();
            reader.vector()[0] = 99f;

            assertThat(reader.vector()).containsExactly(4f);
        }
    }

    @Test
    void aBadAppendIsRefusedAndWritesNothingSoTheFileStaysReadable() throws IOException {
        Path path = temp.resolve("w.bin");
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(path, 2)) {
            writer.append(1, 0, 0, new float[] {1f, 2f});
            assertThatThrownBy(() -> writer.append(2, 2, 0, new float[] {1f, 2f}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("kind must be 0 or 1: 2");
            assertThatThrownBy(() -> writer.append(2, -1, 0, new float[] {1f, 2f}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("kind must be 0 or 1: -1");
            assertThatThrownBy(() -> writer.append(2, 0, 0, new float[] {1f}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the embedding has 1 values, the file takes 2");
            assertThatThrownBy(() -> writer.append(2, 0, 0, new float[] {1f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the embedding holds a NaN or infinite value");
            assertThatThrownBy(() -> writer.append(2, 0, 0, new float[] {Float.POSITIVE_INFINITY, 1f}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the embedding holds a NaN or infinite value");
            assertThat(writer.rows()).isEqualTo(1);
        }

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThat(reader.rows()).isEqualTo(1);
        }
    }

    @Test
    void appendingToAClosedWriterIsRefused() throws IOException {
        EmbeddingFile.Writer writer = EmbeddingFile.create(temp.resolve("c.bin"), 1);
        writer.close();

        assertThatThrownBy(() -> writer.append(1, 0, 0, new float[] {1f}))
            .isInstanceOf(IllegalStateException.class).hasMessage("the writer is closed");
    }

    @Test
    void aDimensionBelowOneIsRefusedByTheWriter() {
        assertThatThrownBy(() -> EmbeddingFile.create(temp.resolve("d.bin"), 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("dimension must be at least 1: 0");
        assertThat(temp.resolve("d.bin")).doesNotExist();
    }

    @Test
    void aWriterNeverOverwritesAnExistingFile() throws IOException {
        Path path = write(new byte[] {1});

        assertThatThrownBy(() -> EmbeddingFile.create(path, 1)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(path)).containsExactly(1);
    }

    @Test
    void aWrongMagicIsRefused() throws IOException {
        refused(file("CFEMB002", 1, 0, new byte[0]), "the file does not start with CFEMB001");
    }

    @Test
    void aFileShorterThanTheHeaderIsRefused() throws IOException {
        refused(new byte[] {'C', 'F', 'E'}, "the file is too short for a header: 3 bytes");
    }

    @Test
    void aDimensionBelowOneIsRefusedByTheReader() throws IOException {
        refused(file("CFEMB001", 0, 0, new byte[0]), "dimension must be at least 1: 0");
        refused(file("CFEMB001", -4, 0, new byte[0]), "dimension must be at least 1: -4");
    }

    @Test
    void aNegativeRowCountIsRefused() throws IOException {
        refused(file("CFEMB001", 1, -1, new byte[0]), "rows must not be negative: -1");
    }

    @Test
    void aTruncatedFileIsRefusedWithItsLengthAndWhatTheHeaderNeeds() throws IOException {
        byte[] body = record(1, 0, 0, 1f, 2f);
        byte[] truncated = new byte[body.length - 1];
        System.arraycopy(body, 0, truncated, 0, truncated.length);

        refused(file("CFEMB001", 2, 1, truncated), "the file is 44 bytes, the header (dimension 2, rows 1) needs 45");
    }

    @Test
    void aFileWithATrailingByteIsRefused() throws IOException {
        byte[] body = record(1, 0, 0, 1f, 2f);
        byte[] longer = new byte[body.length + 1];
        System.arraycopy(body, 0, longer, 0, body.length);

        refused(file("CFEMB001", 2, 1, longer), "the file is 46 bytes, the header (dimension 2, rows 1) needs 45");
    }

    @Test
    void aRowCountLargerThanTheFileIsRefused() throws IOException {
        refused(file("CFEMB001", 1, 2, record(1, 0, 0, 1f)),
            "the file is 41 bytes, the header (dimension 1, rows 2) needs 62");
    }

    @Test
    void aHeaderWhoseSizesOverflowIsRefusedNotWrappedAround() throws IOException {
        refused(file("CFEMB001", 1, Long.MAX_VALUE, new byte[0]),
            "the header's dimension 1 and rows 9223372036854775807 are larger than any file");
        refused(file("CFEMB001", EmbeddingFile.MAX_DIMENSION, Long.MAX_VALUE / 1_000_000, new byte[0]),
            "the header's dimension 262139 and rows 9223372036854 are larger than any file");
    }

    @Test
    void aDimensionAboveTheLimitIsRefusedBeforeAnythingIsAllocatedFromIt() throws IOException {
        refused(file("CFEMB001", EmbeddingFile.MAX_DIMENSION + 1, 0, new byte[0]),
            "dimension 262140 makes a record of 1048577 bytes, more than 1048576");
        refused(file("CFEMB001", 10_000_000, 0, new byte[0]),
            "dimension 10000000 makes a record of 40000017 bytes, more than 1048576");
        refused(file("CFEMB001", Integer.MAX_VALUE, 1, new byte[0]),
            "dimension 2147483647 makes a record of 8589934605 bytes, more than 1048576");
    }

    @Test
    void theLargestAllowedDimensionStillOpens() throws IOException {
        Path path = write(file("CFEMB001", EmbeddingFile.MAX_DIMENSION, 0, new byte[0]));

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThat(reader.dimension()).isEqualTo(EmbeddingFile.MAX_DIMENSION);
            assertThat(reader.next()).isFalse();
        }
    }

    @Test
    void aKindOtherThanZeroOrOneIsRefusedWhenTheCursorReachesIt() throws IOException {
        byte[] body = new byte[2 * 21];
        System.arraycopy(record(1, 0, 0, 1f), 0, body, 0, 21);
        System.arraycopy(record(2, 2, 0, 1f), 0, body, 21, 21);
        Path path = write(file("CFEMB001", 1, 2, body));

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThat(reader.next()).isTrue();
            assertThatThrownBy(reader::next).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("record 2 has kind 2");
        }
    }

    @Test
    void aNegativeKindByteIsRefused() throws IOException {
        Path path = write(file("CFEMB001", 1, 1, record(1, -1, 0, 1f)));

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(path)) {
            assertThatThrownBy(reader::next).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("record 1 has kind -1");
        }
    }

    @Test
    void aNaNOrInfiniteFloatIsRefusedWhenTheCursorReachesIt() throws IOException {
        Path nan = write(file("CFEMB001", 2, 1, record(1, 0, 0, 1f, Float.NaN)));
        Path infinite = write(file("CFEMB001", 2, 1, record(1, 1, 0, Float.NEGATIVE_INFINITY, 1f)));

        try (EmbeddingFile.Reader reader = EmbeddingFile.open(nan)) {
            assertThatThrownBy(reader::next).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("record 1 holds a NaN or infinite value");
        }
        try (EmbeddingFile.Reader reader = EmbeddingFile.open(infinite)) {
            assertThatThrownBy(reader::next).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("record 1 holds a NaN or infinite value");
        }
    }

    @Test
    void aClosedReaderCannotBeReadFrom() throws IOException {
        Path path = write(file("CFEMB001", 1, 1, record(1, 0, 0, 4f)));
        EmbeddingFile.Reader reader = EmbeddingFile.open(path);
        reader.close();

        assertThatThrownBy(reader::next).isInstanceOf(IOException.class);
    }

    /** Delegates to a real stream but fails every read and skip with {@code failure}, and records its close. */
    private static final class FailingStream extends FilterInputStream {

        private final RuntimeException runtime;
        private final IOException failure;
        private final IOException closeFailure;
        private int closes;

        FailingStream(InputStream real, IOException failure, RuntimeException runtime, IOException closeFailure) {
            super(real);
            this.failure = failure;
            this.runtime = runtime;
            this.closeFailure = closeFailure;
        }

        private void fail() throws IOException {
            if (runtime != null) {
                throw runtime;
            }
            throw failure;
        }

        @Override
        public int read() throws IOException {
            fail();
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            fail();
            return -1;
        }

        @Override
        public long skip(long n) throws IOException {
            fail();
            return 0;
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    /** Opens the header's stream for real and the records' stream as {@code second}. */
    private static EmbeddingFile.StreamOpener headerThen(FailingStream[] second,
                                                         Function<InputStream, FailingStream> make) {
        AtomicInteger opens = new AtomicInteger();
        return path -> {
            InputStream real = Files.newInputStream(path);
            if (opens.incrementAndGet() == 1) {
                return real;
            }
            second[0] = make.apply(real);
            return second[0];
        };
    }

    @Test
    void theRecordsStreamIsClosedAndTheFailureRethrownWhenSkippingTheHeaderFails() throws IOException {
        Path path = write(file("CFEMB001", 1, 0, new byte[0]));
        FailingStream[] second = new FailingStream[1];
        IOException boom = new IOException("the disk went away");

        assertThatThrownBy(() -> EmbeddingFile.open(path,
            headerThen(second, real -> new FailingStream(real, boom, null, null))))
            .isSameAs(boom).hasMessage("the disk went away").hasNoSuppressedExceptions();

        assertThat(second[0].closes).isEqualTo(1);
    }

    @Test
    void aRuntimeFailureWhileSkippingAlsoClosesTheStream() throws IOException {
        Path path = write(file("CFEMB001", 1, 0, new byte[0]));
        FailingStream[] second = new FailingStream[1];
        IllegalStateException boom = new IllegalStateException("not a disk problem");

        assertThatThrownBy(() -> EmbeddingFile.open(path,
            headerThen(second, real -> new FailingStream(real, null, boom, null))))
            .isSameAs(boom).hasMessage("not a disk problem");

        assertThat(second[0].closes).isEqualTo(1);
    }

    @Test
    void aFailureToCloseIsAddedAsSuppressedToTheOriginalFailure() throws IOException {
        Path path = write(file("CFEMB001", 1, 0, new byte[0]));
        FailingStream[] second = new FailingStream[1];
        IOException boom = new IOException("read failed");
        IOException closing = new IOException("close failed");

        assertThatThrownBy(() -> EmbeddingFile.open(path,
            headerThen(second, real -> new FailingStream(real, boom, null, closing))))
            .isSameAs(boom).hasMessage("read failed").hasSuppressedException(closing);

        assertThat(second[0].closes).isEqualTo(1);
    }

    @Test
    void aBadHeaderIsRefusedBeforeTheRecordsStreamIsOpened() throws IOException {
        Path path = write(file("NOTMAGIC", 1, 0, new byte[0]));
        AtomicInteger opens = new AtomicInteger();

        assertThatThrownBy(() -> EmbeddingFile.open(path, p -> {
            opens.incrementAndGet();
            return Files.newInputStream(p);
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("the file does not start with CFEMB001");

        assertThat(opens).hasValue(1);
    }

    @Test
    void aMissingFileIsAnIoFailure() {
        assertThatThrownBy(() -> EmbeddingFile.open(temp.resolve("nope.bin"))).isInstanceOf(IOException.class);
    }

    @Test
    void aRecordIsSeventeenBytesPlusFourPerFloat() {
        assertThat(EmbeddingFile.recordBytes(512)).isEqualTo(2_065);
        assertThat(EmbeddingFile.recordBytes(1)).isEqualTo(21);
    }
}
