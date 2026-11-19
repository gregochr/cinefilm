package gregochr.cinefilm.film;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The owner's say over which films count as Christmas films, as TMDB ids: {@code cinefilm.holiday.christmas.include}
 * adds films TMDB's {@code christmas} keyword misses, {@code cinefilm.holiday.christmas.exclude} removes films it
 * wrongly catches (exclude beats include beats the keyword). Both are comma-separated and empty by default.
 */
@ConfigurationProperties(prefix = "cinefilm.holiday.christmas")
public record HolidayOverrides(List<Long> include, List<Long> exclude) {

    /** No overrides: the keyword alone decides. */
    public static final HolidayOverrides NONE = new HolidayOverrides(List.of(), List.of());

    /** Treats an unset list as empty and keeps the lists unmodifiable. */
    public HolidayOverrides {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    /** The included TMDB ids as a set. */
    public Set<Long> includeSet() {
        return Set.copyOf(include);
    }

    /** The excluded TMDB ids as a set. */
    public Set<Long> excludeSet() {
        return Set.copyOf(exclude);
    }
}
