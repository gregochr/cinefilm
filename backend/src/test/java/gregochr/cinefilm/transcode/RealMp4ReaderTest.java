package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.ProcessCommandRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sample-table reader against real ffmpeg-made MP4s, compared with the keyframe packets' presentation times as
 * ffprobe reports them ({@code -show_entries packet=pts_time,flags}, flags containing {@code K}): clips with
 * B-frames (so composition offsets and an edit list) and with irregular, forced keyframes. CI installs ffmpeg; the
 * test is skipped where it is not on the PATH, as on the dev Mac.
 */
@Tag("real-tools")
class RealMp4ReaderTest {

    private static final ProcessCommandRunner RUNNER = new ProcessCommandRunner();
    private static final String[] X264 = {"-c:v", "libx264", "-pix_fmt", "yuv420p", "-sc_threshold", "0"};

    @TempDir
    Path dir;

    @BeforeAll
    static void toolsAreInstalled() {
        assumeTrue(runs("ffprobe") && runs("ffmpeg"), "ffprobe and ffmpeg are not on the PATH");
    }

    private static boolean runs(String tool) {
        try {
            return RUNNER.run(List.of(tool, "-version"), Duration.ofSeconds(20)).succeeded();
        } catch (IOException e) {
            return false;
        }
    }

    private Path clip(String name, String movflags, String... encoderFlags) throws IOException {
        Path clip = dir.resolve(name);
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi",
            "-i", "testsrc=duration=20:size=160x120:rate=25"));
        command.addAll(List.of(X264));
        command.addAll(List.of(encoderFlags));
        command.addAll(List.of("-movflags", movflags, "-f", "mp4", clip.toString()));
        CommandRunner.Result made = RUNNER.run(command, Duration.ofSeconds(120));
        assertThat(made.succeeded()).as("ffmpeg generated the clip").isTrue();
        return clip;
    }

    private void assertSameAsFfprobe(Path clip) {
        KeyframeIndex fromTables = new Mp4SampleTableReader().index(clip);
        Mp4SampleTableReader failing = new Mp4SampleTableReader() {
            @Override
            public KeyframeIndex index(Path file) {
                throw new KeyframeIndexException("force the fallback");
            }
        };
        KeyframeIndex fromFfprobe = new KeyframeIndexer(failing, RUNNER, "ffprobe").index(clip);

        assertThat(fromTables.keyframesMs()).hasSameSizeAs(fromFfprobe.keyframesMs());
        for (int i = 0; i < fromTables.keyframesMs().size(); i++) {
            assertThat(fromTables.keyframesMs().get(i)).as("keyframe %d", i)
                .isBetween(fromFfprobe.keyframesMs().get(i) - 1, fromFfprobe.keyframesMs().get(i) + 1);
        }
        assertThat(fromFfprobe.fps()).isNull();
        assertThat(fromTables.fps()).isBetween(24.99, 25.01);
    }

    @Test
    void bFramesWithTheMoovAtTheEndAgreeWithFfprobe() throws IOException {
        assertSameAsFfprobe(clip("end.mp4", "+use_metadata_tags", "-bf", "2", "-g", "37"));
    }

    @Test
    void bFramesWithTheMoovAtTheStartAgreeWithFfprobe() throws IOException {
        assertSameAsFfprobe(clip("start.mp4", "+faststart", "-bf", "2", "-g", "37"));
    }

    @Test
    void irregularForcedKeyframesAgreeWithFfprobe() throws IOException {
        assertSameAsFfprobe(clip("irregular.mp4", "+faststart", "-bf", "2", "-g", "1000", "-force_key_frames",
            "1.3,5.7,6.1,12.9,13.04"));
    }
}
