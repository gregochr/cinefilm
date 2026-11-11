package gregochr.cinefilm.transcode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable state of one session. Only {@link SessionManager} touches it, always under its lock, so the fields
 * need no synchronisation of their own.
 */
final class TranscodeSession {

    /** How one encoder run ended up. */
    enum Status {
        /** Running. */
        RUNNING,
        /** Exited with status 0: every segment from its start to the end of its part is written. */
        FINISHED,
        /**
         * Stopped by the manager (seek, look-ahead, idle, shutdown), or a placeholder for a session that was rebuilt
         * and has no encoder started yet.
         */
        STOPPED,
        /** Exited with an error on its own. */
        CRASHED
    }

    /** One encoder process, started at a segment and running to the end of that segment's part of the film. */
    static final class Run {
        final int startSegment;
        /** The part (file) of the film the run encodes. */
        final int part;
        /** The last segment of that part: the run never writes beyond it. */
        final int lastSegment;
        EncoderProcess process;
        Status status = Status.RUNNING;
        /** The newest segment the encoder has started writing (its first rung's file exists). */
        int frontier;
        double speed;

        Run(int startSegment, int part, int lastSegment) {
            this.startSegment = startSegment;
            this.part = part;
            this.lastSegment = lastSegment;
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
    /**
     * For each part whose last run ended cleanly (and that no restart has rewritten since), the segment that run
     * started at: every segment of the part from there to its end is completely written. A part's last segment has no
     * successor to prove it complete (the next file's first segment may exist from an earlier run), so this says so
     * instead.
     */
    final Map<Integer, Integer> finishedFrom = new HashMap<>();
    /**
     * The size and modification time of each of the spec's files when the session was made, in part order; null when
     * any of them could not be read, so the session plays as usual but is never remembered.
     */
    final List<FileStamps.Stamp> stamps;
    /** True for a rebuilt session until its first encoder run has started. */
    boolean awaitingFirstRun;

    TranscodeSession(String id, SessionSpec spec, Path dir, Instant now, List<FileStamps.Stamp> stamps) {
        this.id = id;
        this.spec = spec;
        this.dir = dir;
        this.startedAt = now;
        this.lastRequest = now;
        this.lastRung = spec.startRung().id();
        this.playhead = spec.startSegment();
        this.deletedBelow = spec.startSegment();
        this.stamps = stamps;
    }

    private TranscodeSession(String id, SessionSpec spec, Path dir, Instant startedAt, Instant now,
                             List<FileStamps.Stamp> stamps, String lastRung, int playhead) {
        this.id = id;
        this.spec = spec;
        this.dir = dir;
        this.startedAt = startedAt;
        this.lastRequest = now;
        this.lastRung = lastRung;
        this.playhead = playhead;
        this.deletedBelow = playhead;
        this.stamps = stamps;
        this.awaitingFirstRun = true;
        CutList cuts = spec.cuts();
        int part = cuts.partOfSegment(playhead);
        Run placeholder = new Run(playhead, part, cuts.lastSegmentOfPart(part));
        placeholder.status = Status.STOPPED;
        this.run = placeholder;
    }

    /**
     * A session rebuilt from what was remembered: the same id, folder, spec, start time and last rung, the playhead
     * and the behind-deletion mark at the remembered playhead, a fresh crash budget, and a stopped placeholder run
     * (no process) so every path sees "no encoder" until the first segment request starts one.
     */
    static TranscodeSession rebuilt(String id, SessionSpec spec, Path dir, Instant startedAt, Instant now,
                                    List<FileStamps.Stamp> stamps, String lastRung, int playhead) {
        return new TranscodeSession(id, spec, dir, startedAt, now, stamps, lastRung, playhead);
    }

    Path segmentFile(String rungId, int n) {
        return dir.resolve(rungId).resolve(PlaylistWriter.segmentName(n));
    }
}
