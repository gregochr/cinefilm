package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * One run of the credits features tool ({@code docs/engineering/credits-classifier-plan.md}, "{@code FrameLabels} and
 * {@code CreditsFeaturesMain}"): for each development film with no exclusion, one at a time, it checks the directory,
 * then reads it in two passes and writes {@code features.csv}, {@code frame-labels.json} and {@code summary.json} into
 * {@code <out>/features-<yyyyMMdd'T'HHmmss'Z'>/} (made with {@code createDirectory}, so a run never overwrites an
 * earlier one). It reads only the dataset directory and the labels file; no database, no network, never a Spring bean.
 *
 * <p><strong>Only development films are opened.</strong> A record's exclusion is checked before its side (unlike
 * {@link DatasetMeasureJob}, which checks the side first), so an excluded test-side record counts as {@code excluded}.
 * Test, excluded and unlabelled directories are counted and never opened, and no skipped id of any kind is written.
 * Every access to a film directory goes through the {@link FilmSource}.
 *
 * <p>Check, in order: {@code complete.json} is usable ({@code incomplete}); its {@code mediaFileId} is the directory's
 * ({@code mismatched}); its fingerprint is the label's ({@code stale}, with no frame decoded); {@link FilmCheck} finds
 * every file sound and every grey frame {@code frame.height / 2} high ({@code damaged}). Read, for an {@code ok} film
 * only, with every row and count held in memory until the film ends: the coarse frames' features and kinds first (a
 * frame's neighbours are the coarse frames either side, the one neighbour twice at an end), then
 * {@link FrameLabels}, the coarse rows, and the dense rows, all by
 * {@link FeatureRows}. Then {@code complete.json} is fetched again: a film whose bytes changed, whose files vanish or
 * whose frames are rejected during the read is {@code damaged} and contributes no row, record or hash bytes, and
 * the run goes on.
 */
final class CreditsFeaturesJob {

    private static final String COARSE = FilmCheck.COARSE;
    private static final String DENSE = FilmCheck.DENSE;
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final GitCommit gitCommit;
    private final FilmSource source;

    CreditsFeaturesJob(Clock clock, GitCommit gitCommit, FilmSource source) {
        this.clock = clock;
        this.gitCommit = gitCommit;
        this.source = source;
    }

    /**
     * What {@code summary.json} holds.
     *
     * @param featureVersion {@link CreditsFeatures#FEATURE_VERSION}
     * @param toolGitCommit the commit of this tool
     * @param generatedAt when the run was made (ISO instant)
     * @param labelsSha256 the SHA-256 of the labels file, in hex
     * @param datasetSha256 the SHA-256 over the {@code complete.json} bytes of the films read, in ascending id order
     * @param films how many films were read
     * @param rows the rows written, by kind ({@code coarse}, {@code dense}) and label
     * @param openingTypes the films read, by opening type
     * @param noOpeningFrame the films read that have no coarse frame at or after their target
     * @param intervalsLeftOutWhole the credits intervals left out whole as {@code LIT_OPENING}
     * @param omittedDenseFrames the dense frames before the window, not written
     * @param skipped the directories and ids never opened, by reason
     * @param skippedByStatus the development films not read, by status
     */
    record Summary(int featureVersion, String toolGitCommit, String generatedAt, String labelsSha256,
                   String datasetSha256, int films, Map<String, Map<String, Integer>> rows,
                   Map<String, Integer> openingTypes, int noOpeningFrame, int intervalsLeftOutWhole,
                   int omittedDenseFrames, Skipped skipped, SkippedByStatus skippedByStatus) {
    }

    /**
     * What was never opened. Counts only: an unlabelled directory may belong to a test film and an exclusion may sit
     * in a test unit, so no id is written.
     *
     * @param test the directories of test films with no exclusion
     * @param excluded the directories whose labels carry an exclusion
     * @param unlabelled the directories the labels do not list
     */
    record Skipped(int test, int excluded, int unlabelled) {
    }

    /**
     * The development films that were not read.
     *
     * @param incomplete no usable {@code complete.json}
     * @param mismatched a {@code complete.json} of another media file
     * @param damaged a listed file missing or corrupt, or a grey frame of the wrong height
     * @param noDirectory named by {@code --ids}, with no directory
     * @param stale a fingerprint that differs from the label's
     */
    record SkippedByStatus(int incomplete, int mismatched, int damaged, int noDirectory, int stale) {
    }

    /** Why a development film is not read. */
    private enum Status { OK, INCOMPLETE, MISMATCHED, DAMAGED, NO_DIRECTORY, STALE }

    /** Why a directory is not opened. */
    private enum Skip { TEST, EXCLUDED, UNLABELLED }

    /** What was chosen to read, and what was left alone. */
    private record Plan(List<Entry> entries, Skipped skipped) {
    }

