package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link CreditsTrainJob}'s four output files byte for byte, with the real trees, on a fixed fixture. The
 * constants were recorded from the code before {@code TrainRows} and {@code FoldModels} were extracted from it
 * (L4b-4a); the files themselves are not committed.
 */
class CreditsTrainJobOutputTest {

    private static final String MODEL_SHA = "451d531be7a4eb9020fe5cbb0e5446ade374c7f29c8e353fb8dfbc332ec1934c";
    private static final String OOF_MODELS_SHA = "d331e67e5f45f258a7569c9435604c8c937543ba9754a849f4dfb73d1a05d791";
    private static final String NESTED_MODELS_SHA = "9411954f7e0832d458bd7053941841797785c2841e4d70cd1563fb9031fd22d5";
    private static final String OOF_LOGLOSS_SHA = "a04c500e4561a7b03dbdaeedc78d5a32d4bb4c4a7c2538d68f3adae641910abd";

    @TempDir
    Path temp;

    /**
     * Five folds, one film each. Coarse rows: credits, film, credits, film, then a lit opening of three bands that
     * the lit-text variant turns into credits; then two dense rows (credits, film). Means separate the labels.
     */
    private static String fixture() {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            for (int n = 0; n < 4; n++) {
                boolean credits = n % 2 == 0;
                csv.append(TrainFixtures.row(k + 1, k, "coarse", n * 1000L, credits ? "credits" : "film",
                    TrainFixtures.lit(k * 100 + n, credits ? 90 + n + k : 10 + n + k, n)));
            }
            csv.append(TrainFixtures.row(k + 1, k, "coarse", 4000, "LIT_OPENING",
                TrainFixtures.lit(k * 100 + 4, 100, 3)));
            csv.append(TrainFixtures.row(k + 1, k, "dense", 0, "credits", TrainFixtures.lit(k * 100 + 50, 95 + k, 2)));
            csv.append(TrainFixtures.row(k + 1, k, "dense", 500, "film", TrainFixtures.lit(k * 100 + 51, 12 + k, 1)));
        }
        return csv.toString();
    }

    private static String sha(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theFourFilesAreByteForByteWhatTheCodeBeforeTheExtractionWrote() throws IOException {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        Path[] inputs = TrainFixtures.inputs(temp, fixture());
        CreditsTrainOptions options = CreditsTrainOptions.parse(TrainFixtures.args(inputs, temp.resolve("out"),
            "--with-dense", "--lit-text-variant", "--trees", "4", "--depth", "2", "--bins", "4", "--min-leaf", "1"));

        List<Path> written = new CreditsTrainJob(TestData.CLOCK, git).run(options);

        String model = sha(written.get(1));
        String oofModels = sha(written.get(2));
        String oofLogLoss = sha(written.get(3));
        String nestedModels = sha(written.get(4));
        assertSoftly(soft -> {
            soft.assertThat(model).as("model.json").isEqualTo(MODEL_SHA);
            soft.assertThat(oofModels).as("oof-models.json").isEqualTo(OOF_MODELS_SHA);
            soft.assertThat(oofLogLoss).as("oof-logloss.json").isEqualTo(OOF_LOGLOSS_SHA);
            soft.assertThat(nestedModels).as("nested-models.json").isEqualTo(NESTED_MODELS_SHA);
        });
    }
}
