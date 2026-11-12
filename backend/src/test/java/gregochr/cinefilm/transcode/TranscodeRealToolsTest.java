package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.ProcessCommandRunner;
import gregochr.cinefilm.library.ProcessLineStreamRunner;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole streaming path against the real ffmpeg and ffprobe, on the software encoder: a 30 s clip with B-frames
 * and irregular, forced keyframes is indexed, and a real {@link SessionManager} makes a session whose copy rung,
 * 360p rung and 240p floor rung are checked at every segment. CI installs ffmpeg (with libx264); the test skips
 * itself where the tools are not on the PATH, as on the dev Mac. Every wait is bounded by {@link #DEADLINE}.
 *
 * <p>The real clock is read here on purpose: the encoder really runs, and the deadline is wall time.
 */
@Tag("real-tools")
class TranscodeRealToolsTest {

    private static final ProcessCommandRunner RUNNER = new ProcessCommandRunner();
    private static final Duration DEADLINE = Duration.ofSeconds(60);
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(120);
    private static final long DURATION_MS = 30_000;
    /** On the 25 fps grid, irregular gaps: the cuts come out as 0, 5.0, 9.6, 14.0 and 22.52 s. */
    private static final String KEYFRAMES = "0,2.2,5,9.6,13.12,14,22.52";

    @TempDir
    static Path library;

    @TempDir
    Path work;

    private static KeyframeIndexService.Index index;
    private static List<Rung> rungs;
    private static Path source;

    private final Clock real = Clock.systemUTC();
    private SessionManager manager;

    @BeforeAll
    static void clipIsIndexed() throws IOException {
        assumeTrue(runs("ffmpeg") && runs("ffprobe"), "ffmpeg and ffprobe are not on the PATH");
        CommandRunner.Result made = RUNNER.run(List.of("ffmpeg", "-v", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=30:size=640x480:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=30",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "2", "-g", "9999", "-sc_threshold", "0",
            "-force_key_frames", KEYFRAMES, "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart",
            library.resolve("clip.mp4").toString()), TOOL_TIMEOUT);
        assumeTrue(made.succeeded(), "this ffmpeg cannot make the test clip (libx264 missing?)");

        MediaFile file = new MediaFile();
        file.setId(1L);
        file.setPath("clip.mp4");
        file.setDurationMs(DURATION_MS);
        LibraryRoot root = new LibraryRoot(library.toString());
        KeyframeIndexService service = new KeyframeIndexService(root,
            new KeyframeIndexer(new Mp4SampleTableReader(), new ProcessLineStreamRunner(), "ffprobe"),
            mock(MediaKeyframesRepository.class), new NioFileStamps(), CLOCK);
        index = service.ensureIndexed(file);
        rungs = Ladder.rungs(480, 0, index.cuts().copyOk(), EncoderMode.SOFTWARE);
        source = root.resolveFile("clip.mp4").orElseThrow();
    }

    private static boolean runs(String tool) {
        try {
            return RUNNER.run(List.of(tool, "-version"), Duration.ofSeconds(20)).succeeded();
        } catch (IOException e) {
            return false;
        }
    }

    /** The real clock plus an offset the test sets: the manager's idea of time only, so a pause takes no wall time. */
    private static final class ShiftedClock extends Clock {
        private final Clock base;
        private volatile Duration shift = Duration.ZERO;

        ShiftedClock(Clock base) {
            this.base = base;
        }

        void shiftBy(Duration by) {
            shift = shift.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new ShiftedClock(base.withZone(zone));
        }

        @Override
        public Instant instant() {
            return base.instant().plus(shift);
        }
    }

    private SessionManager newManager(Path hls) {
        return newManager(hls, real);
    }

    private SessionManager newManager(Path hls, Clock managerClock) {
        Transcoder transcoder = new FfmpegTranscoder(null, new ProcessEncoderFactory(0), "ffmpeg", "software");
        manager = new SessionManager(transcoder, new NioSegmentFiles(), new PollingSegmentWaiter(real),
            new FakeScheduler(), managerClock, new SessionSettings(Duration.ofMinutes(2), Duration.ofMinutes(10),
            Long.MAX_VALUE, 2, "/dev/dri/renderD128"), new NioFileStamps(), hls, UUID::randomUUID);
        return manager;
    }

    private SessionView session(long edition, int startSegment) {
        SessionSpec spec = new SessionSpec(edition, "Clip", source, 0, index.cuts(), index.fps(), 640, 480, rungs,
            rungs.get(0), null, startSegment, true);
        return manager.create(spec);
    }

    @AfterEach
    void stopEncoders() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    /** Asks for a segment until it is there; the manager itself waits up to 6 s per ask. */
    private Path fetch(String id, String rung, int n) {
        Instant stop = real.instant().plus(DEADLINE);
        while (true) {
            try {
                return manager.segment(id, rung, n);
            } catch (SegmentNotReadyException e) {
                if (!real.instant().isBefore(stop)) {
                    throw new AssertionError("segment " + n + " of " + rung + " not ready within " + DEADLINE, e);
                }
            }
        }
    }

    private static String firstVideoPts(Path segment) throws IOException {
        CommandRunner.Result probed = RUNNER.run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "packet=pts_time", "-of", "csv=p=0", segment.toString()), TOOL_TIMEOUT);
        assertThat(probed.succeeded()).as("ffprobe of %s", segment).isTrue();
        return probed.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst()
            .orElseThrow(() -> new AssertionError("no video packet in " + segment));
    }

    private static void assertDecodes(Path segment) throws IOException {
        CommandRunner.Result decoded = RUNNER.run(List.of("ffmpeg", "-v", "error", "-nostdin", "-i",
            segment.toString(), "-f", "null", "-"), TOOL_TIMEOUT);
        assertThat(decoded.succeeded()).as("ffmpeg decodes %s", segment).isTrue();
    }

    @Test
    void theIndexHasIrregularCutsThatTheCopyRungCanFollowAndTheLadderHasAllThreeRungs() {
        assertThat(index.cuts().segmentCount()).isGreaterThanOrEqualTo(4);
        assertThat(index.cuts().startMs(0)).isZero();
        assertThat(index.cuts().copyOk()).isTrue();
        assertThat(index.fps()).isBetween(24.9, 25.1);
        assertThat(rungs).extracting(Rung::id).containsExactly("original", "360p", "240p");
    }

    @Test
    void theSessionsPlaylistsAreValid() {
        newManager(work.resolve("hls"));
        SessionView view = session(1, 0);

        String master = PlaylistWriter.master(view.rungs(), view.startRung(), view.pinned(), view.width(),
            view.height());
        String media = PlaylistWriter.media(view.cuts());

        assertThat(master).startsWith("#EXTM3U\n").contains("original/index.m3u8\n", "360p/index.m3u8\n",
            "240p/index.m3u8\n");
        assertThat(master.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")).count()).isEqualTo(3);
        assertThat(media).startsWith("#EXTM3U\n").endsWith("#EXT-X-ENDLIST\n").contains("#EXT-X-PLAYLIST-TYPE:VOD");
        List<String> lines = media.lines().toList();
        List<Double> durations = lines.stream().filter(line -> line.startsWith("#EXTINF:"))
            .map(line -> Double.parseDouble(line.substring("#EXTINF:".length(), line.indexOf(',')))).toList();
        assertThat(durations).hasSize(view.cuts().segmentCount());
        assertThat(durations.stream().mapToDouble(Double::doubleValue).sum()).isBetween(29.99, 30.01);
        assertThat(lines.stream().filter(line -> line.startsWith("seg-")).toList())
            .hasSize(view.cuts().segmentCount()).startsWith("seg-0.ts", "seg-1.ts");
    }

    @Test
    void everySegmentOfEveryRungDecodesAndStartsAtTheSamePtsOnEveryRung() throws IOException {
        newManager(work.resolve("hls"));
        String id = session(1, 0).id();
        int count = index.cuts().segmentCount();

        for (int n = 0; n < count; n++) {
            Path copy = fetch(id, "original", n);
            Path small = fetch(id, "360p", n);
            Path floor = fetch(id, "240p", n);
            assertDecodes(copy);
            assertDecodes(small);
            assertDecodes(floor);
            assertThat(firstVideoPts(small)).as("first video pts of segment %d on 360p vs the copy", n)
                .isEqualTo(firstVideoPts(copy));
            assertThat(firstVideoPts(floor)).as("first video pts of segment %d on 240p vs the copy", n)
                .isEqualTo(firstVideoPts(copy));
        }
    }

    @Test
    void aSessionStartedAtALaterSegmentProducesTheSamePtsAsTheFromZeroRun() throws IOException {
        newManager(work.resolve("hls"));
        int count = index.cuts().segmentCount();
        int restart = 2;
        String zero = session(1, 0).id();
        Map<String, String> expected = new LinkedHashMap<>();
        for (int n = restart; n < count; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                expected.put(rung + "/" + n, firstVideoPts(fetch(zero, rung, n)));
            }
        }

        String later = session(2, restart).id();
        Map<String, String> actual = new LinkedHashMap<>();
        List<Path> fetched = new ArrayList<>();
        for (int n = restart; n < count; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                Path segment = fetch(later, rung, n);
                fetched.add(segment);
                actual.put(rung + "/" + n, firstVideoPts(segment));
            }
        }

        assertThat(actual).isEqualTo(expected);
        for (Path segment : fetched) {
            assertDecodes(segment);
        }
    }

    @Test
    void aSessionThatExpiredFromIdlenessCarriesOnUnderTheSameIdWithTheSamePtsAsAFromZeroRun() throws IOException {
        ShiftedClock shifted = new ShiftedClock(real);
        newManager(work.resolve("hls"), shifted);
        int count = index.cuts().segmentCount();
        int resume = 2;
        String zero = session(1, 0).id();
        Map<String, String> expected = new LinkedHashMap<>();
        for (int n = resume; n < count; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                expected.put(rung + "/" + n, firstVideoPts(fetch(zero, rung, n)));
            }
        }
        String id = session(2, 0).id();
        for (int n = 0; n < resume; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                assertDecodes(fetch(id, rung, n));
            }
        }

        shifted.shiftBy(Duration.ofMinutes(10));
        manager.sweep();
        assertThat(manager.sessions()).isEmpty();

        Map<String, String> actual = new LinkedHashMap<>();
        List<Path> fetched = new ArrayList<>();
        for (int n = resume; n < count; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                Path segment = fetch(id, rung, n);
                fetched.add(segment);
                actual.put(rung + "/" + n, firstVideoPts(segment));
            }
        }

        assertThat(manager.sessions()).extracting(SessionDtos.SessionRow::id).containsExactly(id);
        assertThat(actual).isEqualTo(expected);
        for (Path segment : fetched) {
            assertDecodes(segment);
        }
    }

    @Test
    void seekingBackToBeforeTheRunsStartRestartsTheEncoderInTheSameSessionWithMatchingPts() throws IOException {
        newManager(work.resolve("hls"));
        int last = index.cuts().segmentCount() - 1;
        String reference = session(2, 0).id();
        Map<String, String> expected = new LinkedHashMap<>();
        for (int n = 0; n <= 1; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                expected.put(rung + "/" + n, firstVideoPts(fetch(reference, rung, n)));
            }
        }
        String id = session(1, last).id();
        assertDecodes(fetch(id, "360p", last));
        assertDecodes(fetch(id, "240p", last));

        Map<String, String> actual = new LinkedHashMap<>();
        for (int n = 0; n <= 1; n++) {
            for (String rung : List.of("original", "360p", "240p")) {
                Path segment = fetch(id, rung, n);
                assertDecodes(segment);
                actual.put(rung + "/" + n, firstVideoPts(segment));
            }
        }

        assertThat(actual).isEqualTo(expected);
    }
}
