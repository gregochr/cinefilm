package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import org.springframework.stereotype.Component;

/**
 * Keeps the deny list in force: the file as read by the latest pass, or, when that read failed, the last copy read
 * successfully since start-up. Only {@link #refresh()} reads the file, and only a pass calls it, so Clear and the
 * candidates (which call {@link #current()}) never touch the file.
 *
 * <p>Thread safety: the copy is an immutable record held in a volatile field, so a reader sees either the old copy
 * or the new one, whole. {@code refresh} is synchronized, and both the pass and Clear run under the library lock,
 * which also keeps a pass and a Clear from interleaving.
 */
@Component
public class CreditsDenyListKeeper {

    /**
     * What a refresh found.
     *
     * @param inForce the list now in force; null when there is none
     * @param fellBack true when the file could not be read and {@code inForce} is the copy from an earlier read
     */
    public record Refreshed(Parsed inForce, boolean fellBack) {
    }

    private final CreditsDenyFileReader reader;
    private volatile Parsed good;

    /** Creates the keeper with nothing in force. */
    public CreditsDenyListKeeper(CreditsDenyFileReader reader) {
        this.reader = reader;
    }

    /** Reads the file now: a good read becomes the list in force, a bad one leaves the earlier copy (if any). */
    public synchronized Refreshed refresh() {
        Parsed read = reader.read().orElse(null);
        if (read != null) {
            good = read;
            return new Refreshed(read, false);
        }
        return new Refreshed(good, good != null);
    }

    /** The list in force from the latest refresh; null when there is none. Never reads the file. */
    public Parsed current() {
        return good;
    }
}
