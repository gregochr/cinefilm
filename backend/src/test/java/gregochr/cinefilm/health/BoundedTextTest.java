package gregochr.cinefilm.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class BoundedTextTest {

    /** Hands out {@code source} at most {@code piece} characters per read and counts what was asked for. */
    private static final class CountingReader extends Reader {
        private final String source;
        private final int piece;
        private int position;
        private long asked;

        CountingReader(String source, int piece) {
            this.source = source;
            this.piece = piece;
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            asked += length;
            if (position >= source.length()) {
                return -1;
            }
            int count = Math.min(Math.min(piece, length), source.length() - position);
            source.getChars(position, position + count, buffer, offset);
            position += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void textOfExactlyTheLimitIsReturnedWhole() throws IOException {
        String text = "a".repeat(10_000);

        assertThat(BoundedText.read(new StringReader(text), 10_000)).isEqualTo(text);
    }

    @Test
    void textOneOverTheLimitIsNull() throws IOException {
        assertThat(BoundedText.read(new StringReader("a".repeat(10_001)), 10_000)).isNull();
    }

    @Test
    void textBelowTheLimitIsReturnedWhole() throws IOException {
        assertThat(BoundedText.read(new StringReader("hello"), 10_000)).isEqualTo("hello");
    }

    @Test
    void emptyTextIsTheEmptyString() throws IOException {
        assertThat(BoundedText.read(new StringReader(""), 10)).isEmpty();
    }

    @Test
    void aLimitOfZeroAcceptsOnlyEmptyText() throws IOException {
        assertThat(BoundedText.read(new StringReader(""), 0)).isEmpty();
        assertThat(BoundedText.read(new StringReader("a"), 0)).isNull();
    }

    @Test
    void aReaderThatReturnsSmallPiecesIsReadToTheEnd() throws IOException {
        String text = "0123456789".repeat(1_000);

        assertThat(BoundedText.read(new CountingReader(text, 7), 10_000)).isEqualTo(text);
    }

    @Test
    void smallPiecesOverTheLimitAreStillNull() throws IOException {
        assertThat(BoundedText.read(new CountingReader("x".repeat(10_001), 3), 10_000)).isNull();
    }

    @Test
    void aBodyFarOverTheLimitIsAbandonedAfterTheLimitPlusOneCharactersWereAskedFor() throws IOException {
        CountingReader reader = new CountingReader("y".repeat(5_000_000), 100_000);

        assertThat(BoundedText.read(reader, 20_000)).isNull();
        assertThat(reader.asked).isEqualTo(20_001);
    }

    @Test
    void aBodyOfExactlyTheLimitAsksForNoMoreThanTheLimitPlusOne() throws IOException {
        CountingReader reader = new CountingReader("y".repeat(20_000), 100_000);

        assertThat(BoundedText.read(reader, 20_000)).hasSize(20_000);
        assertThat(reader.asked).isLessThanOrEqualTo(20_001 + 1);
    }

    @Test
    void anIoFailureFromTheReaderPropagates() {
        Reader failing = new Reader() {
            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("connection reset");
            }

            @Override
            public void close() {
            }
        };

        assertThatThrownBy(() -> BoundedText.read(failing, 10))
            .isExactlyInstanceOf(IOException.class)
            .hasMessage("connection reset");
    }
}
