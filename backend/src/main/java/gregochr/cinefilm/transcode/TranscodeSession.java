package gregochr.cinefilm.transcode;

import java.nio.file.Path;
import java.time.Instant;

/**
 * The mutable state of one session. Only {@link SessionManager} touches it, always under its lock, so the fields
 * need no synchronisation of their own.
 */
final class TranscodeSession {

    /** How one encoder run ended up. */
    enum Status {
        /** Running. */
        RUNNING,
        /** Exited with status 0: every segment from its start to the end of the film is written. */
        FINISHED,
        /** Stopped by the manager (seek, look-ahead, idle, shutdown). */
        STOPPED,
        /** Exited with an error on its own. */
        CRASHED
    }

    /** One encoder process, started at a segment. */
    static final class Run {
        final int startSegment;
        EncoderProcess process;
        Status status = Status.RUNNING;
        /** The newest segment the encoder has started writing (its first rung's file exists). */
        int frontier;
        double speed;

        Run(int startSegment) {
            this.startSegment = startSegment;
            this.frontier = startSegment;
        }

        boolean running() {
            return status == Status.RUNNING;
        }
    }

    final String id;
    final SessionSpec spec;
    final Path dir;
    final Instant startedAt;
    Instant lastRequest;
    Run run;
    int crashes;
    boolean unavailable;
    String lastRung;
    /** The segment the player is at: the newest requested, moved back only by a seek of more than two minutes. */
    int playhead;
    /** Every segment below this has been deleted as too far behind the playhead. */
    int deletedBelow;

    TranscodeSession(String id, SessionSpec spec, Path dir, Instant now) {
        this.id = id;
        this.spec = spec;
        this.dir = dir;
        this.startedAt = now;
        this.lastRequest = now;
        this.lastRung = spec.startRung().id();
        this.playhead = spec.startSegment();
        this.deletedBelow = spec.startSegment();
    }

    Path segmentFile(String rungId, int n) {
        return dir.resolve(rungId).resolve(PlaylistWriter.segmentName(n));
    }
}
