package gregochr.cinefilm.credits.learn;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fixture features run and training runs for the evaluation tool: films 1 to 10 are development films, two in each
 * fold ({@code (id - 1) % 5}); films 11 and 12 are test films, in the look's run only. Every film has 12 coarse frames
 * at {@code 100000 + 5000 i}, film for {@code i < 4} (mean 10) and credits from {@code i = 4} (mean 90 or 150), so
 * Plex's target and the segmenter's {@code t} are 120000; and 101 dense frames at {@code 90000 + 500 i} (the first and
 * last without time features) with the same split. A model trained on the mean alone separates them.
 */
final class EvaluateFixtures {

    static final long TARGET = 120_000;
    static final String FEATURES_RUN = "features-20240331T233000Z";
    static final String[] FAST = {"--trees", "20", "--depth", "1", "--learning-rate", "0.5", "--bins", "4",
        "--min-leaf", "1"};
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EvaluateFixtures() {
    }

    /** Frame {@code id}'s credits kind: films 1 and 2 are {@code DARK}, 3 and 4 {@code PLATE}, the rest {@code LIT}. */
    static double[] credits(long film, double id) {
        double[] f = TrainFixtures.lit(id, 90, 0);
        if (film <= 2) {
            f[2] = 0;
        } else if (film <= 4) {
            f[0] = 150;
            f[1] = 100;
            f[2] = 105;
            f[3] = 110;
        }
        return f;
    }

    /** Plex opening: 1-2 {@code DARK}, 3-4 and 9-10 {@code PLATE}, 5-6 {@code LIT}, 7-8 none. */
    static FrameKind plex(long film) {
        if (film <= 2) {
            return FrameKind.DARK;
        }
        if (film <= 4 || film >= 9) {
            return FrameKind.PLATE;
        }
        return film <= 6 ? FrameKind.LIT : null;
    }

    /** A development film's fold: two films in each. */
    static int fold(long id) {
        return (int) Math.floorMod(id - 1, 5);
    }

    /** Film 0: one coarse frame, with no time features. */
    static String lonely() {
        return FeatureRows.row(0, fold(0), Side.DEV, "coarse", 100_000, "film",
            TrainFixtures.noTime(TrainFixtures.lit(1, 10, 0)));
    }

    /** One film's rows, every one labelled by its side of 120000. */
    static String film(long id, Side side) {
        Integer fold = side == Side.DEV ? fold(id) : null;
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            boolean credits = i >= 4;
            rows.append(FeatureRows.row(id, fold, side, "coarse", 100_000 + 5_000L * i, credits ? "credits" : "film",
                credits ? credits(id, i) : TrainFixtures.lit(i, 10, 0)));
        }
        for (int i = 0; i < 101; i++) {
            long offset = 90_000 + 500L * i;
            boolean credits = offset >= TARGET;
            double[] features = credits ? credits(id, 50 + i) : TrainFixtures.lit(50 + i, 10, 0);
            rows.append(FeatureRows.row(id, fold, side, "dense", offset, credits ? "credits" : "film",
                i == 0 || i == 100 ? TrainFixtures.noTime(features) : features));
        }
        return rows.toString();
    }

    /** The development films' rows, ids 1 to 10. */
    static String development() {
        return java.util.stream.LongStream.rangeClosed(1, 10).mapToObj(id -> film(id, Side.DEV))
            .collect(java.util.stream.Collectors.joining());
    }

    /** The test films' rows, ids 11 and 12. */
    static String testFilms() {
        return film(11, Side.TEST) + film(12, Side.TEST);
    }

    /** The label record of a film: target 120000, Plex's opening, a leading island on film 3 and none known for 4. */
    static FrameLabelRecord label(long id) {
        Side side = id <= 10 ? Side.DEV : Side.TEST;
        return new FrameLabelRecord(id, "fp" + id, "unit" + id, side, side == Side.DEV ? fold(id) : null,
            TARGET, 0L, id == 4 ? null : id == 3, List.of(), List.of(), List.of(), null, null,
            id <= 10 ? plex(id) : FrameKind.LIT, null);
    }

    /** A features run's {@code frame-labels.json}: the records of films 0 to {@code last}. */
    static String labels(int last) {
        return MAPPER.writeValueAsString(java.util.stream.LongStream.rangeClosed(0, last)
            .mapToObj(EvaluateFixtures::label).toList());
    }

    /**
     * Writes a features run directory of the development films, and with {@code look} the test films and
     * {@code look.json} too.
     */
    static Path featuresRun(Path parent, boolean look) throws IOException {
        Path dir = Files.createDirectories(parent.resolve(FEATURES_RUN));
        Files.writeString(dir.resolve("features.csv"), FeatureRows.header() + development()
            + (look ? testFilms() : ""));
        Files.writeString(dir.resolve("frame-labels.json"), labels(look ? 12 : 10));
        Files.writeString(dir.resolve("summary.json"), "{\"featureVersion\":" + CreditsFeatures.FEATURE_VERSION
            + ",\"datasetSha256\":\"ds-hash\",\"labelsSha256\":\"labels-hash\"}");
        if (look) {
            Files.writeString(dir.resolve("look.json"), "{}");
        }
        return dir;
    }

    /** Trains a run on the development films with {@link #FAST} settings and gives its directory the name given. */
    static Path trainRun(Path parent, String name) throws IOException {
        return trainRun(parent, name, false);
    }

    /** As {@link #trainRun(Path, String)}; {@code inverted} swaps every label, so the run predicts the opposite. */
    static Path trainRun(Path parent, String name, boolean inverted) throws IOException {
        Path inputs = Files.createDirectories(parent.resolve("inputs-" + name));
        String csv = FeatureRows.header() + development();
        Path[] files = TrainFixtures.inputs(inputs, inverted
            ? csv.replace(",credits,", ",x,").replace(",film,", ",credits,").replace(",x,", ",film,") : csv);
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("trainsha");
        List<Path> written = new CreditsTrainJob(TestData.CLOCK, git)
            .run(CreditsTrainOptions.parse(TrainFixtures.args(files, parent.resolve("train-out-" + name), FAST)));
        return Files.move(written.getFirst(), written.getFirst().resolveSibling(name));
    }

    /**
     * Replaces every model of {@code run}'s {@code oof-models.json} and {@code nested-models.json} whose key does not
     * hold {@code fold} by {@code other}'s model of that key.
     */
    static void mixIn(Path run, Path other, int fold) throws IOException {
        for (String file : new String[] {"oof-models.json", "nested-models.json"}) {
            ObjectNode own = (ObjectNode) MAPPER.readTree(Files.readAllBytes(run.resolve(file)));
            JsonNode theirs = MAPPER.readTree(Files.readAllBytes(other.resolve(file)));
            for (String key : new ArrayList<>(own.propertyNames())) {
                if (!List.of(key.split(",")).contains(Integer.toString(fold))) {
                    own.set(key, theirs.get(key));
                }
            }
            Files.write(run.resolve(file), MAPPER.writeValueAsBytes(own));
        }
    }

    /** Gives {@code run} {@code other}'s five shipping models. */
    static void shipWith(Path run, Path other) throws IOException {
        ObjectNode own = (ObjectNode) MAPPER.readTree(Files.readAllBytes(run.resolve("model.json")));
        own.set("models", MAPPER.readTree(Files.readAllBytes(other.resolve("model.json"))).get("models"));
        Files.write(run.resolve("model.json"), MAPPER.writeValueAsBytes(own));
    }
}
