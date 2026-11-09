package gregochr.cinefilm.transcode;

/** The encoder has not produced the segment yet; the client should ask again (503 with {@code Retry-After}). */
public class SegmentNotReadyException extends RuntimeException {

    /** Creates the exception. */
    public SegmentNotReadyException() {
        super("The segment is not ready yet");
    }
}
