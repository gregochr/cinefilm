package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

class CreditsAutoCheckMainTest {

    private static final String RUN = "auto-check-20261122T120000Z";
    private static final String ONE = "[{\"editionId\":7,\"filmId\":1,\"mediaFileId\":2,\"path\":\"gone.mkv\","
        + "\"offsetMs\":0,\"kind\":\"CRAWL\",\"startMs\":100000,\"markerMs\":120000,\"owner\":false}]";

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private AutoCheckJob job;
    private Path media;
    private Path candidates;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        job = new AutoCheckJob(Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC),
            mock(StillSource.class));
        media = Files.createDirectories(temp.resolve("media"));
        candidates = Files.writeString(temp.resolve("c.json"), ONE);
        out = temp.resolve("out");
    }

    private int run(String... given) {
        return CreditsAutoCheckMain.run(given, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() {
        int code = run("--candidates", candidates.toString(), "--out", out.toString(), "--media", media.toString());

        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(out()).isEqualTo("wrote " + out.resolve(RUN) + "\nwrote " + out.resolve(RUN).resolve("list.csv")
            + "\n");
    }

    @Test
    void anUnreadableFileIsListedAndTheRunStillExitsZero() throws IOException {
        int code = run("--candidates", candidates.toString(), "--out", out.toString(), "--media", media.toString());

        assertThat(code).isZero();
        assertThat(Files.readAllLines(out.resolve(RUN).resolve("list.csv")).get(1))
            .isEqualTo("7,CRAWL,false,100000,120000,,,the file is missing");
    }

    @Test
    void aBadFlagExitsOneWithAOneLineMessage() {
        int code = run("--candidates", candidates.toString(), "--stills");

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("auto-check: IllegalArgumentException: unknown argument --stills\n");
        assertThat(out()).isEmpty();
    }

    @Test
    void aMissingRequiredFlagExitsOne() {
        assertThat(run("--out", out.toString())).isEqualTo(1);
        assertThat(err()).isEqualTo("auto-check: IllegalArgumentException: missing required flag --candidates\n");
    }

    @Test
    void aCandidatesFileThatCannotBeReadExitsOne() {
        int code = run("--candidates", temp.resolve("none.json").toString(), "--out", out.toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("auto-check: NoSuchFileException: ").endsWith("none.json\n");
    }

    @Test
    void aCandidatesFileThatIsNotAListExitsOneWithoutALineBreak() throws IOException {
        Files.writeString(candidates, "{\n  \"error\": \"No deny list\"\n}");

        int code = run("--candidates", candidates.toString(), "--out", out.toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("auto-check: MismatchedInputException: ");
        assertThat(err().trim().lines().count()).isOne();
    }

    @Test
    void aRunDirectoryThatAlreadyExistsExitsOne() throws IOException {
        Files.createDirectories(out.resolve(RUN));

        int code = run("--candidates", candidates.toString(), "--out", out.toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("auto-check: IllegalArgumentException: this run's directory already exists");
    }

    @Test
    void fullForAnEditionNotInTheListExitsOne() {
        int code = run("--candidates", candidates.toString(), "--out", out.toString(), "--full", "99", "1");

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("auto-check: IllegalArgumentException: no candidate for edition 99\n");
    }

    @Test
    void exitOnFailureCallsExitWithTheCodeUnlessItIsZero() {
        List<Integer> exits = new ArrayList<>();

        CreditsAutoCheckMain.exitOnFailure(0, exits::add);
        CreditsAutoCheckMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void mainReturnsNormallyOnSuccessAndWritesTheList() throws IOException {
        CreditsAutoCheckMain.main(new String[] {"--candidates", candidates.toString(), "--out", out.toString(),
            "--media", media.toString()});

        try (var runs = Files.list(out)) {
            Path runDir = runs.findFirst().orElseThrow();
            assertThat(runDir.getFileName().toString()).matches("auto-check-\\d{8}T\\d{6}Z");
            assertThat(Files.readString(runDir.resolve("list.csv"))).contains("the file is missing");
        }
    }
}
