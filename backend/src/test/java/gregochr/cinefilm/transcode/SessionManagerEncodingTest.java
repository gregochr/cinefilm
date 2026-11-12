package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.transcode.SessionFakes.FakeFiles;
import gregochr.cinefilm.transcode.SessionFakes.FakeScheduler;
import gregochr.cinefilm.transcode.SessionFakes.FakeStamps;
import gregochr.cinefilm.transcode.SessionFakes.FakeTranscoder;
import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import gregochr.cinefilm.transcode.SessionFakes.ScriptedWaiter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** {@link SessionManager#isEncoding}: what background work waits on so that playback comes first. */
class SessionManagerEncodingTest {

    private static final Path SOURCE = Path.of("/library/Heat.m4v");
    private static final CutList CUTS = CutList.fromKeyframes(
        IntStream.range(0, 100).mapToObj(i -> i * 4000L).toList(), 400_000);
    private static final List<Rung> RUNGS = Ladder.rungs(1080, 24_000_000, true, EncoderMode.VAAPI).subList(0, 2);

    private final FakeTranscoder transcoder = new FakeTranscoder();
    private int counter;
    private final SessionManager manager = new SessionManager(transcoder, new FakeFiles(), new ScriptedWaiter(),
        new FakeScheduler(), new MutableClock(),
        new SessionSettings(Duration.ofMinutes(2), Duration.ofMinutes(10), 1000, 2, "/dev/dri/renderD129"),
        new FakeStamps(), Path.of("/cache/hls"), () -> new UUID(0, ++counter));

    private SessionView create() {
        return manager.create(new SessionSpec(7, "Heat", SOURCE, 1, CUTS, 25.0, 1920, 1080, RUNGS, RUNGS.get(0),
            null, 0, true));
    }

    @Test
    void nothingIsEncodingBeforeAnySessionExists() {
        assertThat(manager.isEncoding()).isFalse();
    }

    @Test
    void aSessionWhoseEncoderIsRunningIsEncoding() {
        create();

        assertThat(manager.isEncoding()).isTrue();
    }

    @Test
    void aSessionWhoseEncoderHasFinishedIsNotEncoding() {
        create();

        transcoder.lastListener().onExit(0, "");

        assertThat(manager.isEncoding()).isFalse();
    }
}
