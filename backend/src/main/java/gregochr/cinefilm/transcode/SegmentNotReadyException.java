package gregochr.cinefilm.transcode;

/**
 * Something the client asked for is not ready yet (a segment the encoder has not produced, the keyframe index of a
 * split film's later part, or the film's file when a session that went quiet cannot be rebuilt because the file
 * cannot be read right now); the client should ask again (503 with {@code Retry-After}).
 */
public class SegmentNotReadyException extends RuntimeException {

    /** Creates the exception. */
    public SegmentNotReadyException() {
        super("The segment is not ready yet");
    }

    /** Creates the exception with the message returned to the client. */
    public SegmentNotReadyException(String message) {
        super(message);
    }
}
