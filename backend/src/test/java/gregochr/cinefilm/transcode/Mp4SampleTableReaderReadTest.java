package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The positional read loop under {@link Mp4SampleTableReader}: partial reads, end of file, channel position. */
class Mp4SampleTableReaderReadTest {

    @TempDir
    Path dir;

    private static final byte[] DATA = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    /** A channel over a byte array whose positional read returns at most {@code chunk} bytes, like a slow device. */
    private static final class ChunkedChannel extends FileChannel {
        private final int chunk;
        private int calls;
        private long lastPosition = -1;

        ChunkedChannel(int chunk) {
            this.chunk = chunk;
        }

        @Override
        public int read(ByteBuffer dst, long position) {
            calls++;
            lastPosition = position;
            if (position >= DATA.length) {
                return -1;
            }
            int n = (int) Math.min(Math.min(chunk, dst.remaining()), DATA.length - position);
            dst.put(DATA, (int) position, n);
            return n;
        }

        @Override
        public long position() {
            throw new AssertionError("the channel position must not be used");
        }

        @Override
        public FileChannel position(long newPosition) {
            throw new AssertionError("the channel position must not be moved");
        }

        @Override
        public int read(ByteBuffer dst) {
            throw new AssertionError("relative read must not be used");
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long size() {
            return DATA.length;
        }

        @Override
        public FileChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void force(boolean metaData) {
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int write(ByteBuffer src, long position) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void implCloseChannel() {
        }
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    @Test
    void partialReadsAreLoopedUntilTheBufferIsFullAtTheRightOffsets() throws IOException {
        ChunkedChannel channel = new ChunkedChannel(3);

        ByteBuffer read = Mp4SampleTableReader.read(channel, 2, 7);

        assertThat(bytes(read)).containsExactly(2, 3, 4, 5, 6, 7, 8);
        assertThat(channel.calls).isEqualTo(3);
        assertThat(channel.lastPosition).isEqualTo(8);
    }

    @Test
    void endOfFileGivesAShorterBufferWhoseLimitIsTheBytesRead() throws IOException {
        ChunkedChannel channel = new ChunkedChannel(4);

        ByteBuffer read = Mp4SampleTableReader.read(channel, 6, 16);

        assertThat(read.position()).isZero();
        assertThat(read.limit()).isEqualTo(4);
        assertThat(bytes(read)).containsExactly(6, 7, 8, 9);
    }

    @Test
    void readingEntirelyPastTheEndGivesAnEmptyBuffer() throws IOException {
        assertThat(Mp4SampleTableReader.read(new ChunkedChannel(4), 10, 8).limit()).isZero();
    }

    @Test
    void aRealFileChannelKeepsItsPositionAndShortensAtEndOfFile() throws IOException {
        Path file = dir.resolve("data.bin");
        Files.write(file, DATA);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(5);

            ByteBuffer read = Mp4SampleTableReader.read(channel, 7, 100);

            assertThat(bytes(read)).containsExactly(7, 8, 9);
            assertThat(channel.position()).isEqualTo(5);
        }
    }

    @Test
    void aLengthThatDoesNotFitAnIntIsAnArithmeticException() {
        assertThatThrownBy(() -> Mp4SampleTableReader.read(new ChunkedChannel(1), 0, 1L << 33))
            .isInstanceOf(ArithmeticException.class);
    }
}
