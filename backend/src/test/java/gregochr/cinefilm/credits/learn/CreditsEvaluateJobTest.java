package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The evaluation tool on a fixture features run and fixture training runs ({@link EvaluateFixtures}). */
class CreditsEvaluateJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RUN = "eval-20240331T233000Z";
    private static final String HEADER = EvaluationReport.HEADER;

    @TempDir
    Path temp;

    private CreditsEvaluateJob job;
    private Path trainA;

    @BeforeEach
    void setUp() throws IOException {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsEvaluateJob(TestData.CLOCK, git);
        trainA = EvaluateFixtures.trainRun(temp, "train-a");
    }

    private Path out() {
        return temp.resolve("out").toAbsolutePath().normalize();
    }

    private List<Path> run(Path features, String... more) throws IOException {
        List<String> args = new ArrayList<>(List.of("--features", features.toString(), "--out", out().toString()));
        args.addAll(List.of(more));
        return job.run(CreditsEvaluateOptions.parse(args.toArray(String[]::new)));
    }

    private void refused(Path features, String message, String... more) {
        List<String> args = new ArrayList<>(List.of("--train", trainA.toString()));
        args.addAll(List.of(more));
        assertThatThrownBy(() -> run(features, args.toArray(String[]::new)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
        assertThat(out()).doesNotExist();
    }

    private JsonNode json(String name) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(out().resolve(RUN).resolve(name)));
    }

    private String evaluation() throws IOException {
        return Files.readString(out().resolve(RUN).resolve("evaluation.csv"));
    }

    @Test
    void aRunWithoutTheLookScoresEveryDevelopmentFilmAndWritesThreeFiles() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        List<Path> written = run(features, "--train", trainA.toString());

        Path dir = out().resolve(RUN);
        assertThat(written).containsExactly(dir, dir.resolve("evaluation.csv"), dir.resolve("calibration.json"),
            dir.resolve("summary.json"));
        assertThat(evaluation()).isEqualTo(HEADER
            + "1,dev,0,FOUND,120000,120000,true,true,-5000,0,DARK,low,DARK,,,train-a,false\n"
            + "2,dev,1,FOUND,120000,120000,true,true,-5000,0,DARK,low,DARK,,,train-a,false\n"
            + "3,dev,2,FOUND,120000,120000,true,true,-5000,0,PLATE,low,PLATE,,,train-a,true\n"
            + "4,dev,3,FOUND,120000,120000,true,true,-5000,0,PLATE,low,PLATE,,,train-a,\n"
            + "5,dev,4,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false\n"
            + "6,dev,0,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false\n"
            + "7,dev,1,FOUND,120000,120000,true,true,-5000,0,LIT,low,,,,train-a,false\n"
            + "8,dev,2,FOUND,120000,120000,true,true,-5000,0,LIT,low,,,,train-a,false\n"
            + "9,dev,3,FOUND,120000,120000,true,true,-5000,0,LIT,low,PLATE,,,train-a,false\n"
            + "10,dev,4,FOUND,120000,120000,true,true,-5000,0,LIT,low,PLATE,,,train-a,false\n");
        JsonNode calibration = json("calibration.json");
        assertThat(calibration.get("grid").get("points").intValue()).isEqualTo(42_000);
        assertThat(calibration.get("outer").propertyNames()).containsExactly("0", "1", "2", "3", "4");
        JsonNode fold2 = calibration.get("outer").get("2");
        assertThat(fold2.get("run").stringValue()).isEqualTo("train-a");
        assertThat(fold2.get("params").toString()).isEqualTo("{\"lambda\":0.5,\"delta\":2.0,\"gapFrames\":2,"
            + "\"sigma\":30.0,\"theta\":0.95,\"thetaDense\":0.95}");
        assertThat(fold2.get("counts").toString()).isEqualTo("{\"early15\":0,\"early5\":0,\"violations\":0,"
            + "\"within10\":5,\"within60\":5,\"films\":8}");
        assertThat(calibration.get("final").get("params").get("lambda").doubleValue()).isEqualTo(1.0);
        assertThat(calibration.get("final").get("counts").get("films").intValue()).isEqualTo(10);
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
    }

    @Test
    void theLookScoresTestFilmsWithTheFinalChoiceAndWritesLookJson() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);

        List<Path> written = run(features, "--train", trainA.toString(), "--look=L5");

        assertThat(written).hasSize(5).last().isEqualTo(out().resolve(RUN).resolve("look.json"));
        assertThat(evaluation()).contains("\n10,dev,4,FOUND").endsWith(
            "\n11,test,,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false\n"
            + "12,test,,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false\n");
        assertThat(json("look.json").toString()).isEqualTo("{\"look\":\"L5\",\"generatedAt\":"
            + "\"2024-03-31T23:30:00Z\",\"toolGitCommit\":\"toolsha\",\"testFilms\":2}");
        assertThat(json("summary.json").get("test").get("films").intValue()).isEqualTo(2);
        assertThat(json("summary.json").get("test").get("goals").get("LIT").get("films").intValue()).isEqualTo(2);
        assertThat(json("summary.json").get("development").get("films").intValue()).isEqualTo(10);
    }

    @Test
    void theSameInputsGiveByteIdenticalFiles() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path trainB = EvaluateFixtures.trainRun(temp, "train-b");
        run(features, "--train", trainA.toString(), "--train", trainB.toString(), "--look=L5");
        Path second = temp.resolve("second");
        job.run(CreditsEvaluateOptions.parse(new String[] {"--features", features.toString(), "--out",
            second.toString(), "--train", trainA.toString(), "--train", trainB.toString(), "--look=L5"}));

        for (String name : List.of("evaluation.csv", "calibration.json", "summary.json", "look.json")) {
            assertThat(Files.readAllBytes(second.resolve(RUN).resolve(name)))
                .isEqualTo(Files.readAllBytes(out().resolve(RUN).resolve(name)));
        }
        assertThat(json("calibration.json").get("final").get("run").stringValue()).isEqualTo("train-a");
    }

    @Test
    void aSecondRunInTheSameSecondIsRefusedAndTheFirstIsKept() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        run(features, "--train", trainA.toString());

        assertThatThrownBy(() -> run(features, "--train", trainA.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        assertThat(evaluation()).startsWith(HEADER);
    }

    @Test
    void aTestRowWithoutTheLookIsRefusedAndNothingIsWritten() throws IOException {
        refused(EvaluateFixtures.featuresRun(temp, true), "a test row is refused without --look=L5");
    }

    @Test
    void theLookOnAFeaturesRunWithNoLookJsonIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Files.delete(features.resolve("look.json"));

        refused(features, "no look.json", "--look=L5");
    }

    @Test
    void aTrainingRunOnOtherDataOrAnotherSplitIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path other = EvaluateFixtures.trainRun(temp, "train-other");
        Path model = other.resolve("model.json");
        String text = Files.readString(model);
        Files.writeString(model, text.replace("\"manifestSha256\" : \"", "\"manifestSha256\" : \"x"));
        refused(features, "train-other: manifestSha256 differs from train-a's", "--train", other.toString());
        Files.writeString(model, text.replace("ds-hash", "other"));
        refused(features, "train-other: datasetSha256 differs", "--train", other.toString());
        Files.writeString(model, text.replace("labels-hash", "other"));
        refused(features, "train-other: labelsSha256 differs", "--train", other.toString());
    }

    @Test
    void aFeatureVersionMismatchIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path nested = trainA.resolve("nested-models.json");
        Files.writeString(nested, Files.readString(nested).replace("\"featureVersion\" : "
            + CreditsFeatures.FEATURE_VERSION, "\"featureVersion\" : 99"));

        refused(features, "train-a: the model has feature version 99");
    }

    @Test
    void aRunMadeBeforeNestedModelsExistedIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Files.delete(trainA.resolve("nested-models.json"));

        assertThatThrownBy(() -> run(features, "--train", trainA.toString())).isInstanceOf(NoSuchFileException.class);
        assertThat(out()).doesNotExist();
    }

    @Test
    void aFilmWithNoTargetInTheLabelsIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Files.writeString(features.resolve("frame-labels.json"), EvaluateFixtures.labels(9));

        refused(features, "film 10 has no target in frame-labels.json");
    }

    @Test
    void aFilmWhoseLabelsHaveNoTargetIsRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        String labels = Files.readString(features.resolve("frame-labels.json"));
        int last = labels.lastIndexOf("\"target_start_ms\":120000");
        Files.writeString(features.resolve("frame-labels.json"),
            labels.substring(0, last) + "\"target_start_ms\":null" + labels.substring(last + 24));

        refused(features, "film 10 has no target in frame-labels.json");
    }

    @Test
    void testFilmsAreScoredByTheShippingModelsOfTheFinalChoicesRun() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path inverted = EvaluateFixtures.trainRun(temp, "train-inv", true);
        run(features, "--train", inverted.toString(), "--train", trainA.toString(), "--look=L5");
        List<String> before = evaluation().lines().skip(1).toList();
        EvaluateFixtures.shipWith(trainA, inverted);
        Path second = temp.resolve("second");

        job.run(CreditsEvaluateOptions.parse(new String[] {"--features", features.toString(), "--out",
            second.toString(), "--train", inverted.toString(), "--train", trainA.toString(), "--look=L5"}));

        List<String> after = Files.readAllLines(second.resolve(RUN).resolve("evaluation.csv")).stream().skip(1)
            .toList();
        assertThat(after.subList(0, 10)).isEqualTo(before.subList(0, 10));
        assertThat(MAPPER.readTree(Files.readAllBytes(second.resolve(RUN).resolve("calibration.json")))
            .get("final").get("run").stringValue()).isEqualTo("train-a");
        assertThat(before.subList(10, 12)).allMatch(line -> line.contains(",FOUND,120000,")
            && line.endsWith(",train-a,false"));
        assertThat(after.subList(10, 12)).containsExactly("11,test,,NOT_FOUND,,,,,,,,,LIT,,,train-a,false",
            "12,test,,NOT_FOUND,,,,,,,,,LIT,,,train-a,false");
    }

    @Test
    void theFinalRunsShippingModelsScoreTheTestFilmsWhicheverPlaceItHasInTheList() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path trainB = EvaluateFixtures.trainRun(temp, "train-b");
        EvaluateFixtures.shipWith(trainB, EvaluateFixtures.trainRun(temp, "train-inv", true));

        run(features, "--train", trainA.toString(), "--train", trainB.toString(), "--look=L5");
        List<String> withAFirst = evaluation().lines().skip(11).toList();
        Path second = temp.resolve("second");
        job.run(CreditsEvaluateOptions.parse(new String[] {"--features", features.toString(), "--out",
            second.toString(), "--train", trainB.toString(), "--train", trainA.toString(), "--look=L5"}));
        List<String> withBFirst = Files.readAllLines(second.resolve(RUN).resolve("evaluation.csv")).stream().skip(11)
            .toList();

        assertThat(MAPPER.readTree(Files.readAllBytes(second.resolve(RUN).resolve("calibration.json")))
            .get("final").get("run").stringValue()).isEqualTo("train-b");
        assertThat(json("calibration.json").get("final").get("run").stringValue()).isEqualTo("train-a");
        assertThat(withAFirst).containsExactly(
            "11,test,,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false",
            "12,test,,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false");
        assertThat(withBFirst).containsExactly("11,test,,NOT_FOUND,,,,,,,,,LIT,,,train-b,false",
            "12,test,,NOT_FOUND,,,,,,,,,LIT,,,train-b,false");
    }

    @Test
    void trainingRunsOfMoreThanOneTypeAreRefusedAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path logreg = EvaluateFixtures.logregRun(temp, "train-lr");

        assertThatThrownBy(() -> run(features, "--train", trainA.toString(), "--train", logreg.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("train-lr: the training runs are of more than "
                + "one type: train-a is gbt and train-lr is logreg; evaluate one type at a time");
        assertThatThrownBy(() -> run(features, "--train", logreg.toString(), "--train", trainA.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("train-a: the training runs are of more than "
                + "one type: train-lr is logreg and train-a is gbt; evaluate one type at a time");
        assertThat(out()).doesNotExist();
    }

    @Test
    void aLogregRunIsRefusedUntilEmbeddingsCanBeReadAndNothingIsWritten() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path logreg = EvaluateFixtures.logregRun(temp, "train-lr");
        Path second = EvaluateFixtures.logregRun(temp, "train-lr2");

        assertThatThrownBy(() -> run(features, "--train", logreg.toString(), "--train", second.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("train-lr: a logreg run is scored on "
                + "embeddings, which this tool cannot read yet; nothing was written");
        assertThat(out()).doesNotExist();
    }

    @Test
    void aLogisticRegressionIsNotScoredFromFeatureRows() throws IOException {
        FeatureRunReader.FilmRows rows = FeatureRunReader.read(Files.writeString(temp.resolve("features.csv"),
            FeatureRows.header() + EvaluateFixtures.lonely()), false).getFirst();
        FrameModel model = TrainedRun.load(EvaluateFixtures.logregRun(temp, "train-lr"),
            new CreditsTrainJob.Hashes("ds-hash", "labels-hash")).shipping().getFirst();

        assertThatThrownBy(() -> CreditsEvaluateJob.predict(model, rows)).isInstanceOf(IllegalStateException.class)
            .hasMessage("a LogisticRegression predicts from embeddings, not from feature rows");
    }

    @Test
    void theBaselineJoinsOnMediaFileIdAndFingerprintAndScoresV2ByItsOffset() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path baseline = Files.writeString(temp.resolve("v2.csv"),
            "media_file_id,fingerprint,verdict,offset_ms,reasons,checked_at,analysis_version\n"
                + "1,fp1,FOUND,131500,,2024-01-01,2\n"
                + "2,wrong,FOUND,125000,,2024-01-01,2\n"
                + "3,fp3,UNSURE,,\"a,b\",2024-01-01,2\n"
                + "11,fp11,FOUND,100000,,2024-01-01,2\n");

        run(features, "--train", trainA.toString(), "--look=L5", "--baseline", baseline.toString());

        List<String> lines = evaluation().lines().toList();
        List<String> v2 = new ArrayList<>();
        for (int id : List.of(1, 2, 3, 4, 11)) {
            String[] fields = lines.get(id).split(",", -1);
            v2.add(fields[13] + "/" + fields[14]);
        }
        assertThat(v2).containsExactly("FOUND/11500", "/", "UNSURE/", "/", "FOUND/-20000");
    }

    @Test
    void aBaselineProblemIsRefused() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path baseline = Files.writeString(temp.resolve("v2.csv"), "media_file_id,fingerprint,verdict\n");
        refused(features, "the baseline has no column offset_ms", "--look=L5", "--baseline", baseline.toString());
        Files.writeString(baseline, "media_file_id,fingerprint,verdict,offset_ms\n1,fp1,UNSURE,\n1,fp1,UNSURE,\n");
        refused(features, "the baseline repeats media file 1", "--look=L5", "--baseline", baseline.toString());
        Files.writeString(baseline, "media_file_id,fingerprint,verdict,offset_ms\n1,fp1\n");
        refused(features, "the baseline has a row of 2 fields, not 4", "--look=L5", "--baseline",
            baseline.toString());
        Files.writeString(baseline, "");
        refused(features, "the baseline has no column media_file_id", "--look=L5", "--baseline",
            baseline.toString());
    }

    @Test
    void aFilmOfOneCoarseFrameWithNoTimeFeaturesIsNotFound() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Files.writeString(features.resolve("features.csv"), FeatureRows.header() + EvaluateFixtures.lonely()
            + EvaluateFixtures.development());

        run(features, "--train", trainA.toString());

        assertThat(evaluation().lines().skip(1).findFirst().orElseThrow())
            .isEqualTo("0,dev,4,NOT_FOUND,,,,,,,,,DARK,,,train-a,false");
    }

    @Test
    void eachFilmIsScoredByItsOwnFoldsOutOfFoldModelsWithItsOwnFoldsChoice() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);
        Path inverted = EvaluateFixtures.trainRun(temp, "train-inv", true);
        Path trainB = EvaluateFixtures.trainRun(temp, "train-b");
        EvaluateFixtures.mixIn(trainA, inverted, 0);
        EvaluateFixtures.mixIn(trainB, inverted, 1);

        run(features, "--train", inverted.toString(), "--train", trainA.toString(), "--train", trainB.toString());

        JsonNode outer = json("calibration.json").get("outer");
        List<String> lines = evaluation().lines().skip(1).toList();
        assertThat(lines).hasSize(10);
        String[] runs = {"train-a", "train-b", "train-a", "train-a", "train-a"};
        for (String line : lines) {
            String[] fields = line.split(",", -1);
            int fold = Integer.parseInt(fields[2]);
            assertThat(fields[15]).isEqualTo(outer.get(fields[2]).get("run").stringValue()).isEqualTo(runs[fold]);
            assertThat(fields[3]).isEqualTo(fold < 2 ? "FOUND" : "NOT_FOUND");
        }
        int[][] within = {{5, 5}, {5, 5}, {1, 5}, {1, 4}, {1, 5}};
        for (int k = 0; k < within.length; k++) {
            assertThat(outer.get(Integer.toString(k)).get("counts").toString()).isEqualTo(
                "{\"early15\":0,\"early5\":0,\"violations\":0,\"within10\":" + within[k][0] + ",\"within60\":"
                    + within[k][1] + ",\"films\":8}");
        }
    }
}
