package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.credits.RgbFrame;
import gregochr.cinefilm.credits.learn.CreditsEncoderBenchJob.EncoderFactory;
import gregochr.cinefilm.credits.learn.EmbedFixtures.Film;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CreditsEmbedJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RUN = "embed-20240331T233000Z";
    private static final List<Film> DEV = List.of(Film.dev(1, 17, 3), Film.dev(3, 16, 2));
    private static final List<Film> ALL = List.of(Film.dev(1, 17, 3), Film.test(2, 2, 1), Film.dev(3, 16, 2));

    @TempDir
    Path temp;

    private Path data;
    private Path out;
    private Path devRun;
    private Path lookRun;
    private GitCommit git;
    private final List<String> opened = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        data = temp.resolve("data");
        out = temp.resolve("out");
        EmbedFixtures.dataset(data, ALL);
        devRun = EmbedFixtures.featuresRun(temp.resolve("runs"), "dev-run", data, DEV, false);
        lookRun = EmbedFixtures.featuresRun(temp.resolve("runs"), "look-run", data, ALL, true);
    }

    /** The disk, recording every JPEG that is opened. */
    private FrameSource recording() {
        FrameSource disk = FrameSource.disk();
        return new FrameSource() {
            @Override
            public byte[] completeBytes(Path dir) throws IOException {
                return disk.completeBytes(dir);
            }

            @Override
            public boolean hasJpeg(Path dir, String kind, long offsetMs) {
                return disk.hasJpeg(dir, kind, offsetMs);
            }

            @Override
            public byte[] jpeg(Path dir, String kind, long offsetMs) throws IOException {
                opened.add(dir.getFileName() + "/" + kind + "/" + offsetMs);
                return disk.jpeg(dir, kind, offsetMs);
            }
        };
    }

    private static LongSupplier sequence(long... values) {
        int[] next = {0};
        return () -> values[next[0]++];
    }

    private CreditsEmbedJob job(EncoderFactory factory) {
        return new CreditsEmbedJob(TestData.CLOCK, git, factory,
            new BenchProbe(sequence(5_000_000_000L, 7_500_000_000L), () -> 1L, () -> 1L, () -> "9.9.9"), recording());
    }

    private CreditsEmbedJob job(ColourMeanEncoder encoder) {
        return job((spec, dir) -> encoder);
    }

    private CreditsEmbedOptions options(Path features, boolean look) {
        return options(features, look, out);
    }

    private CreditsEmbedOptions options(Path features, boolean look, Path target) {
        return new CreditsEmbedOptions(data, features, TinyFixture.dir(), target, look);
    }

    private record Record(long id, int kind, long offset, float[] vector) {
    }

    private static List<Record> records(Path bin) throws IOException {
        List<Record> records = new ArrayList<>();
        try (EmbeddingFile.Reader reader = EmbeddingFile.open(bin)) {
            while (reader.next()) {
                records.add(new Record(reader.mediaFileId(), reader.kind(), reader.offsetMs(), reader.vector()));
            }
        }
        return records;
    }

    private void assertRefused(CreditsEmbedOptions options, String message) {
        assertThatThrownBy(() -> job(new ColourMeanEncoder()).run(options)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
        assertThat(out).doesNotExist();
        assertThat(opened).isEmpty();
    }

    private void assertLeftNothing() throws IOException {
        try (Stream<Path> left = Files.list(out)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    @Test
    void everyFeaturesRowGetsARecordInFileOrderWithTheFramesOwnEmbedding() throws IOException {
        ColourMeanEncoder encoder = new ColourMeanEncoder();

        List<Path> written = job(encoder).run(options(devRun, false));

        Path runDir = out.toAbsolutePath().normalize().resolve(RUN);
        assertThat(written).containsExactly(runDir, runDir.resolve("embeddings.bin"),
            runDir.resolve("embeddings.json"));
        try (Stream<Path> files = Files.list(runDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).sorted())
                .containsExactly("embeddings.bin", "embeddings.json");
        }
        List<Record> records = records(runDir.resolve("embeddings.bin"));
        List<long[]> keys = new ArrayList<>();
        for (Film film : DEV) {
            film.coarseOffsets().forEach(offset -> keys.add(new long[] {film.id(), 0, offset}));
            film.denseOffsets().forEach(offset -> keys.add(new long[] {film.id(), 1, offset}));
        }
        assertThat(records).hasSize(keys.size()).hasSize(38);
        for (int i = 0; i < keys.size(); i++) {
            Record record = records.get(i);
            assertThat(new long[] {record.id(), record.kind(), record.offset()}).isEqualTo(keys.get(i));
            int index = keys.get(i)[1] == 0 ? (int) ((keys.get(i)[2] - 100_000) / 5_000)
                : (int) ((keys.get(i)[2] - 98_000) / 500);
            int[] rgb = EmbedFixtures.colour(record.id(), record.kind() == 1, index);
            for (int c = 0; c < 3; c++) {
                assertThat(record.vector()[c]).isCloseTo(rgb[c], within(3f));
            }
        }
        assertThat(encoder.closed).isTrue();
    }

    @Test
    void embeddingsJsonHoldsEveryFieldInTheSpecsOrderWithTheRunsOwnValues() throws IOException {
        job(new ColourMeanEncoder()).run(options(devRun, false));

        Path runDir = out.resolve(RUN);
        String summary = Files.readString(devRun.resolve("summary.json"));
        String expected = """
            {"toolGitCommit": "toolsha", "generatedAt": "2024-03-31T23:30:00Z", "featuresRun": "dev-run",
             "featuresCsvSha256": "%s", "featureVersion": %d, "datasetSha256": "%s", "labelsSha256": "%s",
             "encoder": {"name": "tiny", "sha256": "%s", "dimension": 3}, "onnxRuntimeVersion": "9.9.9",
             "films": {"dev": 2, "test": 0}, "rows": {"dev": 38, "test": 0}, "devSha256": "%s",
             "cpuSeconds": 2.5}
            """.formatted(EmbedFixtures.sha256(devRun.resolve("features.csv"), 0), CreditsFeatures.FEATURE_VERSION,
            MAPPER.readTree(summary).get("datasetSha256").asString(), "ab".repeat(32), TinyFixture.SHA256,
            EmbedFixtures.sha256(runDir.resolve("embeddings.bin"), 20));
        String json = Files.readString(runDir.resolve("embeddings.json"));
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expected));
        assertThat(MAPPER.readTree(json).propertyNames()).containsExactly("toolGitCommit", "generatedAt",
            "featuresRun", "featuresCsvSha256", "featureVersion", "datasetSha256", "labelsSha256", "encoder",
            "onnxRuntimeVersion", "films", "rows", "devSha256", "cpuSeconds");
    }

    @Test
    void batchesNeverMixFilmsOrKindsAndTheLastOfAKindIsShort() throws IOException {
        ColourMeanEncoder encoder = new ColourMeanEncoder();

        job(encoder).run(options(devRun, false));

        assertThat(encoder.batches).containsExactly(16, 1, 3, 16, 2);
    }

    @Test
    void underTheLookTestFilmsAreEmbeddedAndNeverChangeADevelopmentFilmsBatchesOrHash() throws IOException {
        ColourMeanEncoder withLook = new ColourMeanEncoder();
        Path other = temp.resolve("out-dev");

        List<Path> written = job(withLook).run(options(lookRun, true));
        job(new ColourMeanEncoder()).run(options(devRun, false, other));

        Path runDir = out.toAbsolutePath().normalize().resolve(RUN);
        assertThat(written).containsExactly(runDir, runDir.resolve("embeddings.bin"),
            runDir.resolve("embeddings.json"), runDir.resolve("look.json"));
        assertThat(withLook.batches).containsExactly(16, 1, 3, 2, 1, 16, 2);
        assertThat(records(runDir.resolve("embeddings.bin"))).extracting(Record::id).containsExactlyElementsOf(
            Stream.of(1L, 2L, 3L).flatMap(id -> Stream.generate(() -> id).limit(id == 1 ? 20 : id == 2 ? 3 : 18))
                .toList());
        assertThat(MAPPER.readTree(Files.readString(runDir.resolve("look.json")))).isEqualTo(MAPPER.readTree(
            "{\"look\": \"L5\", \"generatedAt\": \"2024-03-31T23:30:00Z\", \"toolGitCommit\": \"toolsha\", "
                + "\"testFilms\": 1}"));
        var json = MAPPER.readTree(Files.readString(runDir.resolve("embeddings.json")));
        assertThat(json.get("films")).isEqualTo(MAPPER.readTree("{\"dev\": 2, \"test\": 1}"));
        assertThat(json.get("rows")).isEqualTo(MAPPER.readTree("{\"dev\": 38, \"test\": 3}"));
        assertThat(json.get("devSha256").asString()).isEqualTo(MAPPER.readTree(
            Files.readString(other.resolve(RUN).resolve("embeddings.json"))).get("devSha256").asString());
    }

    @Test
    void twoRunsGiveByteIdenticalEmbeddingFiles() throws IOException {
        Path other = temp.resolve("out-2");

        job(new ColourMeanEncoder()).run(options(devRun, false));
        job(new ColourMeanEncoder()).run(options(devRun, false, other));

        assertThat(Files.readAllBytes(other.resolve(RUN).resolve("embeddings.bin")))
            .isEqualTo(Files.readAllBytes(out.resolve(RUN).resolve("embeddings.bin")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void twoRunsWithTheTinyOnnxEncoderGiveByteIdenticalEmbeddingFiles() throws IOException {
        Path other = temp.resolve("out-2");

        job(OnnxImageEncoder::new).run(options(devRun, false));
        job(OnnxImageEncoder::new).run(options(devRun, false, other));

        byte[] first = Files.readAllBytes(out.resolve(RUN).resolve("embeddings.bin"));
        assertThat(first).hasSize(20 + 38 * (int) EmbeddingFile.recordBytes(3));
        assertThat(Files.readAllBytes(other.resolve(RUN).resolve("embeddings.bin"))).isEqualTo(first);
    }

    @Test
    void aTestRowWithoutTheLookStopsTheRunBeforeAnyFrameIsOpened() {
        assertRefused(options(lookRun, false),
            "features.csv line 22: a test row is refused without --look=L5; nothing was written");
    }

    @Test
    void theLookOnAFeaturesRunWithoutLookJsonIsRefused() {
        assertRefused(options(devRun, true),
            "--look=L5 needs a look.json in the features run: " + devRun.toAbsolutePath().normalize());
    }

    @Test
    void aSummaryOfAnotherFeatureVersionIsRefused() throws IOException {
        Files.writeString(devRun.resolve("summary.json"), "{\"featureVersion\": 99}");

        assertRefused(options(devRun, false),
            "summary.json has feature version 99, the code has " + CreditsFeatures.FEATURE_VERSION);
    }

    @Test
    void aFilmWithNoRecordInFrameLabelsIsRefused() throws IOException {
        Files.writeString(devRun.resolve("frame-labels.json"), "[]");

        assertRefused(options(devRun, false), "film 1 has no record in frame-labels.json");
    }

    @Test
    void aMissingCompleteJsonIsRefused() throws IOException {
        Path complete = data.resolve("frames/3/complete.json");
        Files.delete(complete);

        assertRefused(options(devRun, false), "film 3: complete.json cannot be read: " + complete);
    }

    @Test
    void anUnusableCompleteJsonIsRefused() throws IOException {
        Files.writeString(data.resolve("frames/3/complete.json"), "{}");

        assertRefused(options(devRun, false), "film 3: complete.json is not usable");
    }

    @Test
    void aCompleteJsonOfAnotherMediaFileIsRefused() throws IOException {
        DatasetComplete.write(data.resolve("frames/3"),
            FeaturesFixtures.complete(99, FeaturesFixtures.FINGERPRINT, List.of(), List.of()));

        assertRefused(options(devRun, false), "film 3: complete.json is of media file 99");
    }

    @Test
    void aStaleFingerprintIsRefused() throws IOException {
        DatasetComplete.write(data.resolve("frames/3"), FeaturesFixtures.complete(3, "2000:2", List.of(), List.of()));

        assertRefused(options(devRun, false),
            "film 3: complete.json has fingerprint 2000:2, frame-labels.json says 1000:1");
    }

    @Test
    void aMissingJpegIsRefused() throws IOException {
        Files.delete(data.resolve("frames/1/dense/98500.jpg"));

        assertRefused(options(devRun, false), "film 1: dense/98500.jpg is not a file");
        Files.delete(data.resolve("frames/1/coarse/100000.jpg"));
        assertRefused(options(devRun, false), "film 1: coarse/100000.jpg is not a file");
    }

    @Test
    void aDatasetThatHashesDifferentlyFromTheSummaryIsRefused() throws Exception {
        Path complete = data.resolve("frames/3/complete.json");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(data.resolve("frames/1/complete.json")));
        Files.writeString(complete, Files.readString(complete) + "\n");
        digest.update(Files.readAllBytes(complete));

        assertRefused(options(devRun, false), "the development films' complete.json files hash to "
            + HexFormat.of().formatHex(digest.digest()) + ", summary.json says "
            + MAPPER.readTree(Files.readString(devRun.resolve("summary.json"))).get("datasetSha256").asString()
            + ": the dataset has changed since the features run");
    }

    @Test
    void anEncoderWhoseFileHashesDifferentlyIsRefused() throws IOException {
        Path bad = Files.createDirectory(temp.resolve("bad"));
        TinyFixture.withJson(bad, TinyFixture.json(TinyFixture.SHA256, "0".repeat(64)));

        assertRefused(new CreditsEmbedOptions(data, devRun, bad, out, false),
            "tiny.onnx has SHA-256 " + TinyFixture.SHA256 + ", encoder.json says " + "0".repeat(64));
    }

    @Test
    void aDataPathThatIsNotADirectoryIsRefused() {
        assertRefused(new CreditsEmbedOptions(temp.resolve("nope"), devRun, TinyFixture.dir(), out, false),
            "--data is not a directory: " + temp.resolve("nope"));
    }

    @Test
    void aFeaturesPathThatIsNotADirectoryIsRefused() {
        assertRefused(options(temp.resolve("nope"), false), "--features is not a directory: " + temp.resolve("nope"));
    }

    @Test
    void anOutputInsideTheDatasetIsRefusedEvenThroughASymlink() throws IOException {
        Path inside = Files.createSymbolicLink(temp.resolve("link"), data).resolve("o");

        assertRefused(options(devRun, false, inside), "--out must not be inside --data: " + inside);
        assertThat(data.resolve("o")).doesNotExist();
    }

    @Test
    void anOutputInsideTheEncodersDirectoryIsRefused() throws IOException {
        Path encoder = TinyFixture.withJson(Files.createDirectory(temp.resolve("enc")),
            Files.readString(TinyFixture.dir().resolve("encoder.json")));
        Path inside = Files.createSymbolicLink(temp.resolve("enc-link"), encoder).resolve("o");

        assertRefused(new CreditsEmbedOptions(data, devRun, encoder, inside, false),
            "--out must not be inside the encoder's directory: " + inside);
        assertThat(encoder.resolve("o")).doesNotExist();
    }

    @Test
    void aRunDirectoryThatExistsIsNeitherReusedNorDeleted() throws IOException {
        Path existing = Files.createDirectories(out.resolve(RUN));

        assertThatThrownBy(() -> job(new ColourMeanEncoder()).run(options(devRun, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("this run's directory already exists: " + out.toAbsolutePath().normalize().resolve(RUN));
        assertThat(existing).isEmptyDirectory();
        assertThat(opened).isEmpty();
    }

    @Test
    void aJpegOfTheWrongSizeFoundInPassTwoDeletesTheRunDirectoryAfterRecordsWereWritten() throws IOException {
        Files.write(data.resolve("frames/3/coarse/100000.jpg"), FramePrep.jpeg(new RgbFrame(4, 4, new byte[48])));
        ColourMeanEncoder encoder = new ColourMeanEncoder();

        assertThatThrownBy(() -> job(encoder).run(options(devRun, false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("film 3: coarse/100000.jpg is 4x4, the encoder takes 224x224");

        assertThat(encoder.batches).containsExactly(16, 1, 3);
        assertThat(encoder.closed).isTrue();
        assertLeftNothing();
        Files.write(data.resolve("frames/3/coarse/100000.jpg"), FramePrep.jpeg(new RgbFrame(224, 4, new byte[2688])));
        assertThatThrownBy(() -> job(new ColourMeanEncoder()).run(options(devRun, false)))
            .hasMessage("film 3: coarse/100000.jpg is 224x4, the encoder takes 224x224");
    }

    @Test
    void anEncoderThatFailsMidRunDeletesTheRunDirectory() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ColourMeanEncoder encoder = new ColourMeanEncoder(() -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("the encoder failed");
            }
        });

        assertThatThrownBy(() -> job(encoder).run(options(devRun, false)))
            .isInstanceOf(IllegalStateException.class).hasMessage("the encoder failed");

        assertThat(encoder.batches).hasSize(2);
        assertThat(encoder.closed).isTrue();
        assertLeftNothing();
    }

    @Test
    void anEncoderThatCannotBeOpenedLeavesNothing() throws IOException {
        EncoderFactory failing = (spec, dir) -> {
            throw new IllegalStateException("cannot open tiny.onnx");
        };

        assertThatThrownBy(() -> job(failing).run(options(devRun, false)))
            .isInstanceOf(IllegalStateException.class).hasMessage("cannot open tiny.onnx");

        assertLeftNothing();
    }

    @Test
    void anEmbeddingThatIsNotFiniteLeavesNothing() throws IOException {
        ImageEncoder nan = new ImageEncoder() {
            @Override
            public float[][] encode(List<RgbFrame> frames) {
                float[][] rows = new float[frames.size()][3];
                rows[0][1] = Float.NaN;
                return rows;
            }

            @Override
            public void close() {
            }
        };

        assertThatThrownBy(() -> job((spec, dir) -> nan).run(options(devRun, false)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("the embedding holds a NaN or infinite value");

        assertLeftNothing();
    }

    @Test
    void aFailureToDeleteTheRunDirectoryIsAddedToTheOriginalFailure() {
        ColourMeanEncoder encoder = new ColourMeanEncoder(() -> {
            try {
                Files.createDirectories(out.resolve(RUN).resolve("stuck"));
                Files.writeString(out.resolve(RUN).resolve("stuck/file"), "x");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("the encoder failed");
        });

        assertThatThrownBy(() -> job(encoder).run(options(devRun, false)))
            .isInstanceOf(IllegalStateException.class).hasMessage("the encoder failed")
            .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                .isInstanceOf(DirectoryNotEmptyException.class));
    }
}
