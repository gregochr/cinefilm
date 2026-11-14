package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DatasetMeasureJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String WRITTEN = "2026-11-10T12:10:00Z";
    private static final String COARSE_STATS = "20.00,20,20,20,1.0000,0.0000,20.00,20.00,20.00,0.00";
    private static final String DENSE_STATS = "220.00,220,220,220,0.0000,1.0000,220.00,220.00,220.00,0.00";

    @TempDir
    Path temp;

    private Path data;
    private Path frames;
    private Path out;
    private Path run;
    private Path labels;
    private GitCommit git;
    private Path film5;
    private Path film7;
    private DatasetMeasureJob job;

    @BeforeEach
    void setUp() throws IOException {
        data = Files.createDirectories(temp.resolve("data"));
        frames = Files.createDirectories(data.resolve("frames"));
        out = temp.resolve("out");
        run = out.resolve("measure-20261114T090000Z");
        git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new DatasetMeasureJob(Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC), git);
        Side dev = Side.DEV;
        LabelRecord[] records = LongStream.of(3, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 20, 99, 100)
            .mapToObj(id -> MeasureFixtures.label(id, dev, null)).toArray(LabelRecord[]::new);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"), records);
    }

    /** Film 5 is damaged (one colour file missing), 7 is ok, 12 has no complete.json; a stray file is ignored. */
    private void threeFilms() throws IOException {
        film5 = MeasureFixtures.film(frames, "5", MeasureFixtures.complete(5, "ffmpeg, 6", "def", WRITTEN,
            List.of(0L), List.of(100_000L)));
        Files.delete(film5.resolve("coarse/0.jpg"));
        MeasureFixtures.touch(film5, Instant.parse("2026-11-10T12:09:30Z"));
        film7 = MeasureFixtures.film(frames, "7", MeasureFixtures.complete(7, "ffmpeg 7", "abc", WRITTEN,
            List.of(0L, 5_000L), List.of(70_000L, 100_000L, 130_000L)));
        MeasureFixtures.touch(film7, Instant.parse("2026-11-10T12:05:00Z"));
        Files.setLastModifiedTime(film7.resolve("coarse/0.png"),
            FileTime.from(Instant.parse("2026-11-10T12:00:00.900Z")));
        Files.createDirectories(frames.resolve("12"));
        Files.writeString(frames.resolve("notes.txt"), "not a film");
    }

    private DatasetMeasureOptions options(Path data, Path out, boolean stills, long project, Long... ids) {
        return new DatasetMeasureOptions(data, out, labels, new TreeSet<>(List.of(ids)), stills, project);
    }

    private DatasetMeasureOptions splitOptions(Path labelsFile, boolean stills, Long... ids) {
        return new DatasetMeasureOptions(data, out, labelsFile, new TreeSet<>(List.of(ids)), stills, 850);
    }

    private DatasetMeasureOptions options(boolean stills, Long... ids) {
        return options(data, out, stills, 850, ids);
    }

    private long size(Path file) throws IOException {
        return Files.size(file);
    }

    private long tree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            long total = 0;
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                total += Files.size(file);
            }
            return total;
        }
    }

    private static String row(String... fields) {
        return String.join(",", fields) + "\n";
    }

    private static List<List<String>> csv(Path file) throws IOException {
        return CsvReader.parse(Files.readString(file));
    }

    /** The row of one id as column name to value. */
    private static Map<String, String> filmRow(Path filmsCsv, String id) throws IOException {
        List<List<String>> rows = csv(filmsCsv);
        Map<String, String> values = new HashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.getFirst().equals(id)) {
                for (int i = 0; i < row.size(); i++) {
                    values.put(rows.getFirst().get(i), row.get(i));
                }
            }
        }
        return values;
    }

    @Test
    void threeFilmsGiveTheExactFilmsAndFramesFiles() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        List<Path> written = job.run(options(false));

        assertThat(written).containsExactly(run, run.resolve("films.csv"), run.resolve("frames.csv"),
            run.resolve("summary.json"));
        long png5 = size(film5.resolve("coarse/0.png")) + size(film5.resolve("dense/100000.png"));
        long jpg5 = size(film5.resolve("dense/100000.jpg"));
        long png7 = 0;
        long jpg7 = 0;
        for (String file : List.of("coarse/0", "coarse/5000", "dense/70000", "dense/100000", "dense/130000")) {
            png7 += size(film7.resolve(file + ".png"));
            jpg7 += size(film7.resolve(file + ".jpg"));
        }
        assertThat(Files.readString(run.resolve("films.csv"))).isEqualTo(
            row("mediaFileId", "status", "durationMs", "targetStartMs", "windowStartMs", "frameWidth", "frameHeight",
                "areaLeft", "areaTop", "areaWidth", "areaHeight", "coarseCount", "denseCount", "missing", "corrupt",
                "extra", "duplicates", "pngBytes", "jpgBytes", "totalBytes", "readSeconds", "ffmpegVersion",
                "javaVersion",
                "gitCommit", "note")
            + row("5", "damaged", "600000", "100000", "360000", "8", "4", "0", "0", "8", "4", "1", "1", "1", "0", "0",
                "0",
                Long.toString(png5), Long.toString(jpg5), Long.toString(tree(film5)), "30", "\"ffmpeg, 6\"", "25.0.1",
                "def", "")
            + row("7", "ok", "600000", "100000", "360000", "8", "4", "0", "0", "8", "4", "2", "3", "0", "0", "0", "0",
                Long.toString(png7), Long.toString(jpg7), Long.toString(tree(film7)), "599", "ffmpeg 7", "25.0.1",
                "abc", "")
            + "12,incomplete" + ",".repeat(23) + "\n");
        assertThat(Files.readString(run.resolve("frames.csv"))).isEqualTo(
            row("mediaFileId", "kind", "offsetMs", "fromTargetMs", "greyMean", "greyP5", "greyP50", "greyP95",
                "darkShare", "brightShare", "meanR", "meanG", "meanB", "chroma", "pngBytes", "jpgBytes")
            + row("5", "dense", "100000", "0", DENSE_STATS, Long.toString(size(film5.resolve("dense/100000.png"))),
                Long.toString(jpg5))
            + frameRow("7", "coarse", 0, -100_000, COARSE_STATS, film7, "coarse/0")
            + frameRow("7", "coarse", 5000, -95_000, COARSE_STATS, film7, "coarse/5000")
            + frameRow("7", "dense", 70_000, -30_000, DENSE_STATS, film7, "dense/70000")
            + frameRow("7", "dense", 100_000, 0, DENSE_STATS, film7, "dense/100000")
            + frameRow("7", "dense", 130_000, 30_000, DENSE_STATS, film7, "dense/130000"));
    }

    private String frameRow(String id, String kind, long offset, long from, String stats, Path film, String file)
        throws IOException {
        return row(id, kind, Long.toString(offset), Long.toString(from), stats,
            Long.toString(size(film.resolve(file + ".png"))), Long.toString(size(film.resolve(file + ".jpg"))));
    }

    @Test
    void theSummaryIsParsedFieldByField() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false));

        JsonNode summary = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
        long total7 = tree(film7);
        assertThat(summary.path("statusCounts").path("ok").asInt()).isEqualTo(1);
        assertThat(summary.path("statusCounts").path("incomplete").asInt()).isEqualTo(1);
        assertThat(summary.path("statusCounts").path("mismatched").asInt()).isZero();
        assertThat(summary.path("statusCounts").path("damaged").asInt()).isEqualTo(1);
        assertThat(summary.path("statusCounts").size()).isEqualTo(4);
        assertThat(summary.path("meanTotalBytes").asDouble()).isEqualTo((double) total7);
        assertThat(summary.path("maxTotalBytes").asLong()).isEqualTo(total7);
        assertThat(summary.path("meanReadSeconds").asDouble()).isEqualTo(599.0);
        assertThat(summary.path("maxReadSeconds").asLong()).isEqualTo(599);
        assertThat(summary.path("projectFilms").asLong()).isEqualTo(850);
        assertThat(summary.path("projectedBytes").asLong()).isEqualTo(total7 * 850);
        assertThat(strings(summary.path("ffmpegVersions"))).containsExactly("ffmpeg 7", "ffmpeg, 6");
        assertThat(strings(summary.path("javaVersions"))).containsExactly("25.0.1");
        assertThat(strings(summary.path("gitCommits"))).containsExactly("abc", "def");
        assertThat(summary.path("toolGitCommit").asString()).isEqualTo("toolsha");
        assertThat(summary.path("measuredAt").asString()).isEqualTo("2026-11-14T09:00:00Z");
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    @Test
    void theProjectionUsesTheProjectFlagAndTheMeanOfTheOkFilms() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        MeasureFixtures.film(frames, "8", MeasureFixtures.complete(8, "ffmpeg 7", "abc", WRITTEN, List.of(0L),
            List.of()));
        long total7 = tree(film7);
        long total8 = tree(frames.resolve("8"));

        job.run(options(data, out, false, 10));

        JsonNode summary = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
        double mean = (total7 + total8) / 2.0;
        assertThat(summary.path("meanTotalBytes").asDouble()).isEqualTo(mean);
        assertThat(summary.path("maxTotalBytes").asLong()).isEqualTo(Math.max(total7, total8));
        assertThat(summary.path("projectFilms").asLong()).isEqualTo(10);
        assertThat(summary.path("projectedBytes").asLong()).isEqualTo(Math.round(mean * 10));
    }

    @Test
    void stillsAreWrittenOnlyWithTheStillsFlag() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false));

        assertThat(run.resolve("stills")).doesNotExist();
    }

    @Test
    void theStillsAreCopiedUnchangedWithASheetAndAnIndex() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        List<Path> written = job.run(options(true));

        assertThat(written).containsExactly(run, run.resolve("films.csv"), run.resolve("frames.csv"),
            run.resolve("summary.json"), run.resolve("stills"));
        Path stills = run.resolve("stills/7");
        List<String> names;
        try (Stream<Path> list = Files.list(stills)) {
            names = list.map(path -> path.getFileName().toString()).sorted().toList();
        }
        assertThat(names).containsExactlyInAnyOrder("d-30s.jpg", "d-30s.png", "d-20s.jpg", "d-20s.png", "d-10s.jpg",
            "d-10s.png", "d-5s.jpg", "d-5s.png", "d+0s.jpg", "d+0s.png", "d+5s.jpg", "d+5s.png", "d+10s.jpg",
            "d+10s.png", "d+20s.jpg", "d+20s.png", "d+30s.jpg", "d+30s.png", "sheet.png", "stills.csv");
        assertThat(Files.readAllBytes(stills.resolve("d-30s.jpg")))
            .isEqualTo(Files.readAllBytes(film7.resolve("dense/70000.jpg")));
        assertThat(Files.readAllBytes(stills.resolve("d-30s.png")))
            .isEqualTo(Files.readAllBytes(film7.resolve("dense/70000.png")));
        assertThat(Files.readAllBytes(stills.resolve("d+30s.png")))
            .isEqualTo(Files.readAllBytes(film7.resolve("dense/130000.png")));
        assertThat(Files.readAllBytes(stills.resolve("d+0s.jpg")))
            .isEqualTo(Files.readAllBytes(film7.resolve("dense/100000.jpg")));
        assertThat(Files.readString(stills.resolve("stills.csv"))).isEqualTo(
            row("file", "offsetMs", "fromTargetMs")
            + row("d-30s.jpg", "70000", "-30000") + row("d-30s.png", "70000", "-30000")
            + row("d-20s.jpg", "70000", "-30000") + row("d-20s.png", "70000", "-30000")
            + row("d-10s.jpg", "100000", "0") + row("d-10s.png", "100000", "0")
            + row("d-5s.jpg", "100000", "0") + row("d-5s.png", "100000", "0")
            + row("d+0s.jpg", "100000", "0") + row("d+0s.png", "100000", "0")
            + row("d+5s.jpg", "100000", "0") + row("d+5s.png", "100000", "0")
            + row("d+10s.jpg", "100000", "0") + row("d+10s.png", "100000", "0")
            + row("d+20s.jpg", "130000", "30000") + row("d+20s.png", "130000", "30000")
            + row("d+30s.jpg", "130000", "30000") + row("d+30s.png", "130000", "30000"));
        GreyFrame sheet = FramePrep.decodePng(Files.readAllBytes(stills.resolve("sheet.png")));
        assertThat(sheet.width()).isEqualTo(20);
        assertThat(sheet.height()).isEqualTo(14);
        assertThat(sheet.luma(0, 0)).isEqualTo(MeasureFixtures.DENSE_LEVEL);
        assertThat(sheet.luma(4, 0)).isZero();
        assertThat(sheet.luma(19, 13)).isEqualTo(MeasureFixtures.DENSE_LEVEL);
        assertThat(run.resolve("stills/12")).doesNotExist();
    }

    @Test
    void aDamagedFilmWithDenseFramesStillGetsStillsFromTheFramesThatWereMeasured()
        throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(true));

        assertThat(run.resolve("stills/5/sheet.png")).isRegularFile();
        assertThat(run.resolve("stills/5/d+0s.png")).hasBinaryContent(
            Files.readAllBytes(film5.resolve("dense/100000.png")));
    }

    @Test
    void aFilmWithNoDenseFramesGetsANoteAndNoStills() throws IOException, DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "13", MeasureFixtures.complete(13, "f", "c", WRITTEN, List.of(0L), List.of()));

        job.run(options(true));

        assertThat(run.resolve("stills/13")).doesNotExist();
        assertThat(filmRow(run.resolve("films.csv"), "13")).containsEntry("note", "no dense frames")
            .containsEntry("status", "ok").containsEntry("denseCount", "0");
    }

    @Test
    void idsLimitTheFilmsAndAnIdWithNoDirectoryIsReported() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false, 99L, 7L));

        List<List<String>> films = csv(run.resolve("films.csv"));
        assertThat(films).hasSize(3);
        assertThat(films.get(1).getFirst()).isEqualTo("7");
        assertThat(films.get(2)).hasSize(25);
        assertThat(films.get(2).subList(0, 2)).containsExactly("99", "incomplete");
        assertThat(films.get(2).getLast()).isEqualTo("no frames directory");
        assertThat(csv(run.resolve("frames.csv")).stream().map(List::getFirst).distinct().toList())
            .containsExactly("mediaFileId", "7");
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
        assertThat(summary.path("statusCounts").path("incomplete").asInt()).isEqualTo(1);
        assertThat(summary.path("statusCounts").path("damaged").asInt()).isZero();
    }

    @Test
    void anIdIsMatchedAsTheWrittenNumberOnly() throws IOException, DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "007", MeasureFixtures.complete(7, "f", "c", WRITTEN, List.of(), List.of()));
        MeasureFixtures.film(frames, "abc", MeasureFixtures.complete(7, "f", "c", WRITTEN, List.of(), List.of()));

        job.run(options(false, 7L));

        List<List<String>> films = csv(run.resolve("films.csv"));
        assertThat(films.stream().skip(1).map(row -> row.subList(0, 2)).toList())
            .containsExactly(List.of("7", "incomplete"));
        assertThat(films.get(1).getLast()).isEqualTo("no frames directory");
    }

    @Test
    void filmsAreOrderedByNumber() throws IOException, DatasetMeasureJob.NoFilmsException {
        for (String name : List.of("100", "9", "20")) {
            Files.createDirectories(frames.resolve(name));
        }

        job.run(options(false));

        assertThat(csv(run.resolve("films.csv")).stream().skip(1).map(List::getFirst).toList())
            .containsExactly("9", "20", "100");
    }

    @Test
    void aMismatchedFilmIsReportedWithBlanksAndNoFrames() throws IOException, DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "9", MeasureFixtures.complete(8, "f", "c", WRITTEN, List.of(0L), List.of()));

        job.run(options(false));

        assertThat(Files.readString(run.resolve("films.csv")).lines().skip(1).toList())
            .containsExactly("9,mismatched" + ",".repeat(23));
        assertThat(csv(run.resolve("frames.csv"))).hasSize(1);
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
        assertThat(summary.path("statusCounts").path("mismatched").asInt()).isEqualTo(1);
        assertThat(summary.path("meanTotalBytes").isNull()).isTrue();
        assertThat(summary.path("maxTotalBytes").isNull()).isTrue();
        assertThat(summary.path("meanReadSeconds").isNull()).isTrue();
        assertThat(summary.path("maxReadSeconds").isNull()).isTrue();
        assertThat(summary.path("projectedBytes").isNull()).isTrue();
        assertThat(summary.path("ffmpegVersions").size()).isZero();
    }

    @Test
    void theReadTimeIsBlankWhenItCannotBeWorkedOut() throws IOException, DatasetMeasureJob.NoFilmsException {
        Path negative = MeasureFixtures.film(frames, "10", MeasureFixtures.complete(10, "f", "c",
            "2026-11-10T12:00:00Z", List.of(0L), List.of()));
        MeasureFixtures.touch(negative, Instant.parse("2026-11-10T12:05:00Z"));
        Path garbage = MeasureFixtures.film(frames, "11", MeasureFixtures.complete(11, "f", "c", "yesterday",
            List.of(0L), List.of()));
        MeasureFixtures.touch(garbage, Instant.parse("2026-11-10T12:05:00Z"));
        Files.writeString(frames.resolve("12.json"), "x");
        MeasureFixtures.film(frames, "13", MeasureFixtures.complete(13, "f", "c", WRITTEN, List.of(), List.of()));
        Path zero = MeasureFixtures.film(frames, "14", MeasureFixtures.complete(14, "f", "c", WRITTEN, List.of(0L),
            List.of()));
        MeasureFixtures.touch(zero, Instant.parse(WRITTEN));

        job.run(options(false));

        Path films = run.resolve("films.csv");
        assertThat(filmRow(films, "10")).containsEntry("readSeconds", "").containsEntry("status", "ok");
        assertThat(filmRow(films, "11")).containsEntry("readSeconds", "");
        assertThat(filmRow(films, "13")).containsEntry("readSeconds", "");
        assertThat(filmRow(films, "14")).containsEntry("readSeconds", "0");
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
        assertThat(summary.path("meanReadSeconds").asDouble()).isZero();
        assertThat(summary.path("maxReadSeconds").asLong()).isZero();
    }

    @Test
    void aWrittenTimeThatIsNullLeavesTheReadTimeBlank() throws IOException, DatasetMeasureJob.NoFilmsException {
        Path film = MeasureFixtures.film(frames, "15", MeasureFixtures.complete(15, "f", "c", null, List.of(0L),
            List.of()));
        MeasureFixtures.touch(film, Instant.parse("2026-11-10T12:05:00Z"));

        job.run(options(false));

        assertThat(filmRow(run.resolve("films.csv"), "15")).containsEntry("readSeconds", "").containsEntry("status",
            "ok");
    }

    @Test
    void framesAreOrderedCoarseFirstThenByOffsetWhateverTheOrderOfTheLists() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "16", MeasureFixtures.complete(16, "f", "c", WRITTEN, List.of(200_000L, 150_000L),
            List.of(130_000L, 70_000L)));

        job.run(options(false));

        assertThat(csv(run.resolve("frames.csv")).stream().skip(1).map(row -> row.get(1) + " " + row.get(2)).toList())
            .containsExactly("coarse 150000", "coarse 200000", "dense 70000", "dense 130000");
    }

    /** A film directory whose complete.json cannot be read: opening it would show as an {@code incomplete} row. */
    private void trap(String name) throws IOException {
        Path dir = Files.createDirectories(frames.resolve(name));
        Files.writeString(dir.resolve("complete.json"), "{not json");
    }

    /** Every file under the output directory, its path and its content, as one string. */
    private String everythingWritten() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path path : walk.toList()) {
                all.append(out.relativize(path)).append('\n');
                if (Files.isRegularFile(path) && !path.toString().endsWith(".png")
                    && !path.toString().endsWith(".jpg")) {
                    all.append(Files.readString(path)).append('\n');
                }
            }
        }
        return all.toString();
    }

    private Path splitLabels() throws IOException {
        return MeasureFixtures.labelsFile(temp.resolve("split.json"),
            MeasureFixtures.label(5, Side.DEV, null),
            MeasureFixtures.label(6, Side.DEV, null),
            MeasureFixtures.label(4242, Side.TEST, null),
            MeasureFixtures.label(4343, Side.TEST, ExcludeReason.SPLIT_CONFLICT),
            MeasureFixtures.label(8181, Side.DEV, ExcludeReason.NOT_PRESENT),
            MeasureFixtures.label(0, Side.DEV, null),
            MeasureFixtures.label(6161, null, null));
    }

    @Test
    void testExcludedAndUnlabelledFilmsAreCountedAndTheirDirectoriesNeverOpened() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        for (String name : List.of("4242", "4343", "8181", "9191", "abc", "007", "6161")) {
            trap(name);
        }

        job.run(splitOptions(splitLabels(), true));

        assertThat(csv(run.resolve("films.csv")).stream().skip(1).map(List::getFirst).toList())
            .containsExactly("5");
        assertThat(csv(run.resolve("frames.csv")).stream().skip(1).map(List::getFirst).distinct().toList())
            .containsExactly("5");
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
        JsonNode skipped = summary.path("skipped");
        assertThat(skipped.path("test").asInt()).isEqualTo(2);
        assertThat(skipped.path("excluded").asInt()).isEqualTo(1);
        assertThat(skipped.path("unlabelled").asInt()).isEqualTo(6);
        assertThat(new ArrayList<>(skipped.propertyNames()))
            .containsExactlyInAnyOrder("test", "excluded", "unlabelled");
        assertThat(summary.has("look")).isFalse();
        assertThat(summary.path("statusCounts").path("incomplete").asInt()).isZero();
        assertThat(run.resolve("stills/4242")).doesNotExist();
        assertThat(everythingWritten()).doesNotContain("4242").doesNotContain("4343").doesNotContain("8181")
            .doesNotContain("9191").doesNotContain("6161").doesNotContain("abc").doesNotContain("007");
    }

    @Test
    void noTestFilmIdAppearsInAnyOutputFile() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();
        MeasureFixtures.film(frames, "4242", MeasureFixtures.complete(4242, "f", "c", WRITTEN, List.of(0L),
            List.of(100_000L)));
        MeasureFixtures.film(frames, "4343", MeasureFixtures.complete(4343, "f", "c", WRITTEN, List.of(0L),
            List.of(100_000L)));

        job.run(splitOptions(splitLabels(), true));
        new DatasetMeasureJob(Clock.fixed(Instant.parse("2026-11-14T09:00:05Z"), ZoneOffset.UTC), git)
            .run(splitOptions(splitLabels(), true, 4242L, 5L));

        assertThat(everythingWritten()).doesNotContain("4242").doesNotContain("4343");
    }

    @Test
    void idsNarrowTheDevelopmentFilmsAndAnIdOfAnotherKindIsCountedTheSame() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        MeasureFixtures.film(frames, "6", MeasureFixtures.complete(6, "f", "c", WRITTEN, List.of(0L), List.of()));
        trap("4242");
        trap("8181");
        trap("9191");

        job.run(splitOptions(splitLabels(), false, 5L, 4242L, 8181L, 9191L, 7777L, 4343L));

        assertThat(csv(run.resolve("films.csv")).stream().skip(1).map(List::getFirst).toList())
            .containsExactly("5");
        JsonNode skipped = MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json"))).path("skipped");
        assertThat(skipped.path("test").asInt()).isEqualTo(2);
        assertThat(skipped.path("excluded").asInt()).isEqualTo(1);
        assertThat(skipped.path("unlabelled").asInt()).isEqualTo(2);
        assertThat(everythingWritten()).doesNotContain("4242").doesNotContain("4343").doesNotContain("8181")
            .doesNotContain("9191").doesNotContain("7777");
    }

    @Test
    void aDevelopmentIdWithNoDirectoryStillGetsItsNoteRowWhenRequested() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(splitOptions(splitLabels(), false, 6L));

        assertThat(filmRow(run.resolve("films.csv"), "6")).containsEntry("note", "no frames directory");
    }

    @Test
    void aMissingOrUnreadableLabelsFileStopsTheRunBeforeAnythingIsWritten() throws IOException {
        threeFilms();

        assertThatThrownBy(() -> job.run(splitOptions(temp.resolve("no-labels.json"), false)))
            .isInstanceOf(NoSuchFileException.class);
        Path bad = Files.writeString(temp.resolve("bad.json"), "{\"films\": []}");
        assertThatThrownBy(() -> job.run(splitOptions(bad, false)))
            .isInstanceOf(JacksonException.class);
        assertThat(out).doesNotExist();
    }

    @Test
    void everyRunWritesIntoADirectoryOfItsOwnNamedForItsTime() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        DatasetMeasureJob later = new DatasetMeasureJob(Clock.fixed(Instant.parse("2026-11-14T09:00:01Z"),
            ZoneOffset.UTC), git);

        List<Path> first = job.run(options(true));
        Files.writeString(first.getFirst().resolve("sentinel.txt"), "kept");
        List<Path> second = later.run(options(false));

        assertThat(first.getFirst()).isEqualTo(out.resolve("measure-20261114T090000Z"));
        assertThat(second.getFirst()).isEqualTo(out.resolve("measure-20261114T090001Z"));
        assertThat(run.resolve("stills/7")).isDirectory();
        assertThat(out.resolve("measure-20261114T090001Z/stills")).doesNotExist();
        assertThat(Files.readString(run.resolve("sentinel.txt"))).isEqualTo("kept");
        try (Stream<Path> list = Files.list(out)) {
            assertThat(list.map(path -> path.getFileName().toString()).sorted().toList())
                .containsExactly("measure-20261114T090000Z", "measure-20261114T090001Z");
        }
    }

    @Test
    void aRunWhoseDirectoryExistsIsRefusedAndTheEarlierRunIsLeftAlone() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        job.run(options(true));
        Files.writeString(run.resolve("sentinel.txt"), "kept");
        List<String> before = listing(run);

        assertThatThrownBy(() -> job.run(options(false))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("this run's directory already exists: " + run);

        assertThat(listing(run)).isEqualTo(before);
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<String> entries = new ArrayList<>();
            for (Path path : walk.sorted().toList()) {
                entries.add(dir.relativize(path) + ":" + (Files.isDirectory(path) ? 0 : Files.size(path)));
            }
            return entries;
        }
    }

    @Test
    void nothingOutsideTheRunDirectoryIsWritten() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(true));

        try (Stream<Path> list = Files.list(out)) {
            assertThat(list.toList()).containsExactly(run);
        }
        try (Stream<Path> list = Files.list(temp)) {
            assertThat(list.map(path -> path.getFileName().toString()).sorted().toList())
                .containsExactly("data", "labels.json", "out");
        }
    }

    @Test
    void outputIsUtf8WithoutABomAndHeadersAreOnlyWrittenOnce() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false));

        for (String name : List.of("films.csv", "frames.csv")) {
            byte[] bytes = Files.readAllBytes(run.resolve(name));
            assertThat(bytes[0]).isEqualTo((byte) 'm');
            assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\r");
        }
    }

    @Test
    void anOutputDirectoryInsideTheDataIsRefusedAndNothingIsWritten() throws IOException {
        threeFilms();
        Path inside = data.resolve("out");

        assertThatThrownBy(() -> job.run(options(data, inside, false, 850)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--out must not be inside --data: " + inside);
        assertThatThrownBy(() -> job.run(options(data, data, false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.run(options(data, frames.resolve("7/x/../y"), false, 850)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(inside).doesNotExist();
    }

    @Test
    void anOutputReachedThroughASymbolicLinkIntoTheDataIsRefused() throws IOException {
        threeFilms();
        Path link = temp.resolve("link");
        try {
            Files.createSymbolicLink(link, data);
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links can be made here").isTrue();
        }
        Path viaLink = link.resolve("newout");
        Path dataViaLink = temp.resolve("datalink");
        Files.createSymbolicLink(dataViaLink, data);

        assertThatThrownBy(() -> job.run(options(data, viaLink, false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.run(options(data, link.resolve("frames/7"), false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.run(options(dataViaLink, data.resolve("out"), false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job.run(options(dataViaLink, link, false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(data.resolve("newout")).doesNotExist();
    }

    @Test
    void aFileThatCannotBeReadDamagesItsFilmAndEveryReportIsStillWritten() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        Path locked = film7.resolve("dense/100000.jpg");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeThat(Files.isReadable(locked)).as("permissions bind for this user").isFalse();

            List<Path> written = job.run(options(false));

            assertThat(written).hasSize(4).allSatisfy(path -> assertThat(path).exists());
            assertThat(filmRow(run.resolve("films.csv"), "7")).containsEntry("status", "damaged")
                .containsEntry("corrupt", "1").containsEntry("missing", "0");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void aDuplicateOffsetGetsOneFramesRowAndIsCountedAndDamages() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "17", MeasureFixtures.complete(17, "f", "c", WRITTEN, List.of(0L, 0L),
            List.of(100_000L, 100_000L, 100_000L)));

        job.run(options(false));

        assertThat(filmRow(run.resolve("films.csv"), "17")).containsEntry("status", "damaged")
            .containsEntry("duplicates", "3").containsEntry("coarseCount", "2").containsEntry("denseCount", "3");
        assertThat(csv(run.resolve("frames.csv")).stream().skip(1).map(row -> row.get(1) + " " + row.get(2)).toList())
            .containsExactly("coarse 0", "dense 100000");
    }

    @Test
    void numbersAreWrittenTheSameWhateverTheDefaultLocale() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            job.run(options(false, 7L));
        } finally {
            Locale.setDefault(saved);
        }

        assertThat(csv(run.resolve("frames.csv")).get(1).subList(0, 14)).containsExactly("7", "coarse", "0",
            "-100000", "20.00", "20", "20", "20", "1.0000", "0.0000", "20.00", "20.00", "20.00", "0.00");
        assertThat(Files.readString(run.resolve("summary.json"))).contains("\"meanReadSeconds\" : 599.0");
    }

    @Test
    void aSiblingWhoseNameStartsWithTheDataNameIsNotInsideIt() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        Path sibling = temp.resolve("data-out");

        job.run(options(data, sibling, false, 850));

        assertThat(sibling.resolve("measure-20261114T090000Z/films.csv")).isRegularFile();
    }

    @Test
    void aRelativeOutputPathInsideTheDataIsAlsoRefused() throws IOException {
        threeFilms();
        Path relative = Path.of("").toAbsolutePath().relativize(data.resolve("out"));

        assertThatThrownBy(() -> job.run(options(data, relative, false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDataPathThatIsNotADirectoryIsRefused() throws IOException {
        Path file = Files.writeString(temp.resolve("plain.txt"), "x");

        assertThatThrownBy(() -> job.run(options(file, out, false, 850))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--data is not a directory: " + file);
        assertThatThrownBy(() -> job.run(options(temp.resolve("nope"), out, false, 850)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(out).doesNotExist();
    }

    @Test
    void anEmptyFramesDirectoryIsNoFilmsAndNothingIsWritten() throws IOException {
        Files.writeString(frames.resolve("notes.txt"), "only a file");

        assertThatThrownBy(() -> job.run(options(false))).isInstanceOf(DatasetMeasureJob.NoFilmsException.class)
            .hasMessage("no film directories under " + frames);
        assertThat(out).doesNotExist();
    }

    @Test
    void aMissingFramesDirectoryIsNoFilms() throws IOException {
        Files.delete(frames);

        assertThatThrownBy(() -> job.run(options(false))).isInstanceOf(DatasetMeasureJob.NoFilmsException.class)
            .hasMessage("no film directories under " + frames);
        assertThat(out).doesNotExist();
    }

    @Test
    void idsThatMatchNothingStillMeasureAndReportThem() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false, 3L));

        assertThat(csv(run.resolve("films.csv")).stream().skip(1).map(List::getFirst).toList()).containsExactly("3");
    }

    @Test
    void theToolNeverWritesIntoTheDataDirectory() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();
        List<String> before = snapshot();

        job.run(options(true));

        assertThat(snapshot()).isEqualTo(before);
    }

    private List<String> snapshot() throws IOException {
        try (Stream<Path> walk = Files.walk(data)) {
            List<String> entries = new ArrayList<>();
            for (Path path : walk.sorted().toList()) {
                entries.add(data.relativize(path) + ":" + (Files.isDirectory(path) ? 0 : Files.size(path)) + ":"
                    + Files.getLastModifiedTime(path));
            }
            return entries;
        }
    }

    @Test
    void aReadOnlyDataDirectoryIsMeasuredAndStaysUntouched() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();
        List<Path> everything;
        try (Stream<Path> walk = Files.walk(data)) {
            everything = walk.toList();
        }
        try {
            for (Path path : everything) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? "r-xr-xr-x" : "r--r--r--"));
            }
            assumeThat(Files.isWritable(frames)).as("permissions bind for this user").isFalse();
            List<String> before = snapshot();

            job.run(options(true));

            assertThat(csv(run.resolve("films.csv"))).hasSize(4);
            assertThat(run.resolve("stills/7/sheet.png")).isRegularFile();
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            Set<PosixFilePermission> writable = PosixFilePermissions.fromString("rwxrwxrwx");
            for (Path path : everything) {
                Files.setPosixFilePermissions(path, writable);
            }
        }
    }
}
