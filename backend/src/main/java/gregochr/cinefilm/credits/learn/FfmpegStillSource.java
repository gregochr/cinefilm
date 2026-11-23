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
        return new JobReading(reader.open(file, UNUSED_DURATION_MS, () -> { }, 0));
    }

    /** One open file: reads go to the job, and closing the reading closes the job, which deletes its directory. */
    private static final class JobReading implements Reading {

        private final CreditsFrameReader.Job job;

        JobReading(CreditsFrameReader.Job job) {
            this.job = job;
        }

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
    }
}
