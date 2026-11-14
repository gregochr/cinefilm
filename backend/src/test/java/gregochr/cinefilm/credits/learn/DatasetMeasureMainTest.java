package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetMeasureMainTest {

    private static final String RUN = "measure-20261114T090000Z";

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private Path data;
    private Path frames;
    private DatasetMeasureJob job;
    private Path labels;

    @BeforeEach
    void setUp() throws IOException {
        data = Files.createDirectories(temp.resolve("data"));
        frames = Files.createDirectories(data.resolve("frames"));
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new DatasetMeasureJob(Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC), git);
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"), MeasureFixtures.label(7, Side.DEV, null));
    }

    /** Runs with the given flags, and with {@code --labels} added unless they have it. */
    private int run(String... given) {
        List<String> all = new ArrayList<>(List.of(given));
        if (!all.contains("--labels")) {
            all.addAll(List.of("--labels", labels.toString()));
        }
        String[] args = all.toArray(String[]::new);
        return DatasetMeasureMain.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private void oneFilm() throws IOException {
        MeasureFixtures.film(frames, "7", MeasureFixtures.complete(7, "ffmpeg 7", "abc", "2026-11-10T12:10:00Z",
            List.of(0L), List.of(100_000L)));
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() throws IOException {
        oneFilm();
        Path out = temp.resolve("out");

        int code = run("--data", data.toString(), "--out", out.toString());

        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(out()).isEqualTo("wrote " + out.resolve(RUN) + "\nwrote " + out.resolve(RUN + "/films.csv")
            + "\nwrote " + out.resolve(RUN + "/frames.csv") + "\nwrote " + out.resolve(RUN + "/summary.json") + "\n");
    }

    @Test
    void stillsAddTheirDirectoryToThePrintedPaths() throws IOException {
        oneFilm();
        Path out = temp.resolve("out");

        assertThat(run("--data", data.toString(), "--out", out.toString(), "--stills")).isZero();

        assertThat(out().lines().toList()).hasSize(5).last().isEqualTo("wrote " + out.resolve(RUN + "/stills"));
    }

    @Test
    void aDamagedFilmStillExitsZero() throws IOException {
        oneFilm();
        Files.delete(frames.resolve("7/dense/100000.png"));

        assertThat(run("--data", data.toString(), "--out", temp.resolve("out").toString())).isZero();
        assertThat(Files.readString(temp.resolve("out/" + RUN + "/films.csv"))).contains("7,damaged,");
    }

    @Test
    void theTestFilmsAreLeftOutSilently() throws IOException {
        oneFilm();
        MeasureFixtures.film(frames, "4242", MeasureFixtures.complete(4242, "f", "c", "2026-11-10T12:10:00Z",
            List.of(0L), List.of()));
        Path both = MeasureFixtures.labelsFile(temp.resolve("both.json"), MeasureFixtures.label(7, Side.DEV, null),
            MeasureFixtures.label(4242, Side.TEST, null));
        Path out = temp.resolve("out");

        assertThat(run("--data", data.toString(), "--out", out.toString(), "--labels", both.toString())).isZero();

        assertThat(err()).isEmpty();
        assertThat(Files.readString(out.resolve(RUN + "/films.csv"))).doesNotContain("4242");
    }

    @Test
    void aLookFlagIsRefusedAsUnknown() {
        assertThat(run("--data", "x", "--out", "y", "--look=L5")).isEqualTo(1);

        assertThat(err()).isEqualTo("measure: IllegalArgumentException: unknown argument --look=L5\n");
    }

    @Test
    void aMissingLabelsFlagAndAnUnreadableLabelsFileExitOne() throws IOException {
        oneFilm();
        assertThat(DatasetMeasureMain.run(new String[] {"--data", data.toString(), "--out", "y"},
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job)).isEqualTo(1);
        assertThat(err()).isEqualTo("measure: IllegalArgumentException: missing required flag --labels\n");

        errBytes.reset();
        Path missing = temp.resolve("none.json");
        assertThat(run("--data", data.toString(), "--out", temp.resolve("out").toString(), "--labels",
            missing.toString())).isEqualTo(1);
        assertThat(err()).isEqualTo("measure: NoSuchFileException: " + missing + "\n");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aBadFlagExitsOneWithOneLineAndWritesNothing() {
        assertThat(run("--data", data.toString())).isEqualTo(1);

        assertThat(err()).isEqualTo("measure: IllegalArgumentException: missing required flag --out\n");
        assertThat(out()).isEmpty();
    }

    @Test
    void anUnknownFlagExitsOne() {
        assertThat(run("--data", "x", "--out", "y", "--bogus")).isEqualTo(1);

        assertThat(err()).isEqualTo("measure: IllegalArgumentException: unknown argument --bogus\n");
    }

    @Test
    void aBadIdsListAndABadProjectCountExitOne() {
        assertThat(run("--data", "x", "--out", "y", "--ids", "a")).isEqualTo(1);
        assertThat(err()).isEqualTo(
            "measure: IllegalArgumentException: --ids must be whole numbers separated by commas\n");

        errBytes.reset();
        assertThat(run("--data", "x", "--out", "y", "--project", "0")).isEqualTo(1);
        assertThat(err()).isEqualTo("measure: IllegalArgumentException: --project must be a whole number above 0\n");
    }

    @Test
    void anUnreadableDataDirectoryExitsOne() {
        Path missing = temp.resolve("nope");

        assertThat(run("--data", missing.toString(), "--out", temp.resolve("out").toString())).isEqualTo(1);

        assertThat(err()).isEqualTo("measure: IllegalArgumentException: --data is not a directory: " + missing + "\n");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void anOutputInsideTheDataExitsOne() {
        Path inside = data.resolve("out");

        assertThat(run("--data", data.toString(), "--out", inside.toString())).isEqualTo(1);

        assertThat(err()).isEqualTo("measure: IllegalArgumentException: --out must not be inside --data: " + inside
            + "\n");
        assertThat(inside).doesNotExist();
    }

    @Test
    void anOutputThatCannotBeMadeExitsOne() throws IOException {
        oneFilm();
        Path blocker = Files.writeString(temp.resolve("blocker"), "a file");

        assertThat(run("--data", data.toString(), "--out", blocker.resolve("out").toString())).isEqualTo(1);

        assertThat(err()).startsWith("measure: ").hasLineCount(1);
        assertThat(err()).doesNotContain("\n\n");
    }

    @Test
    void anUncheckedIoFailureWhileWalkingExitsOneWithOneLine() throws Exception {
        DatasetMeasureJob failing = mock(DatasetMeasureJob.class);
        when(failing.run(any())).thenThrow(
            new UncheckedIOException(new IOException("Permission denied")));

        int code = DatasetMeasureMain.run(new String[] {"--data", "x", "--out", "y", "--labels", "z"},
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), failing);

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("measure: UncheckedIOException: java.io.IOException: Permission denied\n");
    }

    @Test
    void noFilmDirectoriesExitsTwoWithOneLineAndWritesNothing() {
        Path out = temp.resolve("out");

        assertThat(run("--data", data.toString(), "--out", out.toString())).isEqualTo(2);

        assertThat(err()).isEqualTo("measure: no film directories under " + frames + "\n");
        assertThat(out()).isEmpty();
        assertThat(out).doesNotExist();
    }

    @Test
    void aMissingFramesDirectoryExitsTwo() throws IOException {
        Files.delete(frames);

        assertThat(run("--data", data.toString(), "--out", temp.resolve("out").toString())).isEqualTo(2);
    }

    @Test
    void aFailureCodeIsPassedToExitAndSuccessIsNot() {
        List<Integer> exits = new ArrayList<>();

        DatasetMeasureMain.exitOnFailure(0, exits::add);
        DatasetMeasureMain.exitOnFailure(1, exits::add);
        DatasetMeasureMain.exitOnFailure(2, exits::add);

        assertThat(exits).containsExactly(1, 2);
    }

    @Test
    void mainReturnsNormallyOnSuccessAndWritesTheReport() throws IOException {
        oneFilm();
        Path out = temp.resolve("out");

        DatasetMeasureMain.main(new String[] {"--data", data.toString(), "--out", out.toString(), "--labels",
            labels.toString()});

        try (var runs = Files.list(out)) {
            Path runDir = runs.findFirst().orElseThrow();
            assertThat(runDir.getFileName().toString()).matches("measure-\\d{8}T\\d{6}Z");
            assertThat(Files.readString(runDir.resolve("summary.json"))).contains("\"toolGitCommit\"");
        }
    }
}
