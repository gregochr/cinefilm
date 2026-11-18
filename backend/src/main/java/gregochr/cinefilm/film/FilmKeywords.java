package gregochr.cinefilm.film;

import java.time.Instant;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saves a film's TMDB keywords and stamp in one transaction, only while the film still has the TMDB id asked about. */
@Service
public class FilmKeywords {

    static final int MAX_LENGTH = 100;

    private final FilmRepository films;

    /** Creates the service. */
    public FilmKeywords(FilmRepository films) {
        this.films = films;
    }

    /**
     * Replaces the film's keywords and stamps it. A film that has gone, or been rematched to another TMDB film while
     * TMDB was asked, is left alone.
     *
     * @param id the film
     * @param tmdbId the TMDB id the keywords were fetched for
     * @param keywords the keywords, already lower case and trimmed
     * @param at when TMDB was asked
     * @return true when saved; false when the film has gone or has another TMDB id now
     */
    @Transactional
    public boolean save(Long id, Long tmdbId, Collection<String> keywords, Instant at) {
        Film film = films.findById(id).orElse(null);
        if (film == null || !tmdbId.equals(film.getTmdbId())) {
            return false;
        }
        film.getKeywords().clear();
        for (String keyword : keywords) {
            film.getKeywords().add(keyword.length() > MAX_LENGTH ? keyword.substring(0, MAX_LENGTH) : keyword);
        }
        film.setKeywordsFetchedAt(at);
        return true;
    }
}
