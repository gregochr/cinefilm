package gregochr.cinefilm.film;

import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.playback.PlaybackState;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A film together with the playback state of its editions; the unit the browse rules work on. */
public record FilmView(Film film, Map<Long, PlaybackState> states) {

    /** The playback state of an edition, if it has ever been played or marked. */
    public Optional<PlaybackState> state(Edition edition) {
        return Optional.ofNullable(states.get(edition.getId()));
    }

    /** A film counts as watched in browse views when any edition is watched. */
    public boolean watched() {
        return film.getEditions().stream().anyMatch(e -> state(e).map(PlaybackState::isWatched).orElse(false));
    }

    /** The edition with the most recent {@code lastPlayedAt}. */
    public Optional<Edition> lastPlayedEdition() {
        return film.getEditions().stream()
            .filter(e -> state(e).map(PlaybackState::getLastPlayedAt).isPresent())
            .max(Comparator.comparing((Edition e) -> state(e).orElseThrow().getLastPlayedAt()));
    }

    /** When the film was last played (its last played edition), or null. */
    public Instant lastPlayedAt() {
        return lastPlayedEdition().map(e -> state(e).orElseThrow().getLastPlayedAt()).orElse(null);
    }

    /** Resume info: the most recently played edition, if it has a saved position (even if watched before). */
    public Optional<FilmDtos.Progress> progress() {
        return lastPlayedEdition()
            .filter(e -> state(e).map(s -> s.getPositionMs() > 0).orElse(false))
            .map(e -> new FilmDtos.Progress(state(e).orElseThrow().getPositionMs(), e.getRuntimeMs(), e.getId()));
    }

    /** The latest sign of life across editions: last played, else watched-at; null if neither. */
    public Instant lastActivityAt() {
        return film.getEditions().stream()
            .map(e -> state(e).map(s -> s.getLastPlayedAt() != null ? s.getLastPlayedAt() : s.getWatchedAt())
                .orElse(null))
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }

    /** Directors in credit order. */
    public List<Person> directors() {
        return film.getCredits().stream()
            .filter(c -> c.getRole() == CreditRole.DIRECTOR)
            .sorted(Comparator.comparingInt(Credit::getSequence))
            .map(Credit::getPerson)
            .toList();
    }

    /** Cast credits in billing order. */
    public List<Credit> cast() {
        return film.getCredits().stream()
            .filter(c -> c.getRole() == CreditRole.CAST)
            .sorted(Comparator.comparingInt(Credit::getSequence))
            .toList();
    }

    /** Directors' names joined for display. */
    public String directorNames() {
        return String.join(", ", directors().stream().map(Person::getName).toList());
    }
}
