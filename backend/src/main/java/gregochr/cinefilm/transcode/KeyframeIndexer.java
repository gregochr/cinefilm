package gregochr.cinefilm.transcode;

import gregochr.cinefilm.library.LineStreamRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Finds a file's video keyframe times: from the MP4 sample tables when it can (a few small reads), otherwise by
 * asking {@code ffprobe} for the video packets and keeping the keyframe ones (it reads the whole file, so minutes
 * over SMB). The output is streamed line by line and only keyframe rows are kept, so memory is bounded by the
 * keyframe count; a run that fails, is killed or times out never yields a partial index.
 */
@Component
public class KeyframeIndexer {

    private static final Logger LOG = LoggerFactory.getLogger(KeyframeIndexer.class);

    /** How long the ffprobe fallback may run. */
    static final Duration FFPROBE_TIMEOUT = Duration.ofMinutes(15);

    private final Mp4SampleTableReader reader;
    private final LineStreamRunner runner;
    private final String ffprobe;

    /** Creates the indexer; the binary is {@code cinefilm.tools.ffprobe}. */
    public KeyframeIndexer(Mp4SampleTableReader reader, LineStreamRunner runner,
                           @Value("${cinefilm.tools.ffprobe:ffprobe}") String ffprobe) {
        this.reader = reader;
        this.runner = runner;
        this.ffprobe = ffprobe;
    }

    /**
     * The exact fallback command: the video packets' presentation times and flags, as an argument array, file
     * protocol only, the file last and absolute. Keyframes are the packets whose flags contain {@code K}.
     *
     * @param binary the ffprobe executable
     * @param file the media file
     * @return the argument list
     */
    static List<String> command(String binary, Path file) {
        return List.of(binary, "-v", "error", "-protocol_whitelist", "file", "-select_streams", "v:0",
            "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", file.toAbsolutePath().toString());
    }

    /**
     * The video keyframes of a file and its frame rate.
     *
     * @param file the media file (an absolute path under the library root)
     * @return keyframe times in milliseconds (sorted, without duplicates, never empty); the frame rate is null
     *     when the ffprobe fallback was used
     * @throws KeyframeIndexException when neither the sample tables nor ffprobe give any keyframes
     */
    public KeyframeIndex index(Path file) {
        try {
            return reader.index(file);
        } catch (KeyframeIndexException e) {
            LOG.info("No sample-table keyframes for {} ({}); asking ffprobe", file.getFileName(), e.getMessage());
        }
        return viaFfprobe(file);
    }

    private KeyframeIndex viaFfprobe(Path file) {
        List<Long> times = new ArrayList<>();
        LineStreamRunner.Outcome outcome;
        try {
            outcome = runner.run(command(ffprobe, file), FFPROBE_TIMEOUT, line -> keyframe(line, times));
        } catch (IOException e) {
            throw new KeyframeIndexException("ffprobe could not be run: " + e.getMessage(), e);
        }
        if (!outcome.succeeded()) {
            throw new KeyframeIndexException(outcome.timedOut() ? "ffprobe timed out"
                : "ffprobe exited with status " + outcome.exitCode());
        }
        List<Long> sorted = times.stream().filter(ms -> ms >= 0).sorted().distinct().toList();
        if (sorted.isEmpty()) {
            throw new KeyframeIndexException("ffprobe found no keyframes");
        }
        return new KeyframeIndex(sorted, null);
    }

    /** Keeps the time of a keyframe packet line; every other line is dropped as it arrives. */
    private static void keyframe(String line, List<Long> times) {
        String[] fields = line.strip().split(",");
        if (fields.length < 2 || !fields[1].contains("K")) {
            return;
        }
        try {
            double seconds = Double.parseDouble(fields[0]);
            if (Double.isFinite(seconds)) {
                times.add(Math.round(seconds * 1000));
            }
        } catch (NumberFormatException e) {
            LOG.debug("Ignoring ffprobe line {}", line);
        }
    }
}
