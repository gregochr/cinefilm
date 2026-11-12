package gregochr.cinefilm.subtitle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The current result of fitting one forced subtitle file's cue times to the full English subtitle track inside the
 * film's own media file ({@link TimingFit}). One row per sidecar. {@link #sidecarIdentity} and {@link #referenceKey}
 * say exactly what was fitted to what; neither includes the sidecar's offset, so applying the fitted offset does not
 * make the row stale (the check is of the raw file against the track).
 */
@Entity
@Table(name = "forced_subtitle_alignment")
@Getter
@Setter
@NoArgsConstructor
public class ForcedSubtitleAlignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long sidecarId;

    /** The file as stored: see {@link TimingKeys#identity}. */
    @Column(nullable = false, length = 100)
    private String sidecarIdentity;

    /** What it was fitted to: see {@link TimingKeys#referenceKey}. */
    @Column(nullable = false, length = 1000)
    private String referenceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private TimingFit.Verdict verdict;

    @Column(nullable = false)
    private int fittedOffsetMs;

    @Column(nullable = false)
    private double matchedShare;

    @Column(nullable = false)
    private int matchedCount;

    @Column(nullable = false)
    private int servedCount;

    @Column(nullable = false)
    private int medianResidualMs;

    /** Plain sentences, joined by newlines; at most 1,000 characters. */
    @Column(nullable = false, length = 1000)
    private String reasons = "";

    /** True when the check set the sidecar's offset from this result. */
    @Column(nullable = false)
    private boolean applied;

    @Column(nullable = false)
    private Instant checkedAt;

    /**
     * True while the row still describes the file: the same stored file, fitted to the same reference.
     *
     * @param identity the sidecar's identity now
     * @param key the reference key now (null when it cannot be worked out)
     */
    public boolean isCurrent(String identity, String key) {
        return key != null && sidecarIdentity.equals(identity) && referenceKey.equals(key);
    }

    /** The reasons as a list (empty when there are none). */
    public List<String> reasonList() {
        return reasons.isEmpty() ? List.of() : List.of(reasons.split("\n"));
    }
}
