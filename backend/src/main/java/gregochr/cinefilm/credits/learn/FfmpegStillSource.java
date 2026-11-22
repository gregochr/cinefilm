package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.GreyFrame;
import java.nio.file.Path;
import java.util.Optional;

/** The stills from ffmpeg, through {@link CreditsFrameReader}, one single-frame seek each. */
final class FfmpegStillSource implements StillSource {

    /** The reader's jobs need a length; single-frame seeks never use it, so this is only a usable number. */
    private static final long UNUSED_DURATION_MS = Long.MAX_VALUE / 4;

    private final CreditsFrameReader reader;

    FfmpegStillSource(CreditsFrameReader reader) {
        this.reader = reader;
    }

    @Override
    public Reading open(Path file) {
        CreditsFrameReader.Job job = reader.open(file, UNUSED_DURATION_MS, () -> { }, 0);
        return new Reading() {
            @Override
            public Optional<GreyFrame> frameAt(long fileMs) {
                return job.frameOrEnd(fileMs);
            }

            @Override
            public Optional<byte[]> fullPngAt(long fileMs) {
                return job.fullPngOrEnd(fileMs);
            }

            @Override
            public void close() {
                job.close();
            }
        };
    }
}
