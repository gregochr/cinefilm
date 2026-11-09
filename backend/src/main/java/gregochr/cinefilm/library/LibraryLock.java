package gregochr.cinefilm.library;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The one lock on the library: a scan and a Plex import both rewrite films, so only one of them runs at a time. Whoever
 * holds it says who it is, so a refused caller can say why.
 */
@Component
public class LibraryLock {

    /** Holder name of a library scan. */
    public static final String SCAN = "scan";

    /** Holder name of a Plex import. */
    public static final String IMPORT = "import";

    private final AtomicReference<String> holder = new AtomicReference<>();

    /**
     * Takes the lock if it is free.
     *
     * @param who {@link #SCAN} or {@link #IMPORT}
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
}
