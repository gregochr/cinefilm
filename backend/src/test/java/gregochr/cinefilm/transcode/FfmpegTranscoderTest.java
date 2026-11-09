package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegTranscoderTest {

    @TempDir
    Path out;

    private final EncoderProbe probe = mock(EncoderProbe.class);
    private final List<List<String>> started = new ArrayList<>();
    private final EncoderProcess process = new EncoderProcess() {
        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public OptionalInt exitCode() {
            return OptionalInt.empty();
        }

        @Override
        public void stop() {
        }
    };
    private final EncoderFactory factory = (command, listener) -> {
        started.add(command);
        return process;
    };

    private FfmpegTranscoder transcoder(String mode) {
        return new FfmpegTranscoder(probe, factory, "/usr/bin/ffmpeg", mode);
    }

    @Test
    void autoDetectsOnceAndRemembersTheAnswer() {
        when(probe.detect()).thenReturn(EncoderMode.VAAPI);
        FfmpegTranscoder transcoder = transcoder("auto");

        assertThat(transcoder.mode()).isEqualTo(EncoderMode.VAAPI);
        assertThat(transcoder.mode()).isEqualTo(EncoderMode.VAAPI);
        verify(probe, times(1)).detect();
    }

    @Test
    void autoIsCaseInsensitiveAndStartupDetectionRunsTheProbe() {
        when(probe.detect()).thenReturn(EncoderMode.SOFTWARE);
        FfmpegTranscoder transcoder = transcoder("AUTO");

        transcoder.detectAtStartup();

        assertThat(transcoder.mode()).isEqualTo(EncoderMode.SOFTWARE);
        verify(probe, times(1)).detect();
    }

    @Test
    void aConfiguredModeIsUsedWithoutProbing() {
        assertThat(transcoder("software").mode()).isEqualTo(EncoderMode.SOFTWARE);
        assertThat(transcoder("Unavailable").mode()).isEqualTo(EncoderMode.UNAVAILABLE);
        assertThat(transcoder("vaapi").mode()).isEqualTo(EncoderMode.VAAPI);
        verify(probe, never()).detect();
    }

    @Test
    void anUnknownConfiguredModeFailsLoudly() {
        assertThatThrownBy(() -> transcoder("gpu").mode()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startBuildsTheCommandForThePlanAndHandsItToTheFactory() throws IOException {
        CutList cuts = CutList.parse("0,4000", 8000);
        Rung original = new Rung("original", 1080, 5_000_000, true);
        Rung low = new Rung("480p", 480, 1_000_000, false);
        TranscodePlan plan = new TranscodePlan(Path.of("/films/a.mp4"), out, List.of(original, low), 0, cuts, 0,
            EncoderMode.VAAPI, 2, 25.0);

        EncoderProcess result = transcoder("vaapi").start(plan, null);

        assertThat(result).isSameAs(process);
        assertThat(started).containsExactly(FfmpegCommandBuilder.build("/usr/bin/ffmpeg", plan));
        assertThat(out.resolve("original")).isDirectory();
        assertThat(out.resolve("480p")).isDirectory();
    }
}