    /** A development film to read: its id, and its directory, or null when it has none. */
    private record Entry(long id, Path dir) {
    }

    /** The counts of one run. */
    private static final class Totals {
        private final Map<String, Map<String, Integer>> rows = new LinkedHashMap<>();
        private final Map<String, Integer> openingTypes = new LinkedHashMap<>();
        private final Map<Status, Integer> statuses = new TreeMap<>();
        private int films;
        private int noOpeningFrame;
        private int wholeLeftOut;
        private int omittedDense;

        private Totals() {
            for (String kind : List.of(COARSE, DENSE)) {
                Map<String, Integer> labels = new LinkedHashMap<>();
                labels.put("credits", 0);
                labels.put("film", 0);
                for (LeftOutReason reason : LeftOutReason.values()) {
                    labels.put(reason.name(), 0);
                }
                rows.put(kind, labels);
            }
            for (FrameKind kind : FrameKind.values()) {
                openingTypes.put(kind.name(), 0);
            }
        }

        private int status(Status status) {
            return statuses.getOrDefault(status, 0);
        }

        /** Adds one film's counts (not its statuses) to this run's. */
        private void add(Totals film) {
            films += film.films;
            noOpeningFrame += film.noOpeningFrame;
            wholeLeftOut += film.wholeLeftOut;
            omittedDense += film.omittedDense;
            film.openingTypes.forEach((type, count) -> openingTypes.merge(type, count, Integer::sum));
            film.rows.forEach((kind, labels) -> labels.forEach((label, count) ->
                rows.get(kind).merge(label, count, Integer::sum)));
        }
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return what was written: the run directory, {@code features.csv}, {@code frame-labels.json} and
     *     {@code summary.json}
     * @throws IOException when the dataset cannot be read or the output cannot be written
     * @throws DatasetMeasureJob.NoFilmsException when {@code frames/} is missing or holds no film directory (nothing
     *     is written)
     * @throws IllegalArgumentException when {@code --data} is not a directory, {@code --out} is inside it, or a film
     *     the checks passed cannot be cropped or labelled
     */
    List<Path> run(CreditsFeaturesOptions options) throws IOException, DatasetMeasureJob.NoFilmsException {
        Path data = options.data().toAbsolutePath().normalize();
        Path out = options.out().toAbsolutePath().normalize();
        if (!Files.isDirectory(data)) {
            throw new IllegalArgumentException("--data is not a directory: " + data);
        }
        if (DatasetMeasureJob.resolved(out).startsWith(data.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside --data: " + out);
        }
        byte[] labelsBytes = Files.readAllBytes(options.labels());
        Map<Long, LabelRecord> labels = LabelsFile.read(options.labels());
        Path framesRoot = data.resolve("frames");
        List<Path> found = DatasetMeasureJob.directories(framesRoot);
        if (found.isEmpty()) {
            throw new DatasetMeasureJob.NoFilmsException("no film directories under " + framesRoot);
        }
        Plan plan = plan(found, options, labels);

        Instant now = clock.instant();
        Files.createDirectories(out);
        Path runDir = out.resolve("features-" + STAMP.format(now));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        Totals totals = new Totals();
        List<FrameLabelRecord> records = new ArrayList<>();
        MessageDigest dataset = digest();
        Path featuresCsv = runDir.resolve("features.csv");
        try (BufferedWriter csv = Files.newBufferedWriter(featuresCsv)) {
            csv.write(FeatureRows.header());
            for (Entry entry : plan.entries()) {
                LabelRecord label = labels.get(entry.id());
                Checked checked = check(entry, label);
                Read read = checked.status() == Status.OK ? read(entry, label, checked) : null;
                if (read == null) {
                    totals.statuses.merge(checked.status() == Status.OK ? Status.DAMAGED : checked.status(), 1,
                        Integer::sum);
                } else {
                    csv.write(read.rows());
                    records.add(read.record());
                    dataset.update(checked.bytes());
                    totals.add(read.totals());
                }
            }
        }
        Path frameLabels = runDir.resolve("frame-labels.json");
        Files.write(frameLabels, MAPPER.writeValueAsBytes(records));
        Path summary = runDir.resolve("summary.json");
        Files.write(summary, MAPPER.writeValueAsBytes(summarise(totals, plan.skipped(), now,
            hex(digest().digest(labelsBytes)), hex(dataset.digest()))));
        return List.of(runDir, featuresCsv, frameLabels, summary);
    }

    // ---- Which films ---------------------------------------------------------------------------------------------

    /**
     * Chooses what to read. The candidates are the directories (only those whose ids {@code --ids} names, when it is
     * given) plus each wanted id that has no directory, in ascending id order. A candidate is read only when the
     * labels list it, it has no exclusion, and it is on the development side; every other candidate is counted and
     * never opened.
     */
    private static Plan plan(List<Path> found, CreditsFeaturesOptions options, Map<Long, LabelRecord> labels) {
        Map<Long, Path> candidates = new TreeMap<>();
        int[] counts = new int[Skip.values().length];
        for (Path dir : found) {
            Long id = DatasetMeasureJob.canonicalId(dir.getFileName().toString());
            if (options.ids().isEmpty() || id != null && options.ids().contains(id)) {
                if (id == null) {
                    counts[Skip.UNLABELLED.ordinal()]++;
                } else {
                    candidates.put(id, dir);
                }
            }
        }
        for (long id : options.ids()) {
            candidates.putIfAbsent(id, null);
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Long, Path> candidate : candidates.entrySet()) {
            Skip skip = classify(labels.get(candidate.getKey()));
            if (skip == null) {
                entries.add(new Entry(candidate.getKey(), candidate.getValue()));
            } else {
                counts[skip.ordinal()]++;
            }
        }
        return new Plan(entries, new Skipped(counts[Skip.TEST.ordinal()], counts[Skip.EXCLUDED.ordinal()],
            counts[Skip.UNLABELLED.ordinal()]));
    }

    /** Null to read, else why not. An exclusion is checked before the side, so an excluded test film is excluded. */
    private static Skip classify(LabelRecord label) {
        if (label == null || label.side() == null) {
            return Skip.UNLABELLED;
        }
        if (label.excluded() != null) {
            return Skip.EXCLUDED;
        }
        return label.side() == Side.TEST ? Skip.TEST : null;
    }

    // ---- Pass 1: the check ---------------------------------------------------------------------------------------

    /**
     * The verdict on a film and, when it is {@code ok}, the one {@code complete.json} it was judged on, as a read-only
     * buffer so that the record compares those bytes by content; it is read in full once, by the hash.
     */
    private record Checked(Status status, DatasetComplete complete, ByteBuffer bytes) {
        private static Checked of(Status status) {
            return new Checked(status, null, null);
        }
    }

    /**
     * Fetches {@code complete.json} once; the record parsed from those bytes is the one the checks, the rows and the
     * dataset hash all use.
     */
    private Checked check(Entry entry, LabelRecord label) throws IOException {
        if (entry.dir() == null) {
            return Checked.of(Status.NO_DIRECTORY);
        }
        byte[] bytes;
        try {
            bytes = source.completeBytes(entry.dir());
        } catch (IOException e) {
            return Checked.of(Status.INCOMPLETE);
        }
        DatasetComplete complete = DatasetComplete.parse(bytes).orElse(null);
        if (complete == null) {
            return Checked.of(Status.INCOMPLETE);
        }
        if (complete.mediaFileId() != entry.id()) {
            return Checked.of(Status.MISMATCHED);
        }
        if (!Objects.equals(complete.fingerprint(), label.fingerprint())) {
            return Checked.of(Status.STALE);
        }
        int greyHeight = complete.frame().height() / 2;
        boolean[] wrongHeight = new boolean[1];
        FilmCheck.Result result;
        try {
            result = source.check(entry.dir(), frame -> {
                if (frame.grey().height() != greyHeight) {
                    wrongHeight[0] = true;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // The directory changed or vanished between the bytes read and the walk.
            return Checked.of(Status.DAMAGED);
        }
        // FilmCheck reads complete.json itself: if the file changed since our read, the frames it checked may belong
        // to another version than the one the rows and the hash will come from, so the film is not sound.
        boolean sound = result.status() == FilmCheck.Status.OK && !wrongHeight[0]
            && complete.equals(result.complete());
        return sound ? new Checked(Status.OK, complete, ByteBuffer.wrap(bytes).asReadOnlyBuffer())
            : Checked.of(Status.DAMAGED);
    }

    // ---- Pass 2: the read ----------------------------------------------------------------------------------------

    /** A film being read: its directory, crop and window, and where its rows and counts are held until it is done. */
    private record Film(Path dir, DatasetComplete complete, ActiveCrop crop, StringBuilder csv, Totals totals) {
    }

    /**
     * What pass 2 made of one film.
     *
     * @param record the film's labels after the lit rules
     * @param rows its rows of {@code features.csv}
     * @param totals its counts
     */
    private record Read(FrameLabelRecord record, String rows, Totals totals) {
    }

    /**
     * Pass 2. Nothing of the film is written until it ends: its rows and counts are held, then
     * {@code complete.json} is fetched again, and a film whose bytes are no longer the ones it was checked on, whose
     * files vanish or whose frames are rejected is {@code damaged}, so a directory re-extracted during the run
     * contributes nothing.
     *
     * @return the film's rows, record and counts; null when it is damaged
     */
    private Read read(Entry entry, LabelRecord label, Checked checked) {
        try {
            Read read = readFilm(entry, label, checked.complete());
            return ByteBuffer.wrap(source.completeBytes(entry.dir())).equals(checked.bytes()) ? read : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Read readFilm(Entry entry, LabelRecord label, DatasetComplete complete) throws IOException {
        DatasetComplete.Area area = complete.activeArea();
        ActiveCrop crop = ActiveCrop.of(area.left(), area.top(), area.width(), area.height(),
            complete.frame().width() / 2, complete.frame().height() / 2);
        Totals totals = new Totals();
        Film film = new Film(entry.dir(), complete, crop, new StringBuilder(), totals);

        List<Long> coarseOffsets = complete.coarse();
        FeatureRows.Coarse coarse = FeatureRows.coarse(coarseOffsets,
            index -> load(film, COARSE, coarseOffsets.get(index)));
        List<KindAt> kinds = coarse.kinds();
        FrameLabelRecord record = FrameLabels.apply(label, kinds);
        totals.films++;
        if (record.openingType() == null) {
            totals.noOpeningFrame++;
        } else {
            totals.openingTypes.merge(record.openingType().name(), 1, Integer::sum);
        }
        totals.wholeLeftOut += wholeLeftOut(label, record);
        for (int i = 0; i < kinds.size(); i++) {
            row(film, record, COARSE, kinds.get(i).offsetMs(), coarse.features().get(i));
        }
        dense(film, record);
        return new Read(record, film.csv().toString(), totals);
    }

    private void dense(Film film, FrameLabelRecord record) throws IOException {
        List<Long> offsets = film.complete().dense();
        FeatureRows.dense(offsets.size(), index -> load(film, DENSE, offsets.get(index)),
            (index, crop, previous, next) -> denseRow(film, record, offsets.get(index), crop, previous, next));
    }

    private void denseRow(Film film, FrameLabelRecord record, long offsetMs, GreyFrame crop, GreyFrame previous,
                          GreyFrame next) {
        if (offsetMs < film.complete().window().startMs()) {
            film.totals().omittedDense++;
            return;
        }
        row(film, record, DENSE, offsetMs, FeatureRows.features(crop, previous, next));
    }

    private GreyFrame load(Film film, String kind, long offsetMs) throws IOException {
        GreyFrame grey;
        try {
            grey = source.grey(film.dir(), kind, offsetMs);
        } catch (RuntimeException e) {
            // As FilmCheck does: whatever the decoder throws for a half-written or vanished file, the film is damaged.
            throw new IOException("frame " + kind + "/" + offsetMs + " cannot be read", e);
        }
        try {
            return film.crop().apply(grey);
        } catch (IllegalArgumentException e) {
            throw new IOException("frame " + kind + "/" + offsetMs + " is not the size the film was checked at", e);
        }
    }

    private static void row(Film film, FrameLabelRecord record, String kind, long offsetMs, double[] features) {
        FrameLabel label = FrameLabels.labelAt(record, offsetMs);
        String name = label.kind() == FrameLabel.Kind.LEFT_OUT ? label.reason().name()
            : label.kind().name().toLowerCase(Locale.ROOT);
        film.totals().rows.get(kind).merge(name, 1, Integer::sum);
        film.csv().append(FeatureRows.row(record.mediaFileId(), record.fold(), record.side(), kind, offsetMs, name,
            features));
    }

    /** The credits intervals of the L1 record that are now one {@code LIT_OPENING} stretch, whole. */
    private static int wholeLeftOut(LabelRecord label, FrameLabelRecord record) {
        // Only LIT_OPENING can span a whole credits interval: L1's reasons lie outside the intervals, and a LIT_RUN
        // starts after the plain pair that follows the interval's start.
        Set<Span> leftOutWhole = record.leftOut().stream().map(out -> new Span(out.fromMs(), out.toMs()))
            .collect(Collectors.toSet());
        return (int) label.credits().stream().filter(leftOutWhole::contains).count();
    }

    // ---- Summary -------------------------------------------------------------------------------------------------

    private Summary summarise(Totals totals, Skipped skipped, Instant now, String labelsSha256,
                              String datasetSha256) {
        return new Summary(CreditsFeatures.FEATURE_VERSION, gitCommit.commit(), now.toString(), labelsSha256,
            datasetSha256, totals.films, totals.rows, totals.openingTypes, totals.noOpeningFrame,
            totals.wholeLeftOut, totals.omittedDense, skipped,
            new SkippedByStatus(totals.status(Status.INCOMPLETE), totals.status(Status.MISMATCHED),
                totals.status(Status.DAMAGED), totals.status(Status.NO_DIRECTORY), totals.status(Status.STALE)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
