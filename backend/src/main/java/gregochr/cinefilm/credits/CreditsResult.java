package gregochr.cinefilm.credits;

import java.util.List;

/**
 * What the analysis concluded about one file.
 *
 * @param verdict how sure it is
 * @param offsetMs where the credits start, in milliseconds from the start of the file; null for
 *     {@link CreditsVerdict#NOT_FOUND}, and for an {@link CreditsVerdict#UNSURE} result where no place is known
 * @param reasons plain sentences saying why, in the order they were found
 */
public record CreditsResult(CreditsVerdict verdict, Long offsetMs, List<String> reasons) {

    /**
     * Validates and copies the reasons.
     *
     * @throws IllegalArgumentException when a found result has no offset or a not-found result has one
     */
    public CreditsResult {
        if (verdict == null) {
            throw new IllegalArgumentException("A result needs a verdict");
        }
        if (verdict == CreditsVerdict.FOUND && offsetMs == null) {
            throw new IllegalArgumentException("A found result needs an offset");
        }
        if (verdict == CreditsVerdict.NOT_FOUND && offsetMs != null) {
            throw new IllegalArgumentException("A not-found result has no offset");
        }
        reasons = List.copyOf(reasons);
    }

    /** A confident result. */
    static CreditsResult found(long offsetMs, String reason) {
        return new CreditsResult(CreditsVerdict.FOUND, offsetMs, List.of(reason));
    }

    /** A doubtful result; the offset is where the credits would start, or null when unknown. */
    static CreditsResult unsure(Long offsetMs, String reason) {
        return new CreditsResult(CreditsVerdict.UNSURE, offsetMs, List.of(reason));
    }

    /** No credits found. */
    static CreditsResult notFound(String reason) {
        return new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of(reason));
    }
}
