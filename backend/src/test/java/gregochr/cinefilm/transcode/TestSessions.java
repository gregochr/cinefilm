package gregochr.cinefilm.transcode;

import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionDtos.SessionRow;
import gregochr.cinefilm.transcode.SessionSpec.PartSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Short forms of the session records for tests: the production records have one constructor each. */
public final class TestSessions {

    private TestSessions() {
    }

    /** A request that names no viewing. */
    public static CreateRequest createRequest(Long audioStreamId, Long startMs, String quality, Long probeKbps) {
        return new CreateRequest(audioStreamId, startMs, quality, probeKbps, null);
    }

    /** A row whose viewing is not named. */
    public static SessionRow sessionRow(String id, String filmTitle, long editionId, String rung, Double speed,
                                        int segmentsAhead, Instant startedAt, boolean unavailable) {
        return new SessionRow(id, filmTitle, editionId, rung, speed, segmentsAhead, startedAt, unavailable, null);
    }

    /** A session of several parts whose viewing is not named. */
    public static SessionSpec spec(long editionId, String filmTitle, List<PartSource> parts, CutList cuts, int width,
                                   int height, List<Rung> rungs, Rung startRung, String pinned, int startSegment,
                                   boolean hardwareDecode) {
        return new SessionSpec(editionId, filmTitle, parts, cuts, width, height, rungs, startRung, pinned,
            startSegment, hardwareDecode, null);
    }

    /** A film in one piece whose viewing is not named. */
    public static SessionSpec spec(long editionId, String filmTitle, Path source, int audioIndex, CutList cuts,
                                   Double fps, int width, int height, List<Rung> rungs, Rung startRung,
                                   String pinned, int startSegment, boolean hardwareDecode) {
        return new SessionSpec(editionId, filmTitle, List.of(new PartSource(source, audioIndex, fps)), cuts, width,
            height, rungs, startRung, pinned, startSegment, hardwareDecode, null);
    }
}
