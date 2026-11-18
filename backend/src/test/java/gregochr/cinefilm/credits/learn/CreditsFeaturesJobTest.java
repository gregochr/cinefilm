package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class CreditsFeaturesJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<Long> TWO_COARSE = FeaturesFixtures.COARSE.subList(0, 2);
    private static final List<Long> TWO_DENSE = FeaturesFixtures.DENSE.subList(0, 2);
    private static final List<Integer> TWO_LEVELS = List.of(60, 60);

    @TempDir
    Path temp;

    private Path data;
    private Path frames;
    private Path out;
    private Path run;
    private final Recording recording = new Recording();
    private CreditsFeaturesJob job;

    /** Delegates to the disk and records which directory was asked what. */
    private static final class Recording implements FilmSource {
        private final FilmSource disk = FilmSource.disk();
        private final List<String> calls = new ArrayList<>();
        /** When set, the check reports this record as the complete.json it read, as if the file had been rewritten. */
        private DatasetComplete checkedComplete;
        /** When set, gives the bytes of complete.json for a directory and its nth fetch; null means the disk's. */
        private BiFunction<String, Integer, byte[]> bytes;
        /** When set, every grey frame of this directory is reported missing, as if it were deleted mid-run. */
        private String failGreyIn;
        /** When set, every grey frame read throws this, as the decoder does for a half-written PNG. */
        private RuntimeException greyThrows;
        private byte[] labelsInstead;
        /** When set, the check throws this (checked or not), as the walk of a vanished directory does. */
        private Exception checkFails;
        /** When set, every grey frame read is this one instead of the disk's. */
        private GreyFrame greyInstead;

        /** The distinct operations asked of one directory, in order of first ask. */
        private List<String> of(String id) {
            return calls.stream().filter(call -> call.startsWith(id + ":")).map(call -> call.substring(id.length() + 1))
                .distinct().toList();
        }

        @Override
        public byte[] completeBytes(Path dir) throws IOException {
            String id = dir.getFileName().toString();
            calls.add(id + ":completeBytes");
            int nth = (int) calls.stream().filter(call -> call.equals(id + ":completeBytes")).count();
            byte[] given = bytes == null ? null : bytes.apply(id, nth);
            return given == null ? disk.completeBytes(dir) : given;
        }

        @Override
        public byte[] labelsBytes(Path file) throws IOException {
            return labelsInstead == null ? disk.labelsBytes(file) : labelsInstead;
        }

        @Override
        public FilmCheck.Result check(Path dir, FilmCheck.Sink sink) throws IOException {
            calls.add(dir.getFileName() + ":check");
            if (checkFails instanceof IOException failure) {
                throw failure;
            } else if (checkFails != null) {
                throw (RuntimeException) checkFails;
            }
            FilmCheck.Result result = disk.check(dir, sink);
            return checkedComplete == null ? result : new FilmCheck.Result(result.status(), checkedComplete,
                result.missing(), result.corrupt(), result.extra(), result.duplicates(), result.pngBytes(),
                result.jpgBytes(), result.totalBytes(), result.earliestModified());
        }

        @Override
        public GreyFrame grey(Path dir, String kind, long offsetMs) throws IOException {
            calls.add(dir.getFileName() + ":grey");
            if (dir.getFileName().toString().equals(failGreyIn)) {
                throw new NoSuchFileException(dir.toString());
            }
            if (greyThrows != null) {
                throw greyThrows;
            }
            if (greyInstead != null) {
                return greyInstead;
            }
            return disk.grey(dir, kind, offsetMs);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        data = Files.createDirectories(temp.resolve("data"));
        frames = Files.createDirectories(data.resolve("frames"));
        out = temp.resolve("out");
        run = out.resolve("features-20240331T233000Z");
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsFeaturesJob(TestData.CLOCK, git,
            recording);
    }

    private Path labels;

    /** One film of each kind, ids 1 to 9 as the plan's smoke-test fixture lists them. */
    private void nineFilms() throws IOException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        FeaturesFixtures.film(frames, "2", FeaturesFixtures.complete(2, FeaturesFixtures.FINGERPRINT, TWO_COARSE,
            TWO_DENSE), TWO_LEVELS, TWO_LEVELS, 4);
        FeaturesFixtures.film(frames, "3", FeaturesFixtures.complete(3, "9000:9", TWO_COARSE, TWO_DENSE), TWO_LEVELS,
            TWO_LEVELS, FeaturesFixtures.GREY_SIDE);
        Files.createDirectories(frames.resolve("4/coarse"));
        FeaturesFixtures.film(frames, "5", FeaturesFixtures.complete(99, "9000:9", TWO_COARSE, TWO_DENSE), TWO_LEVELS,
            TWO_LEVELS, FeaturesFixtures.GREY_SIDE);
        Files.createDirectories(frames.resolve("007")); // a name that is not an id: unlabelled, unless ids are named
        for (String id : List.of("7", "8", "9")) {
            FeaturesFixtures.okFilm(frames, id, Long.parseLong(id));
        }
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT),
            MeasureFixtures.label(2, Side.DEV, null), MeasureFixtures.label(3, Side.DEV, null),
            MeasureFixtures.label(4, Side.DEV, null), MeasureFixtures.label(5, Side.DEV, null),
            MeasureFixtures.label(6, Side.DEV, null), MeasureFixtures.label(7, Side.TEST, null),
            MeasureFixtures.label(8, Side.TEST, ExcludeReason.SPLIT_CONFLICT));
    }

    private List<Path> runWith(Long... ids) throws IOException, DatasetMeasureJob.NoFilmsException {
        return job.run(new CreditsFeaturesOptions(data, labels, out, new TreeSet<>(List.of(ids))));
    }

    private static List<Map<String, String>> rows(Path csvFile) throws IOException {
        List<List<String>> parsed = CsvReader.parse(Files.readString(csvFile));
        List<String> header = parsed.getFirst();
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> fields : parsed.subList(1, parsed.size())) {
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), fields.get(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, String>> featureRows() throws IOException {
        return rows(run.resolve("features.csv"));
    }

    private static Map<String, String> rowAt(List<Map<String, String>> rows, String kind, long offsetMs) {
        return rows.stream().filter(row -> row.get("kind").equals(kind)
            && row.get("offsetMs").equals(Long.toString(offsetMs))).findFirst().orElseThrow();
    }

    private JsonNode summary() throws IOException {
        return MAPPER.readTree(Files.readAllBytes(run.resolve("summary.json")));
    }

    @Test
    void theRunWritesTheRunDirectoryAndThreeFiles() throws IOException, DatasetMeasureJob.NoFilmsException {
        nineFilms();

        List<Path> written = runWith(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        Path real = run.toRealPath();
        assertThat(written).containsExactly(run, run.resolve("features.csv"), run.resolve("frame-labels.json"),
            run.resolve("summary.json"));
        try (Stream<Path> files = Files.list(real)) {
            assertThat(files.map(file -> file.getFileName().toString()).sorted()).containsExactly("features.csv",
                "frame-labels.json", "summary.json");
        }
    }

    @Test
    void onlyTheOkFilmIsReadAndTheOthersAreOpenedAsFarAsTheirStatusNeeds()
        throws IOException, DatasetMeasureJob.NoFilmsException {
        nineFilms();

        runWith(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        assertThat(recording.of("1")).containsExactly("completeBytes", "check", "grey");
        assertThat(recording.of("2")).containsExactly("completeBytes", "check");
        assertThat(recording.of("3")).containsExactly("completeBytes");
        assertThat(recording.of("4")).containsExactly("completeBytes");
        assertThat(recording.of("5")).containsExactly("completeBytes");
        for (String id : List.of("6", "7", "8", "9")) {
            assertThat(recording.of(id)).as("directory " + id).isEmpty();
        }
    }

    @Test
    void theDamagedFilmHasTheRightGreyWidthSoTheCheckAloneFindsItDamaged() throws IOException {
        nineFilms();

        FilmCheck.Result result = FilmCheck.check(frames.resolve("2"), frame -> { });

        assertThat(result.status()).isEqualTo(FilmCheck.Status.OK);
    }

    @Test
    void rowsAndARecordAreWrittenForTheOkFilmAlone() throws IOException, DatasetMeasureJob.NoFilmsException {
        nineFilms();

        runWith(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        assertThat(featureRows()).hasSize(8 + 21).allSatisfy(row -> assertThat(row.get("mediaFileId")).isEqualTo("1"));
        JsonNode records = MAPPER.readTree(Files.readAllBytes(run.resolve("frame-labels.json")));
        assertThat(records.size()).isEqualTo(1);
        assertThat(records.get(0).path("media_file_id").asLong()).isEqualTo(1);
    }

    @Test
    void anExcludedDevelopmentRecordIsCountedAsExcluded() throws IOException, DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            MeasureFixtures.label(1, Side.DEV, ExcludeReason.OWNER_EXCLUDED));

        runWith();

        assertThat(summary().path("skipped").toString()).isEqualTo("{\"test\":0,\"excluded\":1,\"unlabelled\":0}");
        assertThat(summary().path("films").asInt()).isZero();
        assertThat(recording.of("1")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "1,2,3,4,5,6,7,8,9|1,1,1|1,1,1,1,1", // every id named: noDirectory is counted
        "|1,1,2|1,1,1,0,1", // no ids: every directory counts (007 too), noDirectory never
        "1,7|1,0,0|0,0,0,0,0", // a directory that is not named is not counted
        "1,50|0,0,1|0,0,0,0,0"}) // a named id with no label is unlabelled
    void theSkippedCountsFollowTheIdsNamed(String ids, String skipped, String byStatus) throws IOException,
        DatasetMeasureJob.NoFilmsException {
        nineFilms();

        runWith(ids == null ? new Long[0] : java.util.Arrays.stream(ids.split(",")).map(Long::valueOf)
            .toArray(Long[]::new));

        assertThat(summary().path("skipped").toString())
            .isEqualTo(String.format("{\"test\":%s,\"excluded\":%s,\"unlabelled\":%s}", (Object[]) skipped.split(",")));
        assertThat(summary().path("skippedByStatus").toString()).isEqualTo(String.format(
            "{\"incomplete\":%s,\"mismatched\":%s,\"damaged\":%s,\"noDirectory\":%s,\"stale\":%s}",
            (Object[]) byStatus.split(",")));
    }

    private void oneOkFilm() throws IOException, DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));
        runWith();
    }

    private static GreyFrame flat(int level) {
        return FeatureFixtures.uniform(FeaturesFixtures.GREY_SIDE, FeaturesFixtures.GREY_SIDE, level);
    }

    private static String line(String kind, long offsetMs, String label, double[] features) {
        List<String> fields = new ArrayList<>(List.of("1", "2", "dev", kind, Long.toString(offsetMs), label));
        for (double value : features) {
            fields.add(Double.isNaN(value) ? "" : Double.toString(value));
        }
        return String.join(",", fields) + "\n";
    }

    @Test
    void coarseRowsComeFirstThenDenseAndBothAreInOffsetOrder() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        List<String> order = featureRows().stream().map(row -> row.get("kind") + row.get("offsetMs")).toList();

        List<String> expected = new ArrayList<>();
        FeaturesFixtures.COARSE.forEach(offset -> expected.add("coarse" + offset));
        FeaturesFixtures.DENSE.subList(4, 25).forEach(offset -> expected.add("dense" + offset));
        assertThat(order).isEqualTo(expected);
    }

    @Test
    void theLabelColumnNamesCreditsFilmOrTheLeftOutReason() throws IOException, DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        List<Map<String, String>> rows = featureRows();

        assertThat(rows.stream().filter(row -> row.get("kind").equals("coarse")).map(row -> row.get("label")))
            .containsExactly("film", "credits", "credits", "LIT_RUN", "LIT_RUN", "LIT_RUN", "LIT_RUN", "LIT_RUN");
        assertThat(rowAt(rows, "dense", 103_500).get("label")).isEqualTo("film");
        assertThat(rowAt(rows, "dense", 104_000).get("label")).isEqualTo("credits");
        assertThat(rowAt(rows, "dense", 110_000).get("label")).isEqualTo("credits");
    }

    @Test
    void aCoarseRowIsTheFeaturesOfItsFrameAndNeighboursWrittenWithDoubleToString() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        String csv = Files.readString(run.resolve("features.csv"));

        assertThat(csv)
            .contains(line("coarse", 100_000, "film", CreditsFeatures.features(flat(60), flat(2), flat(2))))
            .contains(line("coarse", 110_000, "credits", CreditsFeatures.features(flat(2), flat(2), flat(60))))
            .contains(line("coarse", 135_000, "LIT_RUN", CreditsFeatures.features(flat(60), flat(60), flat(60))));
    }

    @Test
    void aDenseFrameUsesTheFramesTenIndicesEitherSideAndOthersHaveEmptyTimeFeatures() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        List<Map<String, String>> rows = featureRows();
        String csv = Files.readString(run.resolve("features.csv"));

        assertThat(rowAt(rows, "dense", 103_000).get("mad")).isEqualTo("10.0");
        assertThat(rowAt(rows, "dense", 103_000).get("inkChange")).isEqualTo("0.0");
        assertThat(rowAt(rows, "dense", 103_000).get("mean")).isEqualTo("20.0");
        assertThat(rowAt(rows, "dense", 105_000).get("mad")).isEqualTo("10.0");
        assertThat(rowAt(rows, "dense", 102_500).get("mad")).isEmpty();
        assertThat(rowAt(rows, "dense", 102_500).get("inkChange")).isEmpty();
        assertThat(rowAt(rows, "dense", 105_500).get("mad")).isEmpty();
        assertThat(rowAt(rows, "dense", 110_000).get("mad")).isEmpty();
        assertThat(csv)
            .contains(line("dense", 104_000, "credits", CreditsFeatures.features(flat(22), flat(12), flat(32))))
            .contains(line("dense", 100_000, "film", CreditsFeatures.features(flat(14))))
            .contains(line("dense", 104_500, "credits", CreditsFeatures.features(flat(23), flat(13), flat(33))))
            .contains(line("dense", 105_000, "credits", CreditsFeatures.features(flat(24), flat(14), flat(34))));
    }

    @Test
    void denseFramesBeforeTheWindowAreOmittedCountedAndStillServeAsNeighbours() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        List<Map<String, String>> rows = featureRows();

        assertThat(rows.stream().filter(row -> row.get("offsetMs").equals("99500"))).isEmpty();
        assertThat(rowAt(rows, "dense", 100_000)).isNotNull();
        assertThat(summary().path("omittedDenseFrames").asInt()).isEqualTo(4);
        assertThat(rowAt(rows, "dense", 103_000).get("mad")).isEqualTo("10.0");
    }

    @Test
    void aFilmWithOneCoarseFrameHasNoNeighboursSoNoTimeFeaturesAndItsIntervalIsLeftOutWhole() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.film(frames, "1", FeaturesFixtures.complete(1, FeaturesFixtures.FINGERPRINT,
            List.of(115_000L), List.of()), List.of(60), List.of(), FeaturesFixtures.GREY_SIDE);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));

        runWith();

        assertThat(featureRows()).hasSize(1);
        assertThat(featureRows().getFirst().get("mad")).isEmpty();
        assertThat(featureRows().getFirst().get("label")).isEqualTo("LIT_OPENING");
        assertThat(summary().path("intervalsLeftOutWhole").asInt()).isEqualTo(1);
    }

    @Test
    void aFilmWithNoCoarseFrameAtOrAfterItsTargetCountsUnderNoOpeningFrameAndIsStillLabelled() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        LabelRecord base = FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"), new LabelRecord(1, base.fingerprint(),
            base.unitKey(), Side.DEV, 2, 200_000L, 0L, false, base.credits(), base.film(), List.of(), null, null));

        runWith();

        assertThat(summary().path("noOpeningFrame").asInt()).isEqualTo(1);
        assertThat(summary().path("openingTypes").toString()).isEqualTo("{\"DARK\":0,\"PLATE\":0,\"LIT\":0}");
        JsonNode record = MAPPER.readTree(Files.readAllBytes(run.resolve("frame-labels.json"))).get(0);
        assertThat(record.path("opening_type").isNull()).isTrue();
        assertThat(record.path("lit_opening").isNull()).isTrue();
        assertThat(featureRows()).hasSize(29);
    }

    @Test
    void aLitOpeningFilmIsCountedAsLitWithItsWholeIntervalLeftOut() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.film(frames, "1", FeaturesFixtures.complete(1, FeaturesFixtures.FINGERPRINT,
            FeaturesFixtures.COARSE, FeaturesFixtures.DENSE), List.of(60, 60, 60, 60, 60, 60, 60, 60),
            FeaturesFixtures.ramp(10, 25), FeaturesFixtures.GREY_SIDE);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));

        runWith();

        JsonNode summary = summary();
        assertThat(summary.path("openingTypes").toString()).isEqualTo("{\"DARK\":0,\"PLATE\":0,\"LIT\":1}");
        assertThat(summary.path("intervalsLeftOutWhole").asInt()).isEqualTo(1);
        assertThat(summary.path("rows").path("coarse").path("LIT_OPENING").asInt()).isEqualTo(7);
        JsonNode record = MAPPER.readTree(Files.readAllBytes(run.resolve("frame-labels.json"))).get(0);
        assertThat(record.path("lit_opening").asBoolean()).isTrue();
    }

    @Test
    void frameLabelsJsonHoldsTheRecordAfterTheLitRules() throws IOException, DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        JsonNode record = MAPPER.readTree(Files.readAllBytes(run.resolve("frame-labels.json"))).get(0);

        assertThat(record.path("credits").toString()).isEqualTo("[[104000,115000]]");
        assertThat(record.path("film").toString()).isEqualTo("[[100000,104000]]");
        assertThat(record.path("left_out").toString())
            .isEqualTo("[{\"from_ms\":115000,\"to_ms\":140000,\"reason\":\"LIT_RUN\"}]");
        assertThat(record.path("opening_type").asString()).isEqualTo("DARK");
        assertThat(record.path("lit_opening").asBoolean()).isFalse();
        assertThat(record.path("fold").asInt()).isEqualTo(2);
    }

    @Test
    void theSummaryHoldsEveryCount() throws IOException, DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        JsonNode summary = summary();

        assertThat(summary.path("featureVersion").asInt()).isEqualTo(CreditsFeatures.FEATURE_VERSION);
        assertThat(summary.path("toolGitCommit").asString()).isEqualTo("toolsha");
        assertThat(summary.path("generatedAt").asString()).isEqualTo("2024-03-31T23:30:00Z");
        assertThat(summary.path("films").asInt()).isEqualTo(1);
        assertThat(summary.path("rows").path("coarse").toString()).isEqualTo("{\"credits\":2,\"film\":1,"
            + "\"BOUNDARY\":0,\"AFTER_LAST_CREDITS\":0,\"LIT_OPENING\":0,\"LIT_RUN\":5}");
        assertThat(summary.path("rows").path("dense").toString()).isEqualTo("{\"credits\":13,\"film\":8,"
            + "\"BOUNDARY\":0,\"AFTER_LAST_CREDITS\":0,\"LIT_OPENING\":0,\"LIT_RUN\":0}");
        assertThat(summary.path("openingTypes").toString()).isEqualTo("{\"DARK\":1,\"PLATE\":0,\"LIT\":0}");
        assertThat(summary.path("noOpeningFrame").asInt()).isZero();
        assertThat(summary.path("intervalsLeftOutWhole").asInt()).isZero();
        assertThat(summary.path("omittedDenseFrames").asInt()).isEqualTo(4);
    }

    @Test
    void theHashesAreTheLabelsFilesAndTheOkFilmsCompleteJson() throws IOException, NoSuchAlgorithmException,
        DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        JsonNode summary = summary();

        assertThat(summary.path("labelsSha256").asString()).isEqualTo(sha(Files.readAllBytes(labels)));
        assertThat(summary.path("datasetSha256").asString())
            .isEqualTo(sha(Files.readAllBytes(frames.resolve("1/complete.json"))));
    }

    @Test
    void theDatasetHashCoversOnlyOkFilmsInAscendingNumericIdOrder() throws IOException, NoSuchAlgorithmException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "10", 10);
        FeaturesFixtures.okFilm(frames, "9", 9);
        FeaturesFixtures.okFilm(frames, "100", 100);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(9, FeaturesFixtures.FINGERPRINT),
            FeaturesFixtures.label(10, FeaturesFixtures.FINGERPRINT),
            FeaturesFixtures.label(100, "stale:0"));

        runWith();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(frames.resolve("9/complete.json")));
        digest.update(Files.readAllBytes(frames.resolve("10/complete.json")));
        assertThat(summary().path("datasetSha256").asString()).isEqualTo(HexFormat.of().formatHex(digest.digest()));
        assertThat(featureRows().stream().map(row -> row.get("mediaFileId")).distinct().toList())
            .containsExactly("9", "10");
    }

    private static String sha(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void aRecordWithNoSideIsUnlabelledAndNeverOpened() throws IOException, DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        LabelRecord base = FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"), new LabelRecord(1, base.fingerprint(),
            base.unitKey(), null, null, base.targetStartMs(), 0L, false, base.credits(), base.film(), List.of(), null,
            null));

        runWith();

        assertThat(summary().path("skipped").toString()).isEqualTo("{\"test\":0,\"excluded\":0,\"unlabelled\":1}");
        assertThat(recording.of("1")).isEmpty();
    }

    @Test
    void aRecordWithNoFoldGivesAnEmptyFoldColumn() throws IOException, DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        LabelRecord base = FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"), new LabelRecord(1, base.fingerprint(),
            base.unitKey(), Side.DEV, null, base.targetStartMs(), 0L, false, base.credits(), base.film(), List.of(),
            null, null));

        runWith();

        assertThat(featureRows()).hasSize(29).allSatisfy(row -> assertThat(row.get("fold")).isEmpty());
    }

    @Test
    void aFilmWithNoCoarseFramesStillWritesItsDenseRowsAndItsIntervalIsLeftOutWhole() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.film(frames, "1", FeaturesFixtures.complete(1, FeaturesFixtures.FINGERPRINT, List.of(),
            FeaturesFixtures.DENSE), List.of(), FeaturesFixtures.ramp(10, 25), FeaturesFixtures.GREY_SIDE);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));

        runWith();

        assertThat(featureRows()).hasSize(21).allSatisfy(row -> assertThat(row.get("kind")).isEqualTo("dense"));
        assertThat(summary().path("noOpeningFrame").asInt()).isEqualTo(1);
        assertThat(summary().path("intervalsLeftOutWhole").asInt()).isEqualTo(1);
        assertThat(rowAt(featureRows(), "dense", 104_000).get("label")).isEqualTo("LIT_OPENING");
    }

    @Test
    void aLeftOutStretchBeforeTheFirstPlainPairIsNotAnIntervalLeftOutWhole() throws IOException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.film(frames, "1", FeaturesFixtures.complete(1, FeaturesFixtures.FINGERPRINT,
            FeaturesFixtures.COARSE, FeaturesFixtures.DENSE), List.of(60, 60, 2, 2, 60, 60, 60, 60),
            FeaturesFixtures.ramp(10, 25), FeaturesFixtures.GREY_SIDE);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));

        runWith();

        assertThat(summary().path("intervalsLeftOutWhole").asInt()).isZero();
        assertThat(summary().path("rows").path("coarse").path("LIT_OPENING").asInt()).isEqualTo(1);
        assertThat(summary().path("rows").path("coarse").path("LIT_RUN").asInt()).isEqualTo(4);
    }

    @Test
    void aFilmWhoseCompleteJsonDoesNotParseIsIncomplete() throws IOException, DatasetMeasureJob.NoFilmsException {
        Files.writeString(Files.createDirectories(frames.resolve("1")).resolve("complete.json"), "{not json");
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));

        runWith();

        assertThat(summary().path("skippedByStatus").path("incomplete").asInt()).isEqualTo(1);
        assertThat(recording.of("1")).containsExactly("completeBytes");
    }

    private static byte[] indented(byte[] json) {
        return JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
            .writeValueAsBytes(DatasetComplete.parse(json).orElseThrow());
    }

    private byte[] oneFilmWithLabel() throws IOException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));
        return Files.readAllBytes(frames.resolve("1/complete.json"));
    }

    @Test
    void theDatasetHashIsOfTheBytesFetchedNotOfTheFileOnDisk() throws IOException, NoSuchAlgorithmException,
        DatasetMeasureJob.NoFilmsException {
        byte[] disk = oneFilmWithLabel();
        byte[] fetched = indented(disk);
        recording.bytes = (id, nth) -> fetched;

        runWith();

        assertThat(summary().path("datasetSha256").asString()).isEqualTo(sha(fetched)).isNotEqualTo(sha(disk));
        assertThat(summary().path("films").asInt()).isEqualTo(1);
    }

    @Test
    void theLabelsAreParsedFromTheSameBytesTheirHashIsOf() throws IOException, NoSuchAlgorithmException,
        DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"));
        byte[] fetched = Files.readAllBytes(MeasureFixtures.labelsFile(temp.resolve("other.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT)));
        recording.labelsInstead = fetched;

        runWith();

        assertThat(summary().path("films").asInt()).isEqualTo(1);
        assertThat(summary().path("labelsSha256").asString()).isEqualTo(sha(fetched))
            .isNotEqualTo(sha(Files.readAllBytes(labels)));
    }

    @Test
    void completeJsonIsFetchedTwiceForAFilmThatIsRead() throws IOException, DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        assertThat(recording.calls.stream().filter(call -> call.equals("1:completeBytes"))).hasSize(2);
    }

    @Test
    void aFilmWhoseFramesVanishDuringTheReadIsDamagedAndTheRunGoesOn() throws IOException,
        NoSuchAlgorithmException, DatasetMeasureJob.NoFilmsException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        FeaturesFixtures.okFilm(frames, "2", 2);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT),
            FeaturesFixtures.label(2, FeaturesFixtures.FINGERPRINT));
        recording.failGreyIn = "1";

        runWith();

        assertThat(summary().path("skippedByStatus").path("damaged").asInt()).isEqualTo(1);
        assertThat(summary().path("films").asInt()).isEqualTo(1);
        assertThat(summary().path("rows").path("coarse").path("film").asInt()).isEqualTo(1);
        assertThat(summary().path("datasetSha256").asString())
            .isEqualTo(sha(Files.readAllBytes(frames.resolve("2/complete.json"))));
        assertThat(featureRows()).hasSize(29).allSatisfy(row -> assertThat(row.get("mediaFileId")).isEqualTo("2"));
        JsonNode records = MAPPER.readTree(Files.readAllBytes(run.resolve("frame-labels.json")));
        assertThat(records.size()).isEqualTo(1);
        assertThat(records.get(0).path("media_file_id").asLong()).isEqualTo(2);
    }

    /** One way a film turns out damaged, in the check or during the read, applied to a film with id 1. */
    private interface Damage {
        void apply(Path film, Recording source) throws IOException;
    }

    private static Stream<Arguments> damages() {
        return Stream.of(
            Arguments.of(Named.<Damage>of("a listed file is missing", (film, source) ->
                Files.delete(film.resolve("dense/98000.png")))),
            Arguments.of(Named.<Damage>of("the check saw another complete.json", (film, source) ->
                source.checkedComplete = FeaturesFixtures.complete(1, FeaturesFixtures.FINGERPRINT,
                    FeaturesFixtures.COARSE, FeaturesFixtures.DENSE.subList(0, 24)))),
            Arguments.of(Named.<Damage>of("complete.json changed during the read", (film, source) -> {
                byte[] disk = Files.readAllBytes(film.resolve("complete.json"));
                source.bytes = (id, nth) -> source.calls.contains(id + ":grey") ? indented(disk) : null;
            })),
            Arguments.of(Named.<Damage>of("a frame vanishes during the read", (film, source) ->
                source.failGreyIn = "1")),
            Arguments.of(Named.<Damage>of("a frame no longer decodes during the read", (film, source) ->
                source.greyThrows = new IllegalArgumentException("truncated PNG"))),
            Arguments.of(Named.<Damage>of("a frame file vanishes unchecked during the read", (film, source) ->
                source.greyThrows = new UncheckedIOException(new NoSuchFileException("1")))),
            Arguments.of(Named.<Damage>of("the directory vanishes during the check", (film, source) ->
                source.checkFails = new NoSuchFileException("1"))),
            Arguments.of(Named.<Damage>of("the directory vanishes unchecked during the check", (film, source) ->
                source.checkFails = new UncheckedIOException(new NoSuchFileException("1")))),
            Arguments.of(Named.<Damage>of("a frame has the wrong size during the read", (film, source) ->
                source.greyInstead = FeatureFixtures.uniform(8, 8, 1))));
    }

    @ParameterizedTest
    @MethodSource("damages")
    void aDamagedFilmIsCountedOnceAndContributesNoRowRecordCountOrHash(Damage damage) throws IOException,
        NoSuchAlgorithmException, DatasetMeasureJob.NoFilmsException {
        Path film = FeaturesFixtures.okFilm(frames, "1", 1);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));
        damage.apply(film, recording);

        runWith();

        JsonNode summary = summary();
        assertThat(summary.path("skippedByStatus").path("damaged").asInt()).isEqualTo(1);
        assertThat(summary.path("films").asInt()).isZero();
        assertThat(summary.path("rows").path("coarse").path("film").asInt()).isZero();
        assertThat(summary.path("datasetSha256").asString()).isEqualTo(sha(new byte[0]));
        assertThat(featureRows()).isEmpty();
        assertThat(MAPPER.readTree(Files.readAllBytes(run.resolve("frame-labels.json"))).size()).isZero();
    }

    @Test
    void aMissingFramesDirectoryIsNoFilmsAndNothingIsWritten() throws IOException {
        Files.delete(frames);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"));

        assertThatThrownBy(this::runWith).isInstanceOf(DatasetMeasureJob.NoFilmsException.class)
            .hasMessage("no film directories under " + frames.toAbsolutePath().normalize());
        assertThat(out).doesNotExist();
    }

    @Test
    void aDataPathThatIsNotADirectoryIsRefused() throws IOException {
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"));
        Path file = Files.writeString(temp.resolve("file"), "x");

        assertThatThrownBy(() -> job.run(new CreditsFeaturesOptions(file, labels, out, new TreeSet<>())))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("--data is not a directory: " + file);
    }

    @Test
    void anOutputDirectoryInsideTheDataDirectoryIsRefused() throws IOException {
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"));
        Path inside = data.resolve("out");

        assertThatThrownBy(() -> job.run(new CreditsFeaturesOptions(data, labels, inside, new TreeSet<>())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--out must not be inside --data: " + inside.toAbsolutePath().normalize());
    }

    @Test
    void aRunNeverOverwritesAnEarlierRunOfTheSameSecond() throws IOException, DatasetMeasureJob.NoFilmsException {
        oneOkFilm();

        assertThatThrownBy(this::runWith).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("this run's directory already exists: " + run.toAbsolutePath().normalize());
    }
}
