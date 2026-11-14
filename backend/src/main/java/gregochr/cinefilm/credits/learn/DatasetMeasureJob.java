package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One run of the dataset measurement tool: for each film directory under {@code <data>/frames/}, one at a time, it
 * checks the files ({@link FilmCheck}), measures every sound frame ({@link FrameStats}), and writes {@code frames.csv},
 * {@code films.csv} and {@code summary.json} to the output directory, plus stills with {@code --stills}. It reads only
 * the dataset directory and writes only the output directory; no database, no network, never a Spring bean.
 *
 * <p>The columns of {@code films.csv} and {@code frames.csv} are the {@code *_COLUMNS} constants below. Films are
 * ordered by media file id (numeric names first, in numeric order); frames by film, then coarse before dense, then by
 * offset. The means and maxima of the summary are over the {@code ok} films. {@code readSeconds} is an estimate: the
 * {@code writtenAt} of {@code complete.json} minus the earliest modification time of the film's frame files, rounded
 * down, so a directory that was copied without its times, or paused for playback, gives a wrong figure; it is blank
 * when either time is missing or the result would be negative.
 *
 * <p>The paths come from the operator's own command line in a throwaway container.
 */
final class DatasetMeasureJob {

    /** What a run could not do because there was nothing to measure. */
    static final class NoFilmsException extends Exception {
        private static final long serialVersionUID = 1L;

        NoFilmsException(String message) {
            super(message);
        }
    }

    static final List<String> FILMS_COLUMNS = List.of("mediaFileId", "status", "durationMs", "targetStartMs",
        "windowStartMs", "frameWidth", "frameHeight", "areaLeft", "areaTop", "areaWidth", "areaHeight", "coarseCount",
        "denseCount", "missing", "corrupt", "extra", "duplicates",
        "pngBytes", "jpgBytes", "totalBytes", "readSeconds",
        "ffmpegVersion", "javaVersion", "gitCommit", "note");
    static final List<String> FRAMES_COLUMNS = List.of("mediaFileId", "kind", "offsetMs", "fromTargetMs", "greyMean",
        "greyP5", "greyP50", "greyP95", "darkShare", "brightShare", "meanR", "meanG", "meanB", "chroma", "pngBytes",
        "jpgBytes");
    private static final List<String> STILLS_COLUMNS = List.of("file", "offsetMs", "fromTargetMs");
    private static final String NO_DENSE = "no dense frames";
    private static final String NO_DIRECTORY = "no frames directory";

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Clock clock;
    private final GitCommit gitCommit;

    DatasetMeasureJob(Clock clock, GitCommit gitCommit) {
        this.clock = clock;
        this.gitCommit = gitCommit;
    }

    /**
     * What {@code summary.json} holds.
     *
     * @param statusCounts how many films have each status
     * @param meanTotalBytes the mean size of an {@code ok} film's directory; null when no film is {@code ok}
     * @param maxTotalBytes the largest of them
     * @param meanReadSeconds the mean {@code readSeconds} of the {@code ok} films that have one
     * @param maxReadSeconds the largest
     * @param projectFilms the films the projection is for
     * @param projectedBytes the mean size times that many films
     * @param ffmpegVersions the distinct ffmpeg versions seen
     * @param javaVersions the distinct Java versions seen
     * @param gitCommits the distinct commits the frames were written by
     * @param toolGitCommit the commit of this tool
     * @param measuredAt when the run was made (ISO instant)
     */
    record Summary(Map<String, Integer> statusCounts, Double meanTotalBytes, Long maxTotalBytes,
                   Double meanReadSeconds, Long maxReadSeconds, long projectFilms, Long projectedBytes,
                   List<String> ffmpegVersions, List<String> javaVersions, List<String> gitCommits,
                   String toolGitCommit, String measuredAt) {
    }

    /** One film's row of {@code films.csv}, kept (it is small) until the summary is made. */
    private record FilmRow(String id, FilmCheck.Result result, Long readSeconds, String note) {
    }

