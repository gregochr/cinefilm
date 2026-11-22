package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.GreyFrame;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One run of the sheet tool (G1 of {@code docs/engineering/credits-3c-auto-plan.md}): for each candidate it reads the
 * nine stills of the gate from the candidate's file and writes one {@link ContactSheet} named by edition id, plus
 * {@code list.csv}, into a directory of its own, {@code <out>/auto-check-<yyyyMMdd'T'HHmmss'Z'>/} (made with
 * {@code createDirectory}, so a run never overwrites an earlier one). With {@code --full} it writes the one still
 * instead. It reads only the candidates file and the media files and writes only that directory: no database, no
 * network, never a Spring bean. No title and no path is written anywhere.
 *
 * <p>A tile that cannot be read from the file is black: one before the last part's start ({@value #BEFORE_PART}) or
 * past the file's end ({@value #PAST_END}). The list says which. A file that cannot be read at all is listed with the
 * reason and skipped.
 */
final class AutoCheckJob {

    /** The reason of a tile whose time falls before the last part's start. */
    static final String BEFORE_PART = "before-part";
    /** The reason of tile 7, 8 or 9 when it and every later tile are empty: the file ends before them. */
    static final String PAST_END = "past-end";
    /** The reason of any other tile with no picture: the sheet is incomplete, and the list's note says so. */
    static final String NO_FRAME = "no-frame";
    private static final int FIRST_END_TILE = 7;
    static final List<String> LIST_COLUMNS = List.of("editionId", "kind", "owner", "startMs", "markerMs", "sheet",
        "blackTiles", "note");

    /** Offsets from the start, in milliseconds, of tiles 1 to 3. */
    private static final long[] AROUND_START_MS = {-10_000, 0, 10_000};
    /** Offsets from the marker, in milliseconds, of tiles 5 to 9 (tile 4 is the marker itself). */
    private static final long[] AFTER_MARKER_MS = {0, 15_000, 30_000, 60_000, 120_000, 240_000};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final StillSource source;

    AutoCheckJob(Clock clock, StillSource source) {
        this.clock = clock;
        this.source = source;
    }

    /**
     * The nine tiles' times in edition time: the start minus 10 s, the start, the start plus 10 s, the marker, and the
     * marker plus 15, 30, 60, 120 and 240 s.
     */
    static long[] editionTimes(long startMs, long markerMs) {
        long[] times = new long[AutoCheckOptions.TILES];
        for (int i = 0; i < AROUND_START_MS.length; i++) {
            times[i] = startMs + AROUND_START_MS[i];
        }
        for (int i = 0; i < AFTER_MARKER_MS.length; i++) {
            times[AROUND_START_MS.length + i] = markerMs + AFTER_MARKER_MS[i];
        }
        return times;
    }

