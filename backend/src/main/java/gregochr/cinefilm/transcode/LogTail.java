package gregochr.cinefilm.transcode;

import java.util.ArrayDeque;
import java.util.Deque;

/** The last few lines of a process's standard error, bounded in lines and in line length. Thread-safe. */
final class LogTail {

    /** Lines kept. */
    static final int MAX_LINES = 40;

    /** Characters kept per line. */
    static final int MAX_LINE_LENGTH = 500;

    private final Deque<String> lines = new ArrayDeque<>();

    /** Adds a line, dropping the oldest beyond {@link #MAX_LINES} and cutting it at {@link #MAX_LINE_LENGTH}. */
    synchronized void add(String line) {
        if (lines.size() == MAX_LINES) {
            lines.removeFirst();
        }
        lines.addLast(line.substring(0, Math.min(line.length(), MAX_LINE_LENGTH)));
    }

    /** The kept lines, oldest first, joined with newlines. */
    synchronized String text() {
        return String.join("\n", lines);
    }
}
