package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    private Path film5;
    private Path film7;
    private DatasetMeasureJob job;

    @BeforeEach
    void setUp() throws IOException {
        data = Files.createDirectories(temp.resolve("data"));
        frames = Files.createDirectories(data.resolve("frames"));
        out = temp.resolve("out");
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new DatasetMeasureJob(Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC), git);
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

    private static DatasetMeasureOptions options(Path data, Path out, boolean stills, long project, Long... ids) {
        return new DatasetMeasureOptions(data, out, new TreeSet<>(List.of(ids)), stills, project);
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

        assertThat(written).containsExactly(out.resolve("films.csv"), out.resolve("frames.csv"),
            out.resolve("summary.json"));
        long png5 = size(film5.resolve("coarse/0.png")) + size(film5.resolve("dense/100000.png"));
        long jpg5 = size(film5.resolve("dense/100000.jpg"));
        long png7 = 0;
        long jpg7 = 0;
        for (String file : List.of("coarse/0", "coarse/5000", "dense/70000", "dense/100000", "dense/130000")) {
            png7 += size(film7.resolve(file + ".png"));
            jpg7 += size(film7.resolve(file + ".jpg"));
        }
        assertThat(Files.readString(out.resolve("films.csv"))).isEqualTo(
            row("mediaFileId", "status", "durationMs", "targetStartMs", "windowStartMs", "frameWidth", "frameHeight",
                "areaLeft", "areaTop", "areaWidth", "areaHeight", "coarseCount", "denseCount", "missing", "corrupt",
                "extra", "pngBytes", "jpgBytes", "totalBytes", "readSeconds", "ffmpegVersion", "javaVersion",
                "gitCommit", "note")
            + row("5", "damaged", "600000", "100000", "360000", "8", "4", "0", "0", "8", "4", "1", "1", "1", "0", "0",
                Long.toString(png5), Long.toString(jpg5), Long.toString(tree(film5)), "30", "\"ffmpeg, 6\"", "25.0.1",
                "def", "")
            + row("7", "ok", "600000", "100000", "360000", "8", "4", "0", "0", "8", "4", "2", "3", "0", "0", "0",
                Long.toString(png7), Long.toString(jpg7), Long.toString(tree(film7)), "599", "ffmpeg 7", "25.0.1",
                "abc", "")
            + "12,incomplete" + ",".repeat(22) + "\n");
        assertThat(Files.readString(out.resolve("frames.csv"))).isEqualTo(
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

        JsonNode summary = MAPPER.readTree(Files.readAllBytes(out.resolve("summary.json")));
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

        JsonNode summary = MAPPER.readTree(Files.readAllBytes(out.resolve("summary.json")));
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

        assertThat(out.resolve("stills")).doesNotExist();
    }

    @Test
    void theStillsAreCopiedUnchangedWithASheetAndAnIndex() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        List<Path> written = job.run(options(true));

        assertThat(written).containsExactly(out.resolve("films.csv"), out.resolve("frames.csv"),
            out.resolve("summary.json"), out.resolve("stills"));
        Path stills = out.resolve("stills/7");
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
        assertThat(out.resolve("stills/12")).doesNotExist();
    }

    @Test
    void aDamagedFilmWithDenseFramesStillGetsStillsFromTheFramesThatWereMeasured()
        throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(true));

        assertThat(out.resolve("stills/5/sheet.png")).isRegularFile();
        assertThat(out.resolve("stills/5/d+0s.png")).hasBinaryContent(
            Files.readAllBytes(film5.resolve("dense/100000.png")));
    }

    @Test
    void aFilmWithNoDenseFramesGetsANoteAndNoStills() throws IOException, DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "13", MeasureFixtures.complete(13, "f", "c", WRITTEN, List.of(0L), List.of()));

        job.run(options(true));

        assertThat(out.resolve("stills/13")).doesNotExist();
        assertThat(filmRow(out.resolve("films.csv"), "13")).containsEntry("note", "no dense frames")
            .containsEntry("status", "ok").containsEntry("denseCount", "0");
    }

    @Test
    void idsLimitTheFilmsAndAnIdWithNoDirectoryIsReported() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false, 99L, 7L));

        List<List<String>> films = csv(out.resolve("films.csv"));
        assertThat(films).hasSize(3);
        assertThat(films.get(1).getFirst()).isEqualTo("7");
        assertThat(films.get(2)).hasSize(24);
        assertThat(films.get(2).subList(0, 2)).containsExactly("99", "incomplete");
        assertThat(films.get(2).getLast()).isEqualTo("no frames directory");
        assertThat(csv(out.resolve("frames.csv")).stream().map(List::getFirst).distinct().toList())
            .containsExactly("mediaFileId", "7");
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(out.resolve("summary.json")));
        assertThat(summary.path("statusCounts").path("incomplete").asInt()).isEqualTo(1);
        assertThat(summary.path("statusCounts").path("damaged").asInt()).isZero();
    }

    @Test
    void anIdIsMatchedAsTheWrittenNumberOnly() throws IOException, DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "007", MeasureFixtures.complete(7, "f", "c", WRITTEN, List.of(), List.of()));
        MeasureFixtures.film(frames, "abc", MeasureFixtures.complete(7, "f", "c", WRITTEN, List.of(), List.of()));

        job.run(options(false, 7L));

        List<List<String>> films = csv(out.resolve("films.csv"));
        assertThat(films.stream().skip(1).map(row -> row.subList(0, 2)).toList())
            .containsExactly(List.of("7", "incomplete"));
        assertThat(films.get(1).getLast()).isEqualTo("no frames directory");
    }

    @Test
    void filmsAreOrderedByNumberThenByName() throws IOException, DatasetMeasureJob.NoFilmsException {
        for (String name : List.of("100", "9", "abc", "20", "Abd")) {
            Files.createDirectories(frames.resolve(name));
        }

        job.run(options(false));

        assertThat(csv(out.resolve("films.csv")).stream().skip(1).map(List::getFirst).toList())
            .containsExactly("9", "20", "100", "Abd", "abc");
    }

    @Test
    void aMismatchedFilmIsReportedWithBlanksAndNoFrames() throws IOException, DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "9", MeasureFixtures.complete(8, "f", "c", WRITTEN, List.of(0L), List.of()));

        job.run(options(false));

        assertThat(Files.readString(out.resolve("films.csv")).lines().skip(1).toList())
            .containsExactly("9,mismatched" + ",".repeat(22));
        assertThat(csv(out.resolve("frames.csv"))).hasSize(1);
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(out.resolve("summary.json")));
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

        Path films = out.resolve("films.csv");
        assertThat(filmRow(films, "10")).containsEntry("readSeconds", "").containsEntry("status", "ok");
        assertThat(filmRow(films, "11")).containsEntry("readSeconds", "");
        assertThat(filmRow(films, "13")).containsEntry("readSeconds", "");
        assertThat(filmRow(films, "14")).containsEntry("readSeconds", "0");
        JsonNode summary = MAPPER.readTree(Files.readAllBytes(out.resolve("summary.json")));
        assertThat(summary.path("meanReadSeconds").asDouble()).isZero();
        assertThat(summary.path("maxReadSeconds").asLong()).isZero();
    }

    @Test
    void aWrittenTimeThatIsNullLeavesTheReadTimeBlank() throws IOException, DatasetMeasureJob.NoFilmsException {
        Path film = MeasureFixtures.film(frames, "15", MeasureFixtures.complete(15, "f", "c", null, List.of(0L),
            List.of()));
        MeasureFixtures.touch(film, Instant.parse("2026-11-10T12:05:00Z"));

        job.run(options(false));

        assertThat(filmRow(out.resolve("films.csv"), "15")).containsEntry("readSeconds", "").containsEntry("status",
            "ok");
    }

    @Test
    void framesAreOrderedCoarseFirstThenByOffsetWhateverTheOrderOfTheLists() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        MeasureFixtures.film(frames, "16", MeasureFixtures.complete(16, "f", "c", WRITTEN, List.of(200_000L, 150_000L),
            List.of(130_000L, 70_000L)));

        job.run(options(false));

        assertThat(csv(out.resolve("frames.csv")).stream().skip(1).map(row -> row.get(1) + " " + row.get(2)).toList())
            .containsExactly("coarse 150000", "coarse 200000", "dense 70000", "dense 130000");
    }

    @Test
    void outputIsUtf8WithoutABomAndHeadersAreOnlyWrittenOnce() throws IOException, DatasetMeasureJob.NoFilmsException {
        threeFilms();

        job.run(options(false));

        for (String name : List.of("films.csv", "frames.csv")) {
            byte[] bytes = Files.readAllBytes(out.resolve(name));
            assertThat(bytes[0]).isEqualTo((byte) 'm');
            assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("\r");
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
    void aSiblingWhoseNameStartsWithTheDataNameIsNotInsideIt() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        threeFilms();
        Path sibling = temp.resolve("data-out");

        job.run(options(data, sibling, false, 850));

        assertThat(sibling.resolve("films.csv")).isRegularFile();
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

        assertThat(csv(out.resolve("films.csv")).stream().skip(1).map(List::getFirst).toList()).containsExactly("3");
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

            assertThat(csv(out.resolve("films.csv"))).hasSize(4);
            assertThat(out.resolve("stills/7/sheet.png")).isRegularFile();
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            Set<java.nio.file.attribute.PosixFilePermission> writable = PosixFilePermissions.fromString("rwxrwxrwx");
            for (Path path : everything) {
                Files.setPosixFilePermissions(path, writable);
            }
        }
    }
}
