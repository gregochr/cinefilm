package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.config.ApiExceptionHandler;
import gregochr.cinefilm.transcode.FileStamps.Stamp;
import gregochr.cinefilm.transcode.SessionFakes.FakeFiles;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import gregochr.cinefilm.transcode.SessionFakes.FakeStamps;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import java.io.IOException;
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
 * A session that went quiet for ten minutes, seen from the HTTP side: the real controllers, service and manager on
 * the fakes, with real segment bytes on disk. The playlists answer 200 with the same text, a segment waits for the
 * restarted encoder (503, then 200), a file that cannot be read just now is 503, and every final ending is a 404.
 */
class HlsSessionPauseTest {

    private static final Path SOURCE = Path.of("/library/Heat.m4v");
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), 400_000);
    private static final List<Rung> RUNGS = Ladder.rungs(1080, 24_000_000, true, EncoderMode.VAAPI).subList(0, 2);
    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final Duration DAY = Duration.ofHours(24);
    private static final byte[] BYTES = {0x47, 9, 8, 7};
    private static final String NO_SESSION = "{\"error\":\"No such session\"}";
    private static final String UNREADABLE = "{\"error\":\"The film's file cannot be read right now\"}";
    private static final String NOT_READY = "{\"error\":\"The segment is not ready yet\"}";
    private static final String MASTER = "/master.m3u8";
    private static final String MEDIA = "/original/index.m3u8";
    private static final String SEGMENT = "/original/seg-40.ts";

    @TempDir
    Path root;

    private final FakeFiles files = new FakeFiles();
    private final FakeTranscoder transcoder = new FakeTranscoder();
    private final ScriptedWaiter waiter = new ScriptedWaiter();
    private final MutableClock clock = new MutableClock();
    private final FakeStamps stamps = new FakeStamps();
    private SessionManager manager;
    private MockMvc mvc;
    private String id;
    private Path hls;
    private int counter;

    @BeforeEach
    void aSessionWithAFullBufferAndAnEncoderThatHasStopped() {
        hls = root.resolve("hls");
        manager = new SessionManager(transcoder, files, waiter, new FakeScheduler(), clock,
            new SessionSettings(Duration.ofMinutes(2), EXPIRY, 1000, 2, "dev"), stamps, hls,
            () -> new UUID(0, ++counter));
        id = manager.create(spec()).id();
        SessionService service = new SessionService(mock(SessionSources.class), mock(KeyframeIndexService.class),
            transcoder, manager);
        mvc = MockMvcBuilders.standaloneSetup(new HlsSessionController(service),
                new TranscodingAdminController(transcoder, mock(IndexJob.class), manager, service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static SessionSpec spec() {
        return new SessionSpec(7, "Heat", SOURCE, 1, CUTS, 25.0, 1920, 1080, RUNGS, RUNGS.get(0), null, 0, true);
    }

    private String path(String route) {
        return "/api/sessions/" + id + route;
    }

    private void expire() {
        clock.advance(EXPIRY);
        manager.sweep();
        assertThat(manager.sessions()).isEmpty();
    }

    private void writeRestartedSegments() throws IOException {
        Path rungDir = Files.createDirectories(hls.resolve(id).resolve("original"));
        Files.write(rungDir.resolve("seg-40.ts"), BYTES);
        files.add(rungDir.resolve("seg-40.ts"));
        files.add(rungDir.resolve("seg-41.ts"));
    }

    private void assertAllRoutesAre404() throws Exception {
        for (String route : List.of(MASTER, MEDIA, SEGMENT)) {
            mvc.perform(get(path(route)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(NO_SESSION));
        }
    }

    private void assertNoEncoderRestarted() {
        assertThat(transcoder.plans).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {MASTER, MEDIA})
    void aPlaylistAfterTenQuietMinutesIs200WithTheSameTextTypeAndCacheControlAndStartsNoEncoder(String route)
        throws Exception {
        MvcResult before = mvc.perform(get(path(route))).andExpect(status().isOk()).andReturn();
        expire();

        MvcResult after = mvc.perform(get(path(route))).andExpect(status().isOk()).andReturn();

        assertThat(after.getResponse().getContentAsString()).isEqualTo(before.getResponse().getContentAsString())
            .startsWith("#EXTM3U\n");
        assertThat(after.getResponse().getContentType()).isEqualTo(before.getResponse().getContentType())
            .startsWith("application/vnd.apple.mpegurl");
        assertThat(after.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertNoEncoderRestarted();
    }

    @Test
    void aSegmentAfterTenQuietMinutesIs503WhileTheRestartedEncoderWorksThen200WithItsBytes() throws Exception {
        expire();

        mvc.perform(get(path(SEGMENT)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(content().string(NOT_READY));
        assertThat(transcoder.plans).hasSize(2);
        assertThat(transcoder.lastPlan().startSegment()).isEqualTo(40);

        writeRestartedSegments();
        mvc.perform(get(path(SEGMENT)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentType("video/mp2t"))
            .andExpect(content().bytes(BYTES));
        assertThat(transcoder.plans).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {MASTER, MEDIA, SEGMENT})
    void aFileThatCannotBeReadJustNowIs503WithRetryAfterOneOnEveryRoute(String route) throws Exception {
        expire();
        stamps.unreadable.add(SOURCE);

        mvc.perform(get(path(route)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(content().string(UNREADABLE));

        assertNoEncoderRestarted();
        stamps.unreadable.clear();
        mvc.perform(get(path(MASTER))).andExpect(status().isOk());
    }

    @Test
    void afterTheViewersDeleteEveryRouteIs404() throws Exception {
        expire();

        mvc.perform(delete("/api/sessions/" + id)).andExpect(status().isNoContent());

        assertAllRoutesAre404();
        assertNoEncoderRestarted();
    }

    @Test
    void afterAdminsStopEveryRouteIs404() throws Exception {
        expire();

        mvc.perform(delete("/api/admin/transcoding/sessions/" + id)).andExpect(status().isNoContent());

        assertAllRoutesAre404();
        assertNoEncoderRestarted();
    }

    @Test
    void afterANewerSessionForTheEditionEveryRouteIs404() throws Exception {
        expire();

        manager.create(spec());

        assertAllRoutesAre404();
        assertThat(transcoder.plans).hasSize(2);
    }

    @Test
    void aDayAfterTheLastRequestEveryRouteIs404() throws Exception {
        expire();
        clock.advance(DAY.minus(EXPIRY));

        assertAllRoutesAre404();
        assertNoEncoderRestarted();
    }

    @Test
    void afterTheFileChangedEveryRouteIs404() throws Exception {
        expire();
        stamps.overrides.put(SOURCE, new Stamp(1001, 5000));

        assertAllRoutesAre404();
        assertNoEncoderRestarted();
    }
}
