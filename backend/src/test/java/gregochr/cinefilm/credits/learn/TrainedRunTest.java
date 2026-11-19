package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.learn.CreditsTrainJob.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class TrainedRunTest {

    private static final Hashes HASHES = new Hashes("ds-hash", "labels-hash");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private Path run;

    @BeforeEach
    void setUp() throws IOException {
        run = EvaluateFixtures.trainRun(temp, "train-a");
    }

    private void edit(String file, String from, String to) throws IOException {
        Path path = run.resolve(file);
        Files.writeString(path, Files.readString(path).replace(from, to));
    }

    /** Changes {@code model.json} and checks that loading is refused with the message. */
    private void refusedAfter(Consumer<ObjectNode> change, String message) throws IOException {
        ObjectNode model = (ObjectNode) MAPPER.readTree(Files.readAllBytes(run.resolve("model.json")));
        change.accept(model);
        Files.write(run.resolve("model.json"), MAPPER.writeValueAsBytes(model));
        assertThatThrownBy(() -> TrainedRun.load(run, HASHES)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-a: " + message);
    }

    @Test
    void aRunHasFiveShippingModelsTenPairsAndTenTriplesKeyedByTheirFolds() throws IOException {
        TrainedRun loaded = TrainedRun.load(run, HASHES);

        assertThat(loaded.name()).isEqualTo("train-a");
        assertThat(loaded.manifestSha256()).isEqualTo(TrainFixtures.manifestSha());
        assertThat(loaded.shipping()).hasSize(5);
        assertThat(loaded.pairs()).containsOnlyKeys("0,1", "0,2", "0,3", "0,4", "1,2", "1,3", "1,4", "2,3", "2,4",
            "3,4");
        assertThat(loaded.triples()).hasSize(10).containsKeys("0,1,2", "2,3,4");
        assertThat(loaded.triples().keySet()).allMatch(key -> key.split(",").length == 3);
    }

    @Test
    void aDifferentHashIsRefusedByName() {
        assertThatThrownBy(() -> TrainedRun.load(run, new Hashes("x", "labels-hash")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-a: datasetSha256 differs from the features run's");
        assertThatThrownBy(() -> TrainedRun.load(run, new Hashes("ds-hash", "x")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-a: labelsSha256 differs from the features run's");
    }

    @Test
    void aHashThatIsMissingOrNotTextIsRefused() throws IOException {
        refusedAfter(model -> model.put("datasetSha256", 5), "model.json has no text field datasetSha256");
        refusedAfter(model -> model.put("datasetSha256", "ds-hash").remove("manifestSha256"),
            "model.json has no text field manifestSha256");
    }

    @Test
    void aShippingListThatIsNotFiveModelsIsRefused() throws IOException {
        refusedAfter(model -> ((ArrayNode) model.get("models")).remove(0), "model.json needs 5 models");
        refusedAfter(model -> model.put("models", "x"), "model.json needs 5 models");
        refusedAfter(model -> model.remove("models"), "model.json needs 5 models");
    }

    @Test
    void aMissingPairOrTripleModelIsRefused() throws IOException {
        edit("oof-models.json", "\"3,4\"", "\"x\"");
        assertThatThrownBy(() -> TrainedRun.load(run, HASHES)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-a: oof-models.json has no model 3,4");
        edit("oof-models.json", "\"x\"", "\"3,4\"");
        edit("nested-models.json", "\"2,3,4\"", "\"x\"");
        assertThatThrownBy(() -> TrainedRun.load(run, HASHES)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("train-a: nested-models.json has no model 2,3,4");
    }
}
