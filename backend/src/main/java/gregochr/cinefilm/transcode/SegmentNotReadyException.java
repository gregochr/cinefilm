package gregochr.cinefilm.transcode;

/**
 * Something the client asked for is not ready yet (a segment the encoder has not produced, or the keyframe index of a
 * split film's later part); the client should ask again (503 with {@code Retry-After}).
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
