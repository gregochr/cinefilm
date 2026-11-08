package gregochr.cinefilm.film;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Remembered per-film choices: edition, quality, audio track and subtitles. */
@Entity
@Table(name = "film_preference")
@Getter
@Setter
@NoArgsConstructor
public class FilmPreference {

    @Id
    private Long filmId;

    private Long defaultEditionId;

    private Long lastEditionId;

    private String quality;

    private Long audioStreamId;

    private String subtitle;

    /** Creates empty preferences for a film. */
    public FilmPreference(Long filmId) {
        this.filmId = filmId;
    }
}
