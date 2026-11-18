package gregochr.cinefilm.film;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
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
     * Replaces the film's keywords and stamps it. The guarded stamp comes first and takes the row lock, so a film
     * that has gone, or been rematched to another TMDB film while TMDB was asked (or before the lock is released),
     * is left alone.
     *
     * @param id the film
     * @param tmdbId the TMDB id the keywords were fetched for
     * @param keywords the keywords, already lower case and trimmed
     * @param at when TMDB was asked
     * @return true when saved; false when the film has gone or has another TMDB id now
     */
    @Transactional
    public boolean save(Long id, Long tmdbId, Collection<String> keywords, Instant at) {
        if (films.stampKeywords(id, tmdbId, at) == 0) {
            return false;
        }
        films.deleteKeywordRows(id);
        Set<String> cut = new LinkedHashSet<>();
        for (String keyword : keywords) {
            cut.add(cut(keyword));
        }
        for (String keyword : cut) {
            films.insertKeywordRow(id, keyword);
        }
        return true;
    }

    /** At most {@link #MAX_LENGTH} characters, never splitting a surrogate pair. */
    private static String cut(String keyword) {
        if (keyword.length() <= MAX_LENGTH) {
            return keyword;
        }
        int end = Character.isHighSurrogate(keyword.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return keyword.substring(0, end);
    }
}
