package gregochr.cinefilm.opensubtitles;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A subtitle downloaded from OpenSubtitles as a film's reference (whole-film timeline). Kept and reused: the same file
 * is never downloaded twice for a film. A reference with no cues is unusable and is kept so that file is never fetched
 * again.
 */
@Entity
@Table(name = "opensubtitles_reference")
@Getter
@Setter
@NoArgsConstructor
public class OpenSubtitlesReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long filmId;

    /** The IMDb id it was fetched for. */
    @Column(nullable = false, length = 16)
    private String imdbId;

    /** The file id {@code /download} took. */
    @Column(nullable = false)
    private long osFileId;

    @Column(length = 200)
    private String release;

    /** The cues normalised to SRT; empty when unusable. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String cuesText = "";

    @Column(nullable = false)
    private int cueCount;

    @Column(nullable = false)
    private Instant fetchedAt;

    /** True when the download had at least one cue, so it can be compared with. */
    public boolean isUsable() {
        return cueCount > 0;
    }
}
