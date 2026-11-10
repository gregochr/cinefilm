package gregochr.cinefilm.library;

import gregochr.cinefilm.film.Edition;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the edition a film opens on when the owner has not chosen one: an untagged edition ("Original") or one whose
 * name contains "theatrical", else the longest. Editions that cannot be played are passed over while any can.
 */
public final class DefaultEdition {

    private static final String UNTAGGED = "original";

    private DefaultEdition() {
    }

    /**
     * Chooses the default among a film's editions.
     *
     * @param editions the film's editions (in id order)
     * @return the default, or empty when there are none
     */
    public static Optional<Edition> choose(List<Edition> editions) {
        List<Edition> playable = editions.stream().filter(e -> !e.hiddenFromBrowse()).toList();
        List<Edition> pool = playable.isEmpty() ? editions : playable;
        Optional<Edition> plain = pool.stream().filter(DefaultEdition::isPlain).findFirst();
        if (plain.isPresent()) {
            return plain;
        }
        return pool.stream().max(Comparator.comparingLong(Edition::getRuntimeMs));
    }

    private static boolean isPlain(Edition edition) {
        String name = edition.getName().toLowerCase(Locale.ROOT);
        return name.startsWith(UNTAGGED) || name.contains("theatrical");
    }
}
