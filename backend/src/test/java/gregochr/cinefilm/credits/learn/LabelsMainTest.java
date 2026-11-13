package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelsMainTest {

    private static final Path FIXTURES = LearnFixtures.dir();

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(String... args) {
        return LabelsMain.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String[] args(Path out, String... more) {
        List<String> all = new java.util.ArrayList<>(List.of("--archive", FIXTURES.resolve("archive").toString(),
            "--export", FIXTURES.resolve("export.csv").toString(), "--preseed",
            FIXTURES.resolve("preseed.txt").toString(), "--prefix", LearnFixtures.PREFIX, "--date", "2026-11-13",
            "--out", out.toString()));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    @Test
    void runsEndToEndOnTheFixtureDirectoryAndWritesTheThreeFiles() throws IOException {
        int code = run(args(temp, "--exclusions", FIXTURES.resolve("exclusions.csv").toString()));

        assertThat(code).isZero();
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEqualTo(
            "wrote " + temp.resolve("labels-2026-11-13.json") + "\nwrote " + temp.resolve("split-2026-11-13.json")
            + "\nwrote " + temp.resolve("labels-2026-11-13-report.txt") + "\n");
        assertThat(Files.readAllLines(temp.resolve("labels-2026-11-13.json"))).hasSize(21);
        assertThat(Files.readAllLines(temp.resolve("split-2026-11-13.json"))).hasSize(25);
        assertThat(Files.readString(temp.resolve("labels-2026-11-13-report.txt")))
            .startsWith("CineFilm credits labels, run date 2026-11-13\nfiles: 19\nunits: 18\n");
    }

    @Test
    void aFailureCodeIsPassedToExitAndSuccessIsNot() {
        List<Integer> exits = new java.util.ArrayList<>();

        LabelsMain.exitOnFailure(0, exits::add);
        LabelsMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void mainReturnsNormallyOnSuccess() {
        LabelsMain.main(args(temp));

        assertThat(temp.resolve("labels-2026-11-13.json")).isRegularFile();
    }

    @Test
    void aMissingFlagExitsNonZeroWithOneLine() {
        String[] without = List.of("--archive", "a").toArray(String[]::new);

        assertThat(run(without)).isEqualTo(1);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
            .isEqualTo("labels: IllegalArgumentException: missing required flag --export\n");
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void anUnreadableInputExitsNonZeroWithOneLineAndWritesNothing() {
        Path missing = temp.resolve("no-such-archive");
        String[] args = args(temp.resolve("out"));
        args[1] = missing.toString();

        assertThat(run(args)).isEqualTo(1);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
            .isEqualTo("labels: NoSuchFileException: " + missing + "\n");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aMalformedInputExitsNonZeroWithOneLine() throws IOException {
        Path badExport = temp.resolve("bad.csv");
        Files.writeString(badExport, "a,b\n1,2\n");
        String[] args = args(temp.resolve("out"));
        args[3] = badExport.toString();

        assertThat(run(args)).isEqualTo(1);
        assertThat(errBytes.toString(StandardCharsets.UTF_8).lines().toList()).hasSize(1).first().asString()
            .startsWith("labels: IllegalArgumentException: export header must be media_file_id,path,");
    }

    @Test
    void aBadPreviousManifestExitsNonZeroWithOneLine() throws IOException {
        Path bad = temp.resolve("previous.json");
        Files.writeString(bad, "{\"films\": 7}");

        assertThat(run(args(temp.resolve("out"), "--previous-split", bad.toString()))).isEqualTo(1);
        assertThat(errBytes.toString(StandardCharsets.UTF_8).lines().toList()).hasSize(1).first().asString()
            .startsWith("labels: ");
    }
}
