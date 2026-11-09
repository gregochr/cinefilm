package gregochr.cinefilm.transcode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The {@link Transcoder} over the local ffmpeg. The mode is detected once, on first use or at start-up, unless
 * {@code cinefilm.transcode.mode} forces it ({@code vaapi}, {@code software} or {@code unavailable}; {@code auto}
 * probes).
 */
@Service
public class FfmpegTranscoder implements Transcoder {

    private final EncoderProbe probe;
    private final EncoderFactory factory;
    private final String ffmpeg;
    private final String configured;
    private EncoderMode detected;

    /** Creates the transcoder. */
    public FfmpegTranscoder(EncoderProbe probe, EncoderFactory factory,
                            @Value("${cinefilm.tools.ffmpeg:ffmpeg}") String ffmpeg,
                            @Value("${cinefilm.transcode.mode:auto}") String configured) {
        this.probe = probe;
        this.factory = factory;
        this.ffmpeg = ffmpeg;
        this.configured = configured;
    }

    /** Detects (and logs) the mode as soon as the application is up. */
    @EventListener(ApplicationReadyEvent.class)
    public void detectAtStartup() {
        mode();
    }

    @Override
    public synchronized EncoderMode mode() {
        if (detected == null) {
            detected = "auto".equalsIgnoreCase(configured) ? probe.detect()
                : EncoderMode.valueOf(configured.toUpperCase(Locale.ROOT));
        }
        return detected;
    }

    @Override
    public EncoderProcess start(TranscodePlan plan, EncoderListener listener) throws IOException {
        for (Rung rung : plan.rungs()) {
            Files.createDirectories(plan.rungDir(rung));
        }
        return factory.start(FfmpegCommandBuilder.build(ffmpeg, plan), listener);
    }
}
