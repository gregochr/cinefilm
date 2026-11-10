package gregochr.cinefilm.health;

import java.io.IOException;
import java.io.Reader;

/** Reads text with a hard limit, so a huge request body is never held in memory. */
public final class BoundedText {

    private static final int CHUNK = 4_096;

    private BoundedText() {
    }

    /**
     * Reads the whole text when it has at most {@code maxChars} characters, and returns null as soon as it has seen
     * more than that. It asks the reader for at most {@code maxChars + 1} characters in all, so memory use is bounded
     * by the limit, never by the size of the body. An {@link IOException} from the reader propagates.
     */
    public static String read(Reader reader, int maxChars) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] chunk = new char[CHUNK];
        while (true) {
            int room = maxChars + 1 - text.length();
            int count = reader.read(chunk, 0, Math.min(CHUNK, room));
            if (count < 0) {
                return text.toString();
            }
            text.append(chunk, 0, count);
            if (text.length() > maxChars) {
                return null;
            }
        }
    }
}
