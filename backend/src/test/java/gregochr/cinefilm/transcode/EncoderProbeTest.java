package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.CommandRunner.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncoderProbeTest {

    private static final Path DEVICE = Path.of("/dev/dri/renderD128");
    private static final String BOTH = " V....D libx264              libx264 H.264\n"
        + " V....D h264_vaapi           H.264/AVC (VAAPI) (codec h264)\n";
    private static final String SOFTWARE_ONLY = " V....D libx264              libx264 H.264\n";
    private static final String VAAPI_ONLY = " V....D h264_vaapi           H.264/AVC (VAAPI) (codec h264)\n";

    private final List<List<String>> commands = new ArrayList<>();

    private EncoderProbe probe(String encoders, Result vaapiTest, boolean deviceReadable) {
        CommandRunner runner = (command, timeout) -> {
            commands.add(command);
            if (command.contains("-encoders")) {
                if (encoders == null) {
                    throw new IOException("ffmpeg: not found");
                }
                return new Result(0, encoders, false);
            }
            return vaapiTest;
        };
        return new EncoderProbe(runner, "ffmpeg", DEVICE, device -> deviceReadable && device.equals(DEVICE));
    }

    private static final Result OK = new Result(0, "", false);

    @Test
    void aReadableDeviceAndAWorkingVaapiEncodeSelectVaapi() {
        assertThat(probe(BOTH, OK, true).detect()).isEqualTo(EncoderMode.VAAPI);
    }

    @Test
    void theVaapiTestEncodesTestPatternThroughTheConfiguredDeviceIntoNothing() {
        probe(BOTH, OK, true).detect();

        assertThat(commands).containsExactly(List.of("ffmpeg", "-hide_banner", "-encoders"),
            List.of("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-init_hw_device",
                "vaapi=va:/dev/dri/renderD128", "-filter_hw_device", "va", "-f", "lavfi", "-i",
                "testsrc2=size=320x240:rate=5", "-frames:v", "5", "-vf", "format=nv12,hwupload", "-c:v",
                "h264_vaapi", "-f", "null", "-"));
    }

    @Test
    void aFailingVaapiEncodeFallsBackToSoftware() {
        assertThat(probe(BOTH, new Result(1, "", false), true).detect()).isEqualTo(EncoderMode.SOFTWARE);
        assertThat(probe(BOTH, new Result(-1, "", true), true).detect()).isEqualTo(EncoderMode.SOFTWARE);
    }

    @Test
    void aVaapiTestThatCannotBeRunFallsBackToSoftware() {
        CommandRunner runner = (command, timeout) -> {
            if (command.contains("-encoders")) {
                return new Result(0, BOTH, false);
            }
            throw new IOException("cannot start");
        };

        assertThat(new EncoderProbe(runner, "ffmpeg", DEVICE, device -> true).detect())
            .isEqualTo(EncoderMode.SOFTWARE);
    }

    @Test
    void anUnreadableDeviceIsNotTestedAndSoftwareIsUsed() {
        assertThat(probe(BOTH, OK, false).detect()).isEqualTo(EncoderMode.SOFTWARE);
        assertThat(commands).hasSize(1);
    }

    @Test
    void withoutTheVaapiEncoderListedTheDeviceIsNotTested() {
        assertThat(probe(SOFTWARE_ONLY, OK, true).detect()).isEqualTo(EncoderMode.SOFTWARE);
        assertThat(commands).hasSize(1);
    }

    @Test
    void vaapiWithoutLibx264IsStillVaapi() {
        assertThat(probe(VAAPI_ONLY, OK, true).detect()).isEqualTo(EncoderMode.VAAPI);
    }

    @Test
    void vaapiThatFailsWithoutLibx264LeavesNothing() {
        assertThat(probe(VAAPI_ONLY, new Result(1, "", false), true).detect()).isEqualTo(EncoderMode.UNAVAILABLE);
    }

    @Test
    void aSimilarNameIsNotTheEncoder() {
        assertThat(probe(" V....D libx264rgb  x\n V....D xh264_vaapi y\n", OK, true).detect())
            .isEqualTo(EncoderMode.UNAVAILABLE);
    }

    @Test
    void noFfmpegOrAFailingListingIsUnavailable() {
        assertThat(probe(null, OK, true).detect()).isEqualTo(EncoderMode.UNAVAILABLE);
        CommandRunner failing = (command, timeout) -> new Result(1, BOTH, false);
        assertThat(new EncoderProbe(failing, "ffmpeg", DEVICE, device -> true).detect())
            .isEqualTo(EncoderMode.UNAVAILABLE);
        CommandRunner slow = (command, timeout) -> new Result(-1, BOTH, true);
        assertThat(new EncoderProbe(slow, "ffmpeg", DEVICE, device -> true).detect())
            .isEqualTo(EncoderMode.UNAVAILABLE);
    }

    @Test
    void theProbeUsesShortTimeouts() {
        List<Duration> timeouts = new ArrayList<>();
        CommandRunner runner = (command, timeout) -> {
            timeouts.add(timeout);
            return new Result(0, BOTH, false);
        };

        new EncoderProbe(runner, "ffmpeg", DEVICE, device -> true).detect();

        assertThat(timeouts).containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(30));
    }

    @Test
    void theSpringConstructorReadsTheDeviceFromAPath() {
        CommandRunner runner = (command, timeout) -> new Result(0, SOFTWARE_ONLY, false);

        assertThat(new EncoderProbe(runner, "ffmpeg", "/definitely/not/a/device").detect())
            .isEqualTo(EncoderMode.SOFTWARE);
    }
}
