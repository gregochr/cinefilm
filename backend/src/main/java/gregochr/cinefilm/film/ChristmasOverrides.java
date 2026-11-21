package gregochr.cinefilm.film;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's say over which films count as Christmas films, as TMDB ids: an included film shows although TMDB's
 * {@code christmas} keyword misses it, an excluded one is hidden although the keyword catches it (exclude beats
 * include beats the keyword: {@link WeeklyThemeSelector}). A film has one choice at a time.
 */
@Service
public class ChristmasOverrides {

    private final ChristmasOverrideRepository repository;
    private final Clock clock;

    /** Creates the service over its table and the application clock. */
    public ChristmasOverrides(ChristmasOverrideRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Both sets of the owner's choices, read together. */
    public record Snapshot(Set<Long> include, Set<Long> exclude) {
    }

    /** Both sets from one read of the table (Home asks on every load, so it is one query, not two). */
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        Set<Long> include = new HashSet<>();
        Set<Long> exclude = new HashSet<>();
        for (ChristmasOverride row : repository.findAll()) {
            (row.getChoice() == ChristmasChoice.INCLUDE ? include : exclude).add(row.getTmdbId());
        }
        return new Snapshot(Set.copyOf(include), Set.copyOf(exclude));
    }

    /** The TMDB ids of the films the owner included. */
    @Transactional(readOnly = true)
    public Set<Long> includes() {
        return ids(ChristmasChoice.INCLUDE);
    }

    /** The TMDB ids of the films the owner excluded. */
    @Transactional(readOnly = true)
    public Set<Long> excludes() {
        return ids(ChristmasChoice.EXCLUDE);
    }

    /**
     * Records the choice for a film, changing an earlier one in place (so a film never has both, and the row keeps
     * the time it was first made).
     */
    @Transactional
    public void set(long tmdbId, ChristmasChoice choice) {
        repository.findById(tmdbId).ifPresentOrElse(row -> row.setChoice(choice),
            () -> repository.save(new ChristmasOverride(tmdbId, choice, clock.instant())));
    }

    /** Forgets the owner's choice for a film, so the keyword decides again. A film with none is left alone. */
    @Transactional
    public void remove(long tmdbId) {
        repository.deleteById(tmdbId);
    }

    private Set<Long> ids(ChristmasChoice choice) {
        return repository.findByChoice(choice).stream().map(ChristmasOverride::getTmdbId)
            .collect(Collectors.toUnmodifiableSet());
    }
}
