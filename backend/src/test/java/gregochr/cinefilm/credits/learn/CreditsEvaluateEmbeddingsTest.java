package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.credits.learn.EmbeddingCursor.FilmEmbeddings;
import gregochr.cinefilm.credits.learn.FeatureRunReader.FilmRows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The evaluation tool on model B: hand-made {@code "logreg"} runs scored on an embeddings run. */
class CreditsEvaluateEmbeddingsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RUN = "eval-20240331T233000Z";
    private static final String ZEROS = "0".repeat(64);
    /** {@code p} of an embedding whose first value is 0 and of one whose first value is 1, in the fixture runs. */
    private static final double LOW = 0.2606492294156818;
    private static final double HIGH = 0.7393507705843182;

    @TempDir
    Path temp;

    private CreditsEvaluateJob job;
    private Path lr;

    @BeforeEach
    void setUp() throws IOException {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsEvaluateJob(TestData.CLOCK, git);
        lr = EmbeddedEvaluateFixtures.logregRun(temp, "train-lr");
    }

    private Path out() {
        return temp.resolve("out").toAbsolutePath().normalize();
    }

    private List<Path> run(String... args) throws IOException {
        List<String> all = new ArrayList<>(List.of("--out", out().toString()));
        all.addAll(List.of(args));
        return job.run(CreditsEvaluateOptions.parse(all.toArray(String[]::new)));
    }

    private List<Path> run(Path features, Path embeddings, String... more) throws IOException {
        List<String> args = new ArrayList<>(List.of("--features", features.toString(), "--train", lr.toString(),
            "--embeddings", embeddings.toString()));
        args.addAll(List.of(more));
        return run(args.toArray(String[]::new));
    }

    private void refused(String message, Path features, Path embeddings, String... more) {
        assertThatThrownBy(() -> run(features, embeddings, more)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
        assertThat(out()).doesNotExist();
    }

    private Path embeddings(Path features) throws IOException {
        return EmbeddedEvaluateFixtures.embeddingsRun(temp, features);
    }

    private JsonNode json(String name) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(out().resolve(RUN).resolve(name)));
    }

    private static String params(String lambda) {
        return "{\"lambda\":" + lambda + ",\"delta\":20.0,\"gapFrames\":2,\"sigma\":5.0,\"theta\":0.7,"
            + "\"thetaDense\":0.95}";
    }

    // ---- scoring ----

    @Test
    void aModelBOnlyEvaluationScoresEveryDevelopmentFilmFromItsEmbeddingsAndWritesThreeFiles() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        List<Path> written = run(features, embeddings(features));

        Path dir = out().resolve(RUN);
        assertThat(written).containsExactly(dir, dir.resolve("evaluation.csv"), dir.resolve("calibration.json"),
            dir.resolve("summary.json"));
        assertThat(Files.readString(dir.resolve("evaluation.csv"))).isEqualTo(EvaluationReport.HEADER
            + "1,dev,0,FOUND,130000,,false,true,5000,10000,DARK,low,DARK,,,train-lr,false\n"
            + "2,dev,1,FOUND,130000,,false,true,5000,10000,DARK,low,DARK,,,train-lr,false\n"
            + "3,dev,2,FOUND,130000,,false,true,5000,10000,PLATE,low,PLATE,,,train-lr,true\n"
            + "4,dev,3,FOUND,130000,,false,true,5000,10000,PLATE,low,PLATE,,,train-lr,\n"
            + "5,dev,4,FOUND,130000,,false,true,5000,10000,LIT,low,LIT,,,train-lr,false\n"
            + "6,dev,0,FOUND,130000,,false,true,5000,10000,LIT,low,LIT,,,train-lr,false\n"
            + "7,dev,1,FOUND,130000,,false,true,5000,10000,LIT,low,,,,train-lr,false\n"
            + "8,dev,2,FOUND,130000,,false,true,5000,10000,LIT,low,,,,train-lr,false\n"
            + "9,dev,3,FOUND,130000,,false,true,5000,10000,LIT,low,PLATE,,,train-lr,false\n"
            + "10,dev,4,FOUND,130000,,false,true,5000,10000,LIT,low,PLATE,,,train-lr,false\n");
        JsonNode calibration = json("calibration.json");
        assertThat(calibration.get("grid").get("points").intValue()).isEqualTo(42_000);
        assertThat(calibration.get("outer").propertyNames()).containsExactly("0", "1", "2", "3", "4");
        int[] within = {5, 5, 5, 4, 5};
        for (int k = 0; k < within.length; k++) {
            JsonNode outer = calibration.get("outer").get(Integer.toString(k));
            assertThat(outer.get("run").stringValue()).isEqualTo("train-lr");
            assertThat(outer.get("params").toString()).isEqualTo(params("0.0"));
            assertThat(outer.get("counts").toString()).isEqualTo("{\"early15\":0,\"early5\":0,\"violations\":0,"
                + "\"within10\":" + within[k] + ",\"within60\":" + within[k] + ",\"films\":8}");
        }
        assertThat(calibration.get("final").get("run").stringValue()).isEqualTo("train-lr");
        assertThat(calibration.get("final").get("params").toString()).isEqualTo(params("0.0"));
        assertThat(calibration.get("final").get("counts").toString()).isEqualTo("{\"early15\":0,\"early5\":0,"
            + "\"violations\":0,\"within10\":6,\"within60\":6,\"films\":10}");
        assertThat(calibration.get("toolGitCommit").stringValue()).isEqualTo("toolsha");
        assertThat(calibration.get("datasetSha256").stringValue()).isEqualTo("ds-hash");
        assertThat(calibration.get("labelsSha256").stringValue()).isEqualTo("labels-hash");
        JsonNode summary = json("summary.json");
        assertThat(summary.get("generatedAt").stringValue()).isEqualTo("2024-03-31T23:30:00Z");
        assertThat(summary.has("test")).isFalse();
        assertThat(summary.get("development").get("films").intValue()).isEqualTo(10);
        assertThat(summary.get("development").get("early").get("LIT").get("low").get("found").intValue())
            .isEqualTo(6);
        assertThat(summary.get("development").get("goals").get("none").get("films").intValue()).isEqualTo(2);
        assertThat(summary.get("development").get("goals").get("PLATE").get("within10s").intValue()).isEqualTo(4);
    }

    @Test
    void theLookScoresTestFilmsByTheFinalRunsShippingModelsOnTheirEmbeddingsAndWritesLookJson()
        throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path embeddings = embeddings(features);
        Files.writeString(embeddings.resolve("look.json"), "{}");

        List<Path> written = run(features, embeddings, "--look=L5");

        Path dir = out().resolve(RUN);
        assertThat(written).containsExactly(dir, dir.resolve("evaluation.csv"), dir.resolve("calibration.json"),
            dir.resolve("summary.json"), dir.resolve("look.json"));
        assertThat(Files.readString(dir.resolve("evaluation.csv"))).contains("\n10,dev,4,FOUND,130000,").endsWith(
            "\n11,test,,FOUND,130000,,false,true,5000,10000,LIT,low,LIT,,,train-lr,false\n"
            + "12,test,,FOUND,130000,,false,true,5000,10000,LIT,low,LIT,,,train-lr,false\n");
        assertThat(json("look.json").toString()).isEqualTo("{\"look\":\"L5\",\"generatedAt\":"
            + "\"2024-03-31T23:30:00Z\",\"toolGitCommit\":\"toolsha\",\"testFilms\":2}");
        assertThat(json("summary.json").get("test").get("films").intValue()).isEqualTo(2);
        assertThat(json("summary.json").get("development").get("films").intValue()).isEqualTo(10);
    }

    @Test
    void testFilmsAreScoredByTheShippingModelsOfTheFinalChoicesRunWhicheverPlaceItHasInTheList()
        throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path embeddings = embeddings(features);
        Files.writeString(embeddings.resolve("look.json"), "{}");
        Path second = EmbeddedEvaluateFixtures.logregRun(temp, "train-lr2");
        EvaluateFixtures.shipWith(second, EmbeddedEvaluateFixtures.logregRun(temp, "train-inv", true));

        run("--features", features.toString(), "--train", lr.toString(), "--train", second.toString(),
            "--embeddings", embeddings.toString(), "--look=L5");
        List<String> firstListed = Files.readAllLines(out().resolve(RUN).resolve("evaluation.csv")).subList(11, 13);
        Path other = temp.resolve("other");
        job.run(CreditsEvaluateOptions.parse(new String[] {"--features", features.toString(), "--out",
            other.toString(), "--train", second.toString(), "--train", lr.toString(), "--embeddings",
            embeddings.toString(), "--look=L5"}));
        List<String> secondListed = Files.readAllLines(other.resolve(RUN).resolve("evaluation.csv")).subList(11, 13);

        assertThat(firstListed).containsExactly(
            "11,test,,FOUND,130000,,false,true,5000,10000,LIT,low,LIT,,,train-lr,false",
            "12,test,,FOUND,130000,,false,true,5000,10000,LIT,low,LIT,,,train-lr,false");
        assertThat(secondListed).containsExactly("11,test,,NOT_FOUND,,,,,,,,,LIT,,,train-lr2,false",
            "12,test,,NOT_FOUND,,,,,,,,,LIT,,,train-lr2,false");
    }

    @Test
    void aDenseFrameWithoutTimeFeaturesGetsNoPAlthoughItHasAnEmbedding() throws IOException {
        Path csv = Files.writeString(temp.resolve("features.csv"), FeatureRows.header()
            + EvaluateFixtures.film(1, Side.DEV));
        FilmRows rows = FeatureRunReader.read(csv, false).getFirst();
        FilmEmbeddings embeddings = new FilmEmbeddings(
            rows.coarseOffsets().stream().map(o -> EmbeddedEvaluateFixtures.vector(new long[] {1, 0, o}))
                .toArray(float[][]::new),
            rows.denseOffsets().stream().map(o -> EmbeddedEvaluateFixtures.vector(new long[] {1, 1, o}))
                .toArray(float[][]::new));
        FrameModel model = TrainedRun.load(lr, new CreditsTrainJob.Hashes("ds-hash", "labels-hash")).shipping()
            .getFirst();

        FilmScore.Prediction prediction = CreditsEvaluateJob.predict(model, rows, embeddings);

        assertThat(embeddings.dense()[0]).containsExactly(0f, 0f);
        assertThat(embeddings.dense()[100]).containsExactly(1f, 0f);
        assertThat(prediction.denseP()).hasSize(101);
        assertThat(prediction.denseP()[0]).isNaN();
        assertThat(prediction.denseP()[100]).isNaN();
        for (int i = 1; i < 100; i++) {
            assertThat(prediction.denseP()[i]).as("dense " + i).isEqualTo(i < 60 ? LOW : HIGH);
        }
        assertThat(prediction.coarseP()).containsExactly(LOW, LOW, LOW, LOW, HIGH, HIGH, HIGH, HIGH, HIGH, HIGH,
            HIGH, HIGH);
        assertThat(prediction.logitPi()).isZero();
    }

    @Test
    void aFilmOfOneCoarseFrameWithNoTimeFeaturesIsNotFoundWithModelB() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Files.writeString(features.resolve("features.csv"), FeatureRows.header() + EvaluateFixtures.lonely()
            + EvaluateFixtures.development());

        run(features, embeddings(features));

        assertThat(Files.readAllLines(out().resolve(RUN).resolve("evaluation.csv")).get(1))
            .isEqualTo("0,dev,4,NOT_FOUND,,,,,,,,,DARK,,,train-lr,false");
    }

    // ---- the flag and the model type ----

    @Test
    void aLogregRunWithoutEmbeddingsIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        assertThatThrownBy(() -> run("--features", features.toString(), "--train", lr.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("the training runs are logreg, which are "
                + "scored on embeddings: pass --embeddings <embeddings run>");
        assertThat(out()).doesNotExist();
    }

    @Test
    void embeddingsWithAModelARunAreRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path trainA = EvaluateFixtures.trainRun(temp, "train-a");

        assertThatThrownBy(() -> run("--features", features.toString(), "--train", trainA.toString(),
            "--embeddings", embeddings(features).toString())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--embeddings is for logreg runs, but the training runs are gbt");
        assertThat(out()).doesNotExist();
    }

    // ---- the embeddings run must be the one the runs were trained on ----

    @Test
    void anEmbeddingsRunOfAnotherFeaturesCsvIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path embeddings = embeddings(features);
        EmbeddedEvaluateFixtures.edit(embeddings, json -> json.put("featuresCsvSha256", ZEROS));

        refused("embeddings.json's featuresCsvSha256 is " + ZEROS + ", features.csv's SHA-256 is "
            + LrFixtures.sha256(features.resolve("features.csv")), features, embeddings);
    }

    @Test
    void anEmbeddingsRunOfAnotherDatasetOrLabelsOrFeatureVersionIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path embeddings = embeddings(features);

        EmbeddedEvaluateFixtures.edit(embeddings, json -> json.put("datasetSha256", "other"));
        refused("embeddings.json's datasetSha256 is other, summary.json's is ds-hash", features, embeddings);
        EmbeddedEvaluateFixtures.edit(embeddings, json -> json.put("datasetSha256", "ds-hash").put("labelsSha256",
            "other"));
        refused("embeddings.json's labelsSha256 is other, summary.json's is labels-hash", features, embeddings);
        EmbeddedEvaluateFixtures.edit(embeddings, json -> json.put("labelsSha256", "labels-hash")
            .put("featureVersion", CreditsFeatures.FEATURE_VERSION + 1));
        refused("embeddings.json has feature version " + (CreditsFeatures.FEATURE_VERSION + 1) + ", the code has "
            + CreditsFeatures.FEATURE_VERSION, features, embeddings);
    }

    @Test
    void aRunTrainedOnAnotherEncoderOrOtherEmbeddingsIsRefusedWhicheverRunItIsAndNothingIsWritten()
        throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path embeddings = embeddings(features);
        Path second = EmbeddedEvaluateFixtures.logregRun(temp, "train-lr2");

        EmbeddedEvaluateFixtures.editModel(second, model -> ((ObjectNode) model.get("encoder")).put("sha256",
            "other-encoder"));
        assertThatThrownBy(() -> run("--features", features.toString(), "--train", lr.toString(), "--train",
            second.toString(), "--embeddings", embeddings.toString())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-lr2: its encoder's sha256 is other-encoder, embeddings.json's is "
                + LrFixtures.ENCODER_SHA);
        EmbeddedEvaluateFixtures.editModel(second, model -> {
            ((ObjectNode) model.get("encoder")).put("sha256", LrFixtures.ENCODER_SHA);
            model.put("embeddingsDevSha256", "other-dev");
        });
        assertThatThrownBy(() -> run("--features", features.toString(), "--train", lr.toString(), "--train",
            second.toString(), "--embeddings", embeddings.toString())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-lr2: it was trained on embeddings with devSha256 other-dev, embeddings.json's is "
                + LrFixtures.DEV_SHA);
        assertThat(out()).doesNotExist();
    }

    @Test
    void anEncoderOfAnotherDimensionThanTheEmbeddingsIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path embeddings = embeddings(features);
        EmbeddedEvaluateFixtures.editModel(lr, model -> ((ObjectNode) model.get("encoder")).put("dimension", 3));

        refused("train-lr: its encoder's dimension is 3, embeddings.json's is 2", features, embeddings);

        EmbeddedEvaluateFixtures.edit(embeddings, json -> ((ObjectNode) json.get("encoder")).put("dimension", 3));
        refused("embeddings.bin has dimension 2, embeddings.json's encoder says 3", features, embeddings);
    }

    @Test
    void theLookWithAnEmbeddingsRunMadeWithoutItIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path embeddings = embeddings(features);

        refused("--look=L5 needs an embeddings run made with --look=L5: no look.json in " + embeddings, features,
            embeddings, "--look=L5");
    }

    // ---- the records must be the rows ----

    @Test
    void aRecordWhoseKeyIsNotItsRowsIsRefusedWhereverItIsAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        refused("embeddings.bin record 0 is (mediaFileId 1, kind 0, offsetMs 100001), features.csv data row 0 is "
                + "(mediaFileId 1, kind 0, offsetMs 100000)", features,
            EmbeddedEvaluateFixtures.embeddingsRun(temp, features,
                (i, key) -> i == 0 ? new long[] {key[0], key[1], key[2] + 1} : key, 0));
        Files.delete(temp.resolve(EmbeddedEvaluateFixtures.EMBEDDINGS_RUN).resolve("embeddings.bin"));
        refused("embeddings.bin record 12 is (mediaFileId 1, kind 0, offsetMs 90000), features.csv data row 12 is "
                + "(mediaFileId 1, kind 1, offsetMs 90000)", features,
            EmbeddedEvaluateFixtures.embeddingsRun(temp, features,
                (i, key) -> i == 12 ? new long[] {key[0], 0, key[2]} : key, 0));
        Files.delete(temp.resolve(EmbeddedEvaluateFixtures.EMBEDDINGS_RUN).resolve("embeddings.bin"));
        refused("embeddings.bin record 1129 is (mediaFileId 11, kind 1, offsetMs 140000), features.csv data row "
                + "1129 is (mediaFileId 10, kind 1, offsetMs 140000)", features,
            EmbeddedEvaluateFixtures.embeddingsRun(temp, features,
                (i, key) -> i == 1129 ? new long[] {11, key[1], key[2]} : key, 0));
    }

    @Test
    void aRecordWhoseKeyDiffersOnlyInTheLastRowIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        refused("embeddings.bin record 1129 is (mediaFileId 10, kind 1, offsetMs 140001), features.csv data row "
                + "1129 is (mediaFileId 10, kind 1, offsetMs 140000)", features,
            EmbeddedEvaluateFixtures.embeddingsRun(temp, features,
                (i, key) -> i == 1129 ? new long[] {key[0], key[1], key[2] + 1} : key, 0));
    }

    @Test
    void aRecordCountOtherThanTheRowCountIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        refused("embeddings.bin has 1129 records, fewer than features.csv's data rows", features,
            EmbeddedEvaluateFixtures.embeddingsRun(temp, features, (i, key) -> key, -1));
        Files.delete(temp.resolve(EmbeddedEvaluateFixtures.EMBEDDINGS_RUN).resolve("embeddings.bin"));
        refused("embeddings.bin has 1131 records, features.csv has 1130 data rows", features,
            EmbeddedEvaluateFixtures.embeddingsRun(temp, features, (i, key) -> key, 1));
    }

    // ---- a model B run written by the trainer ----

    @Test
    void aRunWrittenByTheModelBTrainerIsReadBackAndEvaluatedEndToEnd() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path embeddings = embeddings(features);
        Path manifest = Files.writeString(temp.resolve("split.json"), TrainFixtures.MANIFEST_TEXT);
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("trainsha");
        List<Path> trained = new CreditsTrainLrJob(TestData.CLOCK, git).run(CreditsTrainLrOptions.parse(
            new String[] {"--features", features.resolve("features.csv").toString(), "--summary",
                features.resolve("summary.json").toString(), "--manifest", manifest.toString(), "--out",
                temp.resolve("train-out").toString(), "--embeddings", embeddings.toString()}));
        Path runDir = trained.getFirst();

        TrainedRun loaded = TrainedRun.load(runDir, new CreditsTrainJob.Hashes("ds-hash", "labels-hash"));
        run("--features", features.toString(), "--train", runDir.toString(), "--embeddings", embeddings.toString());

        assertThat(loaded.type()).isEqualTo("logreg");
        assertThat(loaded.encoder()).isEqualTo(new TrainedRun.Encoder(LrFixtures.ENCODER_SHA, 2));
        assertThat(loaded.embeddingsDevSha256()).isEqualTo(LrFixtures.DEV_SHA);
        assertThat(loaded.shipping()).hasSize(5);
        assertThat(loaded.pairs()).hasSize(10);
        assertThat(loaded.triples()).hasSize(10);
        String run = ",train-lr-20240331T233000Z,";
        assertThat(Files.readString(out().resolve(RUN).resolve("evaluation.csv"))).isEqualTo(EvaluationReport.HEADER
            + "1,dev,0,FOUND,120000,120000,true,true,-5000,0,DARK,low,DARK,," + run + "false\n"
            + "2,dev,1,FOUND,120000,120000,true,true,-5000,0,DARK,low,DARK,," + run + "false\n"
            + "3,dev,2,FOUND,120000,120000,true,true,-5000,0,PLATE,low,PLATE,," + run + "true\n"
            + "4,dev,3,FOUND,120000,120000,true,true,-5000,0,PLATE,low,PLATE,," + run + "\n"
            + "5,dev,4,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,," + run + "false\n"
            + "6,dev,0,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,," + run + "false\n"
            + "7,dev,1,FOUND,120000,120000,true,true,-5000,0,LIT,low,,," + run + "false\n"
            + "8,dev,2,FOUND,120000,120000,true,true,-5000,0,LIT,low,,," + run + "false\n"
            + "9,dev,3,FOUND,120000,120000,true,true,-5000,0,LIT,low,PLATE,," + run + "false\n"
            + "10,dev,4,FOUND,120000,120000,true,true,-5000,0,LIT,low,PLATE,," + run + "false\n");
        assertThat(json("calibration.json").get("final").get("run").stringValue())
            .isEqualTo("train-lr-20240331T233000Z");
    }
}