    /** One frame, measured, before it knows its distance from the target. */
    private record FrameRow(String kind, long offsetMs, FrameStats.Grey grey, FrameStats.Colour colour,
                            long pngBytes, long jpgBytes) {
    }

    /** A directory to measure: its name, and the directory itself, or null when the name has none. */
    private record Entry(String name, Path dir) {
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return what was written: {@code films.csv}, {@code frames.csv}, {@code summary.json}, and the stills directory
     *     when stills were asked for
     * @throws IOException when the dataset cannot be read or the output cannot be written
     * @throws NoFilmsException when {@code frames/} is missing or holds no film directory (nothing is written)
     * @throws IllegalArgumentException when {@code --data} is not a directory or {@code --out} is inside it
     */
    List<Path> run(DatasetMeasureOptions options) throws IOException, NoFilmsException {
        Path data = options.data().toAbsolutePath().normalize();
        Path out = options.out().toAbsolutePath().normalize();
        if (!Files.isDirectory(data)) {
            throw new IllegalArgumentException("--data is not a directory: " + data);
        }
        if (resolved(out).startsWith(data.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside --data: " + out);
        }
        Path framesRoot = data.resolve("frames");
        List<Path> found = directories(framesRoot);
        if (found.isEmpty()) {
            throw new NoFilmsException("no film directories under " + framesRoot);
        }
        List<Entry> entries = entries(found, options);

        Files.createDirectories(out);
        Path stillsDir = options.stills() ? out.resolve("stills") : null;
        Path framesCsv = out.resolve("frames.csv");
        List<FilmRow> films = new ArrayList<>();
        try (BufferedWriter frames = Files.newBufferedWriter(framesCsv)) {
            frames.write(CsvWriter.row(FRAMES_COLUMNS));
            for (Entry entry : entries) {
                films.add(entry.dir() == null ? new FilmRow(entry.name(), FilmCheck.Result.unread(
                    FilmCheck.Status.INCOMPLETE), null, NO_DIRECTORY) : measure(entry, frames, stillsDir));
            }
        }
        Path filmsCsv = out.resolve("films.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(filmsCsv)) {
            writer.write(CsvWriter.row(FILMS_COLUMNS));
            for (FilmRow film : films) {
                writer.write(CsvWriter.row(filmFields(film)));
            }
        }
        Path summary = out.resolve("summary.json");
        Files.write(summary, MAPPER.writeValueAsBytes(summarise(films, options.project())));

        List<Path> written = new ArrayList<>(List.of(filmsCsv, framesCsv, summary));
        if (stillsDir != null) {
            written.add(stillsDir);
        }
        return written;
    }

    /**
     * The path with every symbolic link resolved: the real path of its nearest existing ancestor, then the part that
     * does not exist yet, so that an output directory not made yet is still compared by where it would really be.
     */
    private static Path resolved(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

    // ---- Which films ---------------------------------------------------------------------------------------------

    private static List<Path> directories(Path framesRoot) throws IOException {
        if (!Files.isDirectory(framesRoot)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(framesRoot)) {
            return list.filter(Files::isDirectory).toList();
        }
    }

    /** The directories to measure, plus an entry with no directory for each wanted id that has none, in id order. */
    private static List<Entry> entries(List<Path> found, DatasetMeasureOptions options) {
        List<Entry> entries = new ArrayList<>();
        for (Path dir : found) {
            String name = dir.getFileName().toString();
            if (options.ids().isEmpty() || isWanted(name, options)) {
                entries.add(new Entry(name, dir));
            }
        }
        for (long id : options.ids()) {
            if (found.stream().noneMatch(dir -> dir.getFileName().toString().equals(Long.toString(id)))) {
                entries.add(new Entry(Long.toString(id), null));
            }
        }
        entries.sort(Comparator.comparing((Entry entry) -> numeric(entry.name()) == null)
            .thenComparing(entry -> numeric(entry.name()) == null ? 0L : numeric(entry.name()))
            .thenComparing(Entry::name));
        return entries;
    }

    private static boolean isWanted(String name, DatasetMeasureOptions options) {
        Long id = numeric(name);
        return id != null && options.ids().contains(id) && name.equals(id.toString());
    }

    private static Long numeric(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- One film ------------------------------------------------------------------------------------------------

    private FilmRow measure(Entry entry, BufferedWriter frames, Path stillsDir) throws IOException {
        List<FrameRow> rows = new ArrayList<>();
        FilmCheck.Result result = FilmCheck.check(entry.dir(), frame -> rows.add(new FrameRow(frame.kind(),
            frame.offsetMs(), FrameStats.grey(frame.grey()), FrameStats.colour(frame.colour()), frame.pngBytes(),
            frame.jpgBytes())));
        DatasetComplete complete = result.complete();
        if (complete == null) {
            return new FilmRow(entry.name(), result, null, null);
        }
        rows.sort(Comparator.comparing((FrameRow row) -> !FilmCheck.COARSE.equals(row.kind()))
            .thenComparingLong(FrameRow::offsetMs));
        for (FrameRow row : rows) {
            frames.write(CsvWriter.row(frameFields(entry.name(), row, complete.targetStartMs())));
        }
        List<Long> dense = rows.stream().filter(row -> FilmCheck.DENSE.equals(row.kind()))
            .map(FrameRow::offsetMs).toList();
        String note = null;
        if (dense.isEmpty()) {
            note = NO_DENSE;
        } else if (stillsDir != null) {
            writeStills(entry, stillsDir.resolve(entry.name()), complete, dense);
        }
        return new FilmRow(entry.name(), result, readSeconds(complete, result.earliestModified()), note);
    }

    private static Long readSeconds(DatasetComplete complete, Instant earliest) {
        if (earliest == null || complete.writtenAt() == null) {
            return null;
        }
        try {
            long seconds = Duration.between(earliest, Instant.parse(complete.writtenAt())).getSeconds();
            return seconds < 0 ? null : seconds;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---- Stills --------------------------------------------------------------------------------------------------

    private static void writeStills(Entry entry, Path target, DatasetComplete complete, List<Long> dense)
        throws IOException {
        Files.createDirectories(target);
        List<StillsPicker.Pick> picks = StillsPicker.pick(dense, complete.targetStartMs());
        List<GreyFrame> tiles = new ArrayList<>();
        StringBuilder index = new StringBuilder(CsvWriter.row(STILLS_COLUMNS));
        for (StillsPicker.Pick pick : picks) {
            Path denseDir = entry.dir().resolve(FilmCheck.DENSE);
            byte[] png = Files.readAllBytes(denseDir.resolve(pick.offsetMs() + ".png"));
            byte[] jpg = Files.readAllBytes(denseDir.resolve(pick.offsetMs() + ".jpg"));
            Files.write(target.resolve(pick.baseName() + ".jpg"), jpg);
            Files.write(target.resolve(pick.baseName() + ".png"), png);
            tiles.add(FramePrep.decodePng(png));
            for (String extension : List.of(".jpg", ".png")) {
                index.append(CsvWriter.row(List.of(pick.baseName() + extension, Long.toString(pick.offsetMs()),
                    Long.toString(pick.fromTargetMs()))));
            }
        }
        Files.write(target.resolve("sheet.png"), ContactSheet.png(tiles));
        Files.writeString(target.resolve("stills.csv"), index.toString());
    }

    // ---- Rows ----------------------------------------------------------------------------------------------------

    private static List<String> frameFields(String id, FrameRow row, long targetStartMs) {
        List<String> fields = new ArrayList<>(List.of(id, row.kind(), Long.toString(row.offsetMs()),
            Long.toString(row.offsetMs() - targetStartMs)));
        fields.add(fixed(row.grey().mean(), 2));
        fields.add(Integer.toString(row.grey().p5()));
        fields.add(Integer.toString(row.grey().p50()));
        fields.add(Integer.toString(row.grey().p95()));
        fields.add(fixed(row.grey().darkShare(), 4));
        fields.add(fixed(row.grey().brightShare(), 4));
        fields.add(fixed(row.colour().meanR(), 2));
        fields.add(fixed(row.colour().meanG(), 2));
        fields.add(fixed(row.colour().meanB(), 2));
        fields.add(fixed(row.colour().chroma(), 2));
        fields.add(Long.toString(row.pngBytes()));
        fields.add(Long.toString(row.jpgBytes()));
        return fields;
    }

    private static List<String> filmFields(FilmRow film) {
        FilmCheck.Result result = film.result();
        DatasetComplete complete = result.complete();
        List<String> fields = new ArrayList<>();
        fields.add(film.id());
        fields.add(result.status().name().toLowerCase(Locale.ROOT));
        if (complete == null) {
            for (int i = FILMS_COLUMNS.indexOf("durationMs"); i < FILMS_COLUMNS.size() - 1; i++) {
                fields.add(null);
            }
            fields.add(film.note());
            return fields;
        }
        fields.add(Long.toString(complete.durationMs()));
        fields.add(Long.toString(complete.targetStartMs()));
        fields.add(Long.toString(complete.window().startMs()));
        fields.add(Integer.toString(complete.frame().width()));
        fields.add(Integer.toString(complete.frame().height()));
        fields.add(Integer.toString(complete.activeArea().left()));
        fields.add(Integer.toString(complete.activeArea().top()));
        fields.add(Integer.toString(complete.activeArea().width()));
        fields.add(Integer.toString(complete.activeArea().height()));
        fields.add(Integer.toString(complete.coarse().size()));
        fields.add(Integer.toString(complete.dense().size()));
        fields.add(Integer.toString(result.missing()));
        fields.add(Integer.toString(result.corrupt()));
        fields.add(Integer.toString(result.extra()));
        fields.add(Integer.toString(result.duplicates()));
        fields.add(Long.toString(result.pngBytes()));
        fields.add(Long.toString(result.jpgBytes()));
        fields.add(Long.toString(result.totalBytes()));
        fields.add(film.readSeconds() == null ? null : film.readSeconds().toString());
        fields.add(complete.ffmpegVersion());
        fields.add(complete.javaVersion());
        fields.add(complete.gitCommit());
        fields.add(film.note());
        return fields;
    }

    private static String fixed(double value, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }

    // ---- Summary -------------------------------------------------------------------------------------------------

    private Summary summarise(List<FilmRow> films, long project) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FilmCheck.Status status : List.of(FilmCheck.Status.OK, FilmCheck.Status.INCOMPLETE,
            FilmCheck.Status.MISMATCHED, FilmCheck.Status.DAMAGED)) {
            counts.put(status.name().toLowerCase(Locale.ROOT), (int) films.stream()
                .filter(film -> film.result().status() == status).count());
        }
        List<FilmRow> ok = films.stream().filter(film -> film.result().status() == FilmCheck.Status.OK).toList();
        Double meanTotal = mean(ok.stream().map(film -> film.result().totalBytes()).toList());
        Long maxTotal = max(ok.stream().map(film -> film.result().totalBytes()).toList());
        List<Long> seconds = ok.stream().map(FilmRow::readSeconds).filter(java.util.Objects::nonNull).toList();
        TreeSet<String> ffmpeg = new TreeSet<>();
        TreeSet<String> java = new TreeSet<>();
        TreeSet<String> commits = new TreeSet<>();
        for (FilmRow film : films) {
            Optional.ofNullable(film.result().complete()).ifPresent(complete -> {
                Optional.ofNullable(complete.ffmpegVersion()).ifPresent(ffmpeg::add);
                Optional.ofNullable(complete.javaVersion()).ifPresent(java::add);
                Optional.ofNullable(complete.gitCommit()).ifPresent(commits::add);
            });
        }
        return new Summary(counts, meanTotal, maxTotal, mean(seconds), max(seconds), project,
            meanTotal == null ? null : Math.round(meanTotal * project), List.copyOf(ffmpeg), List.copyOf(java),
            List.copyOf(commits), gitCommit.commit(), clock.instant().toString());
    }

    private static Double mean(List<Long> values) {
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum() / (double) values.size();
    }

    private static Long max(List<Long> values) {
        return values.stream().max(Comparator.naturalOrder()).orElse(null);
    }
}
