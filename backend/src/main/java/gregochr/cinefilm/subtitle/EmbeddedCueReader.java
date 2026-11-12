package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LineStreamRunner;
import gregochr.cinefilm.subtitle.EmbeddedStreams.Choice;
import gregochr.cinefilm.subtitle.EmbeddedStreams.Stream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the cue start times of the full English subtitle track inside a media file, with {@code ffprobe} (no OCR: a
 * picture subtitle's packets carry its times).
 *
 * <p>Two runs, both an argument list with {@code -protocol_whitelist file} and the file last and absolute: a quick one
 * that lists the file's subtitle streams, and one pass over the file's subtitle packets (it demuxes the whole file, so
 * minutes over SMB: a {@value #PACKETS_TIMEOUT_MINUTES} minute timeout, the process killed when it expires, run under
 * {@code nice}). One pass reads every candidate track at once, so the choice between them ({@link EmbeddedStreams})
 * can use how many cues each has. A run that fails, is killed or times out gives no result: it throws, so nothing
 * partial is ever stored.
 */
@Component
public class EmbeddedCueReader {

    /** How long the packet pass may run, in minutes. */
    static final int PACKETS_TIMEOUT_MINUTES = 20;
    /** How long the stream listing may run. */
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(60);
    /** How long the packet pass may run. */
    static final Duration PACKETS_TIMEOUT = Duration.ofMinutes(PACKETS_TIMEOUT_MINUTES);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A read that could not be completed: the tool is missing, failed or timed out. Nothing is stored for it. */
    public static class ReadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with a plain message. */
        public ReadException(String message) {
            super(message);
        }

        /** Creates the exception with a plain message and its cause. */
        public ReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * What a read found.
     *
     * @param status {@link EmbeddedCueStarts.Status#USABLE} or {@link EmbeddedCueStarts.Status#NO_REFERENCE}
     * @param subtitleStreamCount how many subtitle streams the file has
     * @param streamIndex the chosen stream, or null
     * @param codec the chosen stream's codec, or null
     * @param startsMs the chosen stream's cue starts (empty when there is none)
     * @param reason why there is no usable track, or null
     */
    public record Reading(EmbeddedCueStarts.Status status, int subtitleStreamCount, Integer streamIndex, String codec,
                          List<Long> startsMs, String reason) {
    }

    private final CommandRunner runner;
    private final LineStreamRunner lineRunner;
    private final String ffprobe;
    private final int niceness;

    /**
     * Creates the reader; the binary is {@code cinefilm.tools.ffprobe}, and the niceness is
     * {@code cinefilm.transcode.nice} as for the encoder (0 or less runs without {@code nice}).
     */
    public EmbeddedCueReader(CommandRunner runner, LineStreamRunner lineRunner,
                             @Value("${cinefilm.tools.ffprobe:ffprobe}") String ffprobe,
                             @Value("${cinefilm.transcode.nice:10}") int niceness) {
        this.runner = runner;
        this.lineRunner = lineRunner;
        this.ffprobe = ffprobe;
        this.niceness = niceness;
    }

    /**
     * The command that lists a file's subtitle streams as JSON.
     *
     * @param binary the ffprobe executable
     * @param file the media file (absolute)
     * @return the argument list
     */
    static List<String> listCommand(String binary, Path file) {
        return List.of(binary, "-v", "error", "-protocol_whitelist", "file", "-select_streams", "s", "-show_streams",
            "-print_format", "json", file.toAbsolutePath().toString());
    }

    /**
     * The command that prints every subtitle packet's stream, time and size, one {@code key=value|...} line each
     * (key=value, so the output does not depend on the order ffprobe prints its fields in).
     *
     * @param niceness the niceness to run under; 0 or less runs without {@code nice}
     * @param binary the ffprobe executable
     * @param file the media file (absolute)
     * @return the argument list
     */
    static List<String> packetsCommand(int niceness, String binary, Path file) {
        List<String> command = new ArrayList<>();
        if (niceness > 0) {
            command.addAll(List.of("nice", "-n", String.valueOf(niceness)));
        }
        command.addAll(List.of(binary, "-v", "error", "-protocol_whitelist", "file", "-select_streams", "s",
            "-show_entries", "packet=stream_index,pts_time,duration_time,size", "-print_format", "compact=p=0",
            file.toAbsolutePath().toString()));
        return List.copyOf(command);
    }

    /**
     * Reads a file's full English subtitle track.
     *
     * @param file the media file (an absolute path under the library root)
     * @return what was found: the cue starts, or the reason there is no usable track
     * @throws ReadException when ffprobe is missing, fails or times out
     */
    public Reading read(Path file) {
        List<Stream> streams = streams(file);
        List<Stream> candidates = EmbeddedStreams.candidates(streams);
        Map<Integer, List<Long>> starts = candidates.isEmpty() ? Map.of() : packets(file, candidates);
        Choice choice = EmbeddedStreams.choose(streams, starts);
        if (choice.stream() == null) {
            return new Reading(EmbeddedCueStarts.Status.NO_REFERENCE, streams.size(), null, null, List.of(),
                choice.reason());
        }
        return new Reading(EmbeddedCueStarts.Status.USABLE, streams.size(), choice.stream().index(),
            choice.stream().codec(), choice.starts(), null);
    }

    private List<Stream> streams(Path file) {
        CommandRunner.Result result;
        try {
            result = runner.run(listCommand(ffprobe, file), LIST_TIMEOUT);
        } catch (IOException e) {
            throw new ReadException("ffprobe could not be run: " + e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new ReadException(result.timedOut() ? "ffprobe timed out listing the subtitle tracks"
                : "ffprobe exited with status " + result.exitCode() + " listing the subtitle tracks");
        }
        JsonNode root;
        try {
            root = JSON.readTree(result.stdout());
        } catch (JacksonException e) {
            throw new ReadException("ffprobe's stream list is not JSON", e);
        }
        List<Stream> streams = new ArrayList<>();
        for (JsonNode stream : root.path("streams")) {
            if (!"subtitle".equals(text(stream, "codec_type")) || !stream.path("index").isNumber()) {
                continue;
            }
            JsonNode disposition = stream.path("disposition");
            JsonNode tags = stream.path("tags");
            streams.add(new Stream(stream.path("index").asInt(), text(stream, "codec_name"), text(tags, "language"),
                disposition.path("forced").asInt(0) == 1, disposition.path("default").asInt(0) == 1,
                text(tags, "title")));
        }
        return streams;
    }

    private Map<Integer, List<Long>> packets(Path file, List<Stream> candidates) {
        Map<Integer, String> codecs = new LinkedHashMap<>();
        candidates.forEach(s -> codecs.put(s.index(), s.codec()));
        SubtitlePackets packets = new SubtitlePackets(codecs);
        LineStreamRunner.Outcome outcome;
        try {
            outcome = lineRunner.run(packetsCommand(niceness, ffprobe, file), PACKETS_TIMEOUT, packets::accept);
        } catch (IOException e) {
            throw new ReadException("ffprobe could not be run: " + e.getMessage(), e);
        }
        if (!outcome.succeeded()) {
            throw new ReadException(outcome.timedOut() ? "ffprobe timed out reading the subtitle packets"
                : "ffprobe exited with status " + outcome.exitCode() + " reading the subtitle packets");
        }
        return packets.starts();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
