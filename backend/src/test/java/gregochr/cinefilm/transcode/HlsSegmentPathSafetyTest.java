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
import gregochr.cinefilm.transcode.SessionFakes.FakeStamps;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import java.io.IOException;
import java.net.URI;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The segment route from the URL to the file path with nothing mocked between the controller and the manager: a
 * request can only ever name a known session, a known rung and a segment inside the cut list, so a traversal attempt
 * in any of the three variables is a 404 and never reads a file outside the session folder.
 *
 * <p>Two kinds of rejection are told apart. An application-level rejection matches the route, reaches the handler
 * and carries the application's own error body. A route-level rejection (a value with a {@code /} in it, or an empty
 * one) matches no route, so no handler runs and the body is empty. The handler check fails if routing changes.
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
            new FakeStamps(), hls, () -> new UUID(0, 1));
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

    private MvcResult segment(String session, String rung, String file) throws Exception {
        return mvc.perform(get("/api/sessions/{id}/{rung}/{file}", session, rung, file)).andReturn();
    }

    private void assertRejectedByTheApplication(MvcResult result, String message) throws Exception {
        assertThat(result.getHandler()).as("the request reached the controller").isNotNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"error\":\"" + message + "\"}")
            .doesNotContain(SECRET);
        assertThat(transcoder.plans).as("no encoder was started").hasSize(1);
    }

    private void assertRejectedByTheRoute(MvcResult result) throws Exception {
        assertThat(result.getHandler()).as("no handler is mapped to the request").isNull();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(transcoder.plans).as("no encoder was started").hasSize(1);
    }

    @Test
    void aCompleteSegmentOfAKnownSessionIsServed() throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", "seg-0.ts"))
            .andExpect(status().isOk())
            .andExpect(content().bytes(new byte[] {0x47, 1, 2}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "..%2Fsecret.ts", "..%2F..", "seg-0.TS", "seg-0.ts ", "seg--1.ts",
        "seg-1000000.ts", "secret.ts", "seg-0.ts%00.jpg", "."})
    void aFileNameThatIsNotASegmentIsRejectedByTheServiceAs404NoSuchSegment(String file) throws Exception {
        assertRejectedByTheApplication(segment(id, "original", file), "No such segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "..%2Fsecret.ts", "..%2F..", "ORIGINAL", "999p", "480p", ".", "seg-0.ts"})
    void aRungThatIsNotOneOfTheSessionsIsRejectedAs404NoSuchSegment(String rung) throws Exception {
        assertRejectedByTheApplication(segment(id, rung, "seg-0.ts"), "No such segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "..%2Fsecret.ts", "..%2F..", "not-a-uuid", "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-00000000000G", ".", "ORIGINAL"})
    void aSessionIdThatIsNotALiveSessionIsRejectedAs404NoSuchSession(String session) throws Exception {
        assertRejectedByTheApplication(segment(session, "original", "seg-0.ts"), "No such session");
    }

    @ParameterizedTest
    @ValueSource(strings = {"seg-100.ts", "seg-999999.ts"})
    void aSegmentNumberBeyondTheCutListIsRejectedByTheManagerAs404NoSuchSegment(String file) throws Exception {
        assertRejectedByTheApplication(segment(id, "original", file), "No such segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.ts", "seg-0.ts/../../secret.ts", "seg-0.ts/..", "/etc/passwd", "../..",
        "../original", "/etc", "original/..", "", "../hls/x"})
    void aFileValueThatSpansPathSegmentsOrIsEmptyMatchesNoRoute(String file) throws Exception {
        assertRejectedByTheRoute(segment(id, "original", file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.ts", "seg-0.ts/../../secret.ts", "seg-0.ts/..", "/etc/passwd", "../..",
        "../original", "/etc", "original/..", "", "../hls/x"})
    void aRungValueThatSpansPathSegmentsOrIsEmptyMatchesNoRoute(String rung) throws Exception {
        assertRejectedByTheRoute(segment(id, rung, "seg-0.ts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.ts", "seg-0.ts/../../secret.ts", "seg-0.ts/..", "/etc/passwd", "../..",
        "../original", "/etc", "original/..", "", "../hls/x", "00000000-0000-0000-0000-000000000001/.."})
    void aSessionIdValueThatSpansPathSegmentsOrIsEmptyMatchesNoRoute(String session) throws Exception {
        assertRejectedByTheRoute(segment(session, "original", "seg-0.ts"));
    }

    @Test
    void anEncodedSlashInTheFileNameReachesTheServiceWhichRejectsIt() throws Exception {
        MvcResult result = mvc.perform(get(URI.create("/api/sessions/" + id + "/original/..%2Fsecret.ts")))
            .andReturn();

        assertRejectedByTheApplication(result, "No such segment");
    }

    @Test
    void aNegativeSegmentNumberIsA404NotAServerError() throws Exception {
        mvc.perform(get("/api/sessions/{id}/{rung}/{file}", id, "original", "seg--1.ts"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such segment"));
    }
}
