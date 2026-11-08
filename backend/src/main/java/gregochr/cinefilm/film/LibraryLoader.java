package gregochr.cinefilm.film;

import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Loads films together with their playback state. Call inside a read transaction. */
@Component
public class LibraryLoader {

    private final FilmRepository films;
    private final PlaybackStateRepository states;

    public LibraryLoader(FilmRepository films, PlaybackStateRepository states) {
        this.films = films;
        this.states = states;
    }

    /** Every film in the library. */
    public List<FilmView> loadAll() {
        return views(films.findAll());
    }

    /** Wraps the given films with the current playback state. */
    public List<FilmView> views(Collection<Film> list) {
        Map<Long, PlaybackState> byEdition = new HashMap<>();
        states.findAll().forEach(s -> byEdition.put(s.getEditionId(), s));
        return list.stream().map(f -> new FilmView(f, byEdition)).toList();
    }
}