    /** A time in edition time as a time in the last part's file: {@code editionMs - (startMs - offsetMs)}. */
    static long fileTime(AutoCheckCandidate candidate, long editionMs) {
        return editionMs - candidate.fileOrigin();
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return the paths written, the run's directory first
     * @throws IOException when a file cannot be written
     * @throws IllegalArgumentException when the run's directory exists, or {@code --full} names no candidate or a
     *     tile that cannot be read
     * @throws tools.jackson.core.JacksonException when the candidates file is not a JSON list of candidates
     */
    List<Path> run(AutoCheckOptions options) throws IOException {
        List<AutoCheckCandidate> candidates = AutoCheckCandidate.read(options.candidates());
        if (options.full() != null) {
            return full(options, candidates);
        }
        Path runDir = runDirectory(options.out());
        List<Path> written = new ArrayList<>(List.of(runDir));
        Path list = runDir.resolve("list.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(list)) {
            writer.write(CsvWriter.row(LIST_COLUMNS));
            for (AutoCheckCandidate candidate : candidates) {
                writer.write(CsvWriter.row(sheet(candidate, options.media(), runDir, written)));
            }
        }
        written.add(list);
        return written;
    }

    private List<Path> full(AutoCheckOptions options, List<AutoCheckCandidate> candidates) throws IOException {
        AutoCheckOptions.Full full = options.full();
        AutoCheckCandidate candidate = candidates.stream().filter(c -> c.editionId() == full.editionId()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no candidate for edition " + full.editionId()));
        String who = "edition " + full.editionId();
        long fileMs = fileTime(candidate, editionTimes(candidate.startMs(), candidate.markerMs())[full.tile() - 1]);
        Optional<byte[]> png = Optional.empty();
        String black = BEFORE_PART;
        try {
            Path media = locate(candidate, options.media());
            if (fileMs >= 0) {
                black = NO_FRAME;
                try (StillSource.Reading reading = source.open(media)) {
                    png = reading.fullPngAt(fileMs);
                }
            }
        } catch (Unreadable | CreditsFrameReader.ReadException e) {
            throw new IllegalArgumentException(who + ": " + e.getMessage(), e);
        }
        if (png.isEmpty()) {
            throw new IllegalArgumentException(who + " tile " + full.tile() + " is " + black
                + ": there is no still to write");
        }
        Path runDir = runDirectory(options.out());
        Path file = runDir.resolve("edition-" + full.editionId() + "-tile-" + full.tile() + ".png");
        Files.write(file, png.get());
        return List.of(runDir, file);
    }

    /** Writes one candidate's sheet and returns its list row. */
    private List<String> sheet(AutoCheckCandidate candidate, Path media, Path runDir, List<Path> written)
        throws IOException {
        List<String> head = List.of(Long.toString(candidate.editionId()), candidate.kind(),
            Boolean.toString(candidate.owner()), Long.toString(candidate.startMs()),
            Long.toString(candidate.markerMs()));
        Read read;
        try {
            read = read(candidate, media, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9));
        } catch (Unreadable e) {
            return row(head, "", "", e.getMessage());
        }
        if (read.frames().isEmpty()) {
            return row(head, "", "", "no tile could be read");
        }
        GreyFrame first = read.frames().values().iterator().next();
        GreyFrame black = new GreyFrame(first.width(), first.height(), new byte[first.width() * first.height()]);
        List<GreyFrame> tiles = new ArrayList<>();
        for (int tile = 1; tile <= AutoCheckOptions.TILES; tile++) {
            tiles.add(read.frames().getOrDefault(tile, black));
        }
        String name = "edition-" + candidate.editionId() + ".png";
        Path file = runDir.resolve(name);
        Files.write(file, ContactSheet.png(tiles));
        written.add(file);
        StringBuilder blackTiles = new StringBuilder();
        read.black().forEach((tile, reason) -> blackTiles.append(blackTiles.isEmpty() ? "" : " ").append(tile)
            .append(':').append(reason));
        String note = read.black().entrySet().stream().filter(e -> NO_FRAME.equals(e.getValue()))
            .map(e -> "tile " + e.getKey() + " had no picture").collect(Collectors.joining("; "));
        return row(head, name, blackTiles.toString(), note);
    }

    private static List<String> row(List<String> head, String sheet, String blackTiles, String note) {
        List<String> row = new ArrayList<>(head);
        row.addAll(List.of(sheet, blackTiles, note));
        return row;
    }

    /** The candidate's file under the media root. */
    private static Path locate(AutoCheckCandidate candidate, Path media) {
        Path root = media.toAbsolutePath().normalize();
        Path file = root.resolve(candidate.path()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new Unreadable("the file is missing");
        }
        return file;
    }

    /** Reads the wanted tiles (1 to 9) of a candidate. */
    private Read read(AutoCheckCandidate candidate, Path media, List<Integer> wanted) {
        Path file = locate(candidate, media);
        long[] times = editionTimes(candidate.startMs(), candidate.markerMs());
        Map<Integer, GreyFrame> frames = new LinkedHashMap<>();
        Map<Integer, String> black = new TreeMap<>();
        try (StillSource.Reading reading = source.open(file)) {
            for (int tile : wanted) {
                long fileMs = fileTime(candidate, times[tile - 1]);
                if (fileMs < 0) {
                    black.put(tile, BEFORE_PART);
                    continue;
                }
                Optional<GreyFrame> frame = reading.frameAt(fileMs);
                if (frame.isPresent()) {
                    frames.put(tile, frame.get());
                } else {
                    black.put(tile, NO_FRAME);
                }
            }
        } catch (CreditsFrameReader.ReadException e) {
            throw new Unreadable(e.getMessage());
        }
        // Without a duration an empty read is past the end only for tile 7, 8 or 9 with every later tile empty too.
        int last = frames.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        black.replaceAll((tile, reason) -> NO_FRAME.equals(reason) && tile >= FIRST_END_TILE && tile > last
            ? PAST_END : reason);
        return new Read(frames, black);
    }

    private Path runDirectory(Path out) throws IOException {
        Files.createDirectories(out);
        Path runDir = out.resolve("auto-check-" + STAMP.format(clock.instant()));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        return runDir;
    }

    /** What was read: the frames by tile number, and the tiles that are black with the reason. */
    private record Read(Map<Integer, GreyFrame> frames, Map<Integer, String> black) {
    }

    /** A candidate whose file could not be read, with a reason that names no path. */
    private static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unreadable(String reason) {
            super(reason);
        }
    }
}
