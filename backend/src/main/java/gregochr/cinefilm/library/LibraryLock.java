package gregochr.cinefilm.library;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The one lock on the library: a scan, a Plex import and a Fix match all rewrite films, so only one of them runs at
 * a time. Whoever holds it says who it is, so a refused caller can say why.
 */
@Component
public class LibraryLock {

    /** Holder name of a library scan. */
    public static final String SCAN = "scan";

    /** Holder name of a Plex import. */
    public static final String IMPORT = "import";

    /** Holder name of a Fix match (search or apply). */
    public static final String MATCH = "match";

    /** Holder name of an Edit editions save. */
    public static final String EDIT = "edit";

    /** Holder name of an Admin change to a forced subtitle file. */
    public static final String SUBTITLE = "subtitle";

    /** Holder name of the credits detection saving a result. */
    public static final String CREDITS = "credits";

    /** Holder name of the credits import writing its rows (taken only for that short write). */
    public static final String PLEX_CREDITS = "plex-credits";

    private final AtomicReference<String> holder = new AtomicReference<>();

    /**
     * Takes the lock if it is free.
     *
     * @param who {@link #SCAN}, {@link #IMPORT} or {@link #MATCH}
     * @return true when the caller now holds it
     */
    public boolean tryAcquire(String who) {
        return holder.compareAndSet(null, who);
    }

    /** Gives the lock up. */
    public void release() {
        holder.set(null);
    }

    /** Who holds the lock, or null when it is free. */
    public String holder() {
        return holder.get();
    }

    /** True when {@code who} holds the lock. */
    public boolean heldBy(String who) {
        return who.equals(holder.get());
    }

    /**
     * What to tell a caller the lock refused, worded for whoever holds it now. A scan (or a holder that let go
     * between the refusal and this read) is "A scan is running".
     */
    public String busyText() {
        String who = holder.get();
        if (who == null) {
            return "A scan is running";
        }
        return switch (who) {
            case IMPORT -> "A Plex import is running";
            case MATCH -> "A film match is being fixed; try again in a moment";
            case EDIT -> "Editions are being edited; try again in a moment";
            case SUBTITLE -> "Subtitles are being changed or checked; try again in a moment";
            case CREDITS -> "Credits are being saved; try again in a moment";
            case PLEX_CREDITS -> "Plex credits are being imported; try again in a moment";
            default -> "A scan is running";
        };
    }
}
