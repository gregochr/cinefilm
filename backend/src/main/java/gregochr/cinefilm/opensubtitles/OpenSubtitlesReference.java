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
import org.hibernate.annotations.DynamicUpdate;

/**
 * A subtitle downloaded from OpenSubtitles as a film's reference (whole-film timeline). Kept and reused: the same file
 * is never downloaded twice for a film. A reference with no cues is unusable and is kept so that file is never fetched
 * again.
 *
 * <p>Updates write only the columns that changed, because several writers touch a row under different locks (the
 * fetch, the cue-end backfill, the frame rate fill): one must not write another's column back to its old value.
 */
@Entity
@Table(name = "opensubtitles_reference")
@DynamicUpdate
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

    /** Where the last cue ends, in milliseconds; null for a file stored before it was kept, and for an unusable one. */
    private Long lastCueEndMs;

    /** The frame rate OpenSubtitles states the file was timed for; null when it states none. */
    private Double timedFps;

    /** The factor the stored cues were multiplied by to put them on the film's timeline; null = never retimed. */
    private Double retimedBy;

    /** The film rate {@link #retimedBy} came from; null until retimed. */
    private Double retimedForFps;

    /** The cues before they were retimed; null until then. */
    @Column(columnDefinition = "TEXT")
    private String originalCuesText;

    /** Downloads that were charged but gave no usable file; {@link #MAX_FAILED_DOWNLOADS} makes it final. */
    @Column(nullable = false)
    private int failedDownloads;

    @Column(nullable = false)
    private Instant fetchedAt;

    /** Charged failed downloads of one file id after which it is never fetched again. */
    public static final int MAX_FAILED_DOWNLOADS = 3;

    /** True when there is nothing more to try: it is usable, or its file id has failed the most times allowed. */
    public boolean isFinal() {
        return isUsable() || failedDownloads >= MAX_FAILED_DOWNLOADS;
    }

    /** True when the download had at least one cue, so it can be compared with. */
    public boolean isUsable() {
        return cueCount > 0;
    }
}
