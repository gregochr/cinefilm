package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * The current result of comparing the forced subtitle an edition serves with an OpenSubtitles reference. One row per
 * edition; {@link #servedVersion} says exactly what was compared, so any change to what is served makes it stale.
 */
@Entity
@Table(name = "forced_subtitle_check")
@Getter
@Setter
@NoArgsConstructor
public class ForcedSubtitleCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long editionId;

    /** What was compared: see {@link ServedSubtitles#version}. */
    @Column(nullable = false, length = 1000)
    private String servedVersion;

    @Column(nullable = false)
    private Long referenceId;

    /** {@code AGREES} or {@code CHECK}. */
    @Column(nullable = false, length = 10)
    private String verdict;

    @Column(nullable = false)
    private double matchedShare;

    @Column(nullable = false)
    private int servedCount;

    @Column(nullable = false)
    private int referenceCount;

    @Column(nullable = false)
    private int bestOffsetMs;

    /** Plain sentences, joined by newlines; at most 1,000 characters. */
    @Column(nullable = false, length = 1000)
    private String reasons = "";

    @Column(nullable = false)
    private Instant checkedAt;

    /**
     * The file id of the film's best OpenSubtitles result when the check was made; null for a check made by phase 2d,
     * which always compared with the best file (its reference's file id).
     */
    private Long bestFileId;

    /**
     * The cut tags of the edition's name when the check was made ({@code CutTag.key}); they order the candidates, so a
     * rename that changes them makes the check stale. Empty when the name has none.
     */
    @Column(nullable = false, length = 100)
    private String cutTags = "";

    /** The version of the comparison rules that made this row; an older one is stale ({@link #RULES_VERSION}). */
    @Column(nullable = false)
    private int rulesVersion = RULES_VERSION;

    /**
     * The current version of the comparison rules: 1 was phase 2d (one reference, the film's best); 2 is phase 2e
     * (full references judged by timing, the next candidates tried, cut-aware wording).
     */
    public static final int RULES_VERSION = 2;

    /**
     * True while the check still describes what is served: it was made by the current rules, for the same served
     * version, against a reference that was fetched for the film's current IMDb id, and the film's best OpenSubtitles
     * file is the one it was when the check was made (a search that ranks another file first makes the check stale).
     * The reference itself may be any of the film's candidates, not only the best result.
     *
     * @param version the edition's served version now
     * @param filmImdbId the film's IMDb id now
     * @param reference this check's reference
     * @param bestFileId the file id of the film's best OpenSubtitles result now
     */
    public boolean isCurrent(String version, String filmImdbId, OpenSubtitlesReference reference, long bestFileId) {
        return rulesVersion == RULES_VERSION && servedVersion.equals(version) && reference != null
            && reference.getId().equals(referenceId) && reference.getImdbId().equals(filmImdbId)
            && (this.bestFileId != null ? this.bestFileId : reference.getOsFileId()) == bestFileId;
    }

    /** The reasons as a list (empty when there are none). */
    public List<String> reasonList() {
        return reasons.isEmpty() ? List.of() : List.of(reasons.split("\n"));
    }
}
