package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.config.ApiExceptionHandler;
import gregochr.cinefilm.transcode.SessionFakes.FakeFiles;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The segment route from the URL to the file path with nothing mocked between the controller and the manager: a
 * request can only ever name a known session, a known rung and a segment inside the cut list, so a traversal attempt
 * in any of the three variables is a 404 and never reads a file outside the session folder.
 */
class HlsSegmentPathSafetyTest {

    private static final String SECRET = "do not serve";
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), 400_000);
    private static final List<Rung> RUNGS = Ladder.rungs(1080, 24_000_000, true, EncoderMode.VAAPI).subList(0, 2);

    @TempDir
    Path root;

    private final FakeFiles files = new FakeFiles();
    private final FakeTranscoder transcoder = new FakeTranscoder();
    private MockMvc mvc;
    private String id;
    private Path hls;

    @BeforeEach
    void sessionWithTwoCompleteSegmentsOnDisk() throws IOException {
        hls = root.resolve("hls");
        SessionManager manager = new SessionManager(transcoder, files, new ScriptedWaiter(), new FakeScheduler(),
            new MutableClock(), new SessionSettings(Duration.ofMinutes(2), Duration.ofMinutes(10), 1000, 2, "dev"),
            hls, () -> new UUID(0, 1));
        id = manager.create(new SessionSpec(7, "Heat", Path.of("/library/Heat.m4v"), 1, CUTS, 25.0, 1920, 1080,
            RUNGS, RUNGS.get(0), null, 0, true)).id();
        Path rungDir = Files.createDirectories(hls.resolve(id).resolve("original"));
        Files.write(rungDir.resolve("seg-0.ts"), new byte[] {0x47, 1, 2});
        files.add(rungDir.resolve("seg-0.ts"));
        files.add(rungDir.resolve("seg-1.ts"));
        Files.writeString(root.resolve("secret.txt"), SECRET, StandardCharsets.UTF_8);
        Files.writeString(hls.resolve("secret.ts"), SECRET, StandardCharsets.UTF_8);
        SessionService service = new SessionService(mock(SessionSources.class), mock(KeyframeIndexService.class),
            transcoder, manager);
        mvc = MockMvcBuilders.standaloneSetup(new HlsSessionController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void aCompleteSegmentOfAKnownSessionIsServed() throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", "seg-0.ts"))
            .andExpect(status().isOk())
            .andExpect(content().bytes(new byte[] {0x47, 1, 2}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../secret.ts", "..%2Fsecret.ts", "seg-0.ts/../../secret.ts", "seg-0.ts/..",
        "/etc/passwd", "seg-0.TS", "seg-0.ts ", "seg--1.ts", "seg-1000000.ts", "secret.ts", "seg-0.ts%00.jpg"})
    void aTraversalOrMalformedFileNameIs404AndNeverAFileOutsideTheSession(String file) throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", file))
            .andExpect(status().isNotFound())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../..", "..%2F..", "../" + "original", "/etc", "ORIGINAL", "original/..", "999p",
        "480p", ""})
    void aTraversalOrUnknownRungIs404AndNeverAFileOutsideTheSession(String rung) throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, rung, "seg-0.ts"))
            .andExpect(status().isNotFound())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../hls/" + "x", "..%2F", ".", "not-a-uuid", "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-000000000001/..", "00000000-0000-0000-0000-00000000000G"})
    void aTraversalOrUnknownSessionIdIs404AndNeverAFileOutsideTheSession(String session) throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", session, "original", "seg-0.ts"))
            .andExpect(status().isNotFound())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET));
    }

    @Test
    void aSegmentNumberBeyondTheCutListIsA404WithTheNormalBodyAndStartsNoEncoder() throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", "seg-100.ts"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such segment"));
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", "seg-999999.ts"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such segment"));

        assertThat(transcoder.plans).hasSize(1);
    }

    @Test
    void aNegativeSegmentNumberIsA404NotAServerError() throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", "seg--1.ts"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such segment"));
    }
}
