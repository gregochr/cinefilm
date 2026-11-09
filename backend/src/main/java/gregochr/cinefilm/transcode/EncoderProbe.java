package gregochr.cinefilm.transcode;

import gregochr.cinefilm.library.CommandRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out what the server can encode with: {@code ffmpeg -encoders} must list {@code libx264} or
 * {@code h264_vaapi}; VAAPI is chosen only when the render node is readable and a tiny {@code h264_vaapi} encode
 * really works, else {@code libx264} (software), else nothing. Logs its verdict once.
 */
@Component
public class EncoderProbe {

    private static final Logger LOG = LoggerFactory.getLogger(EncoderProbe.class);

    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration ENCODE_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern VAAPI_ENCODER = Pattern.compile("\\bh264_vaapi\\b");
    private static final Pattern SOFTWARE_ENCODER = Pattern.compile("\\blibx264\\b");

    private final CommandRunner runner;
    private final String ffmpeg;
    private final Path device;
    private final Predicate<Path> readable;

    /** Creates the probe; the binary is {@code cinefilm.tools.ffmpeg}, the device {@code cinefilm.transcode.device}. */
    @Autowired
    public EncoderProbe(CommandRunner runner, @Value("${cinefilm.tools.ffmpeg:ffmpeg}") String ffmpeg,
                        @Value("${cinefilm.transcode.device:/dev/dri/renderD128}") String device) {
        this(runner, ffmpeg, Path.of(device), Files::isReadable);
    }

    EncoderProbe(CommandRunner runner, String ffmpeg, Path device, Predicate<Path> readable) {
        this.runner = runner;
        this.ffmpeg = ffmpeg;
        this.device = device;
        this.readable = readable;
    }

    /** The command that lists the encoders. */
    List<String> listCommand() {
        return List.of(ffmpeg, "-hide_banner", "-encoders");
    }

    /** The command that encodes a fraction of a second of test pattern with {@code h264_vaapi} into nothing. */
    List<String> vaapiTestCommand() {
        return List.of(ffmpeg, "-nostdin", "-hide_banner", "-v", "error",
            "-init_hw_device", "vaapi=va:" + device, "-filter_hw_device", "va",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=5", "-frames:v", "5",
            "-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi", "-f", "null", "-");
    }

    /**
     * Detects the encoder mode.
     *
     * @return VAAPI, SOFTWARE or UNAVAILABLE
     */
    public EncoderMode detect() {
        EncoderMode mode = probe();
        LOG.info("Transcoding encoder: {}", mode);
        return mode;
    }

    private EncoderMode probe() {
        String encoders = run(listCommand(), LIST_TIMEOUT);
        if (encoders == null) {
            return EncoderMode.UNAVAILABLE;
        }
        if (VAAPI_ENCODER.matcher(encoders).find() && readable.test(device) && run(vaapiTestCommand(),
            ENCODE_TIMEOUT) != null) {
            return EncoderMode.VAAPI;
        }
        return SOFTWARE_ENCODER.matcher(encoders).find() ? EncoderMode.SOFTWARE : EncoderMode.UNAVAILABLE;
    }

    /** The output of a command that ran and exited 0, else null. */
    private String run(List<String> command, Duration timeout) {
        try {
            CommandRunner.Result result = runner.run(command, timeout);
            return result.succeeded() ? result.stdout() : null;
        } catch (IOException e) {
            LOG.info("ffmpeg could not be run: {}", e.getMessage());
            return null;
        }
    }
}
