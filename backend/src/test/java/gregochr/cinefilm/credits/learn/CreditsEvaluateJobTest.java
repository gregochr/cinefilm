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
    void aRunWithoutTheLookScoresEveryDevelopmentFilmAndWritesTwoFiles() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, false);

        List<Path> written = run(features, "--train", trainA.toString());

        Path dir = out().resolve(RUN);
        assertThat(written).containsExactly(dir, dir.resolve("evaluation.csv"), dir.resolve("calibration.json"));
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
        assertThat(calibration.get("grid").get("points").intValue()).isEqualTo(20_000);
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
    }

    @Test
    void theLookScoresTestFilmsWithTheFinalChoiceAndWritesLookJson() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);

        List<Path> written = run(features, "--train", trainA.toString(), "--look=L5");

        assertThat(written).hasSize(4).last().isEqualTo(out().resolve(RUN).resolve("look.json"));
        assertThat(evaluation()).contains("\n10,dev,4,FOUND").endsWith(
            "\n11,test,,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false\n"
            + "12,test,,FOUND,120000,120000,true,true,-5000,0,LIT,low,LIT,,,train-a,false\n");
        assertThat(json("look.json").toString()).isEqualTo("{\"look\":\"L5\",\"generatedAt\":"
            + "\"2024-03-31T23:30:00Z\",\"toolGitCommit\":\"toolsha\",\"testFilms\":2}");
    }

    @Test
    void theSameInputsGiveByteIdenticalFiles() throws IOException {
        Path features = EvaluateFixtures.featuresRun(temp, true);
        Path trainB = EvaluateFixtures.trainRun(temp, "train-b");
        run(features, "--train", trainA.toString(), "--train", trainB.toString(), "--look=L5");
        Path second = temp.resolve("second");
        job.run(CreditsEvaluateOptions.parse(new String[] {"--features", features.toString(), "--out",
            second.toString(), "--train", trainA.toString(), "--train", trainB.toString(), "--look=L5"}));

        for (String name : List.of("evaluation.csv", "calibration.json", "look.json")) {
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
    void theBaselineIsRefusedAndNothingIsWritten() throws IOException {
        refused(EvaluateFixtures.featuresRun(temp, true), "--baseline is not supported yet", "--look=L5",
            "--baseline", "v2.csv");
    }
}
