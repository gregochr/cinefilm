package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Presence;
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
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A forced English {@code .srt} file found beside the films. The row hangs off the film <em>file</em> it belongs to
 * (not the edition), so it follows the file when editions are edited, joined or merged; no file means unmatched.
 * Rows are never deleted: a file that is not found is marked {@link Presence#MISSING}.
 */
@Entity
@Table(name = "subtitle_sidecar")
@Getter
@Setter
@NoArgsConstructor
public class SubtitleSidecar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Path relative to the library root, with forward slashes. */
    @Column(nullable = false, unique = true, length = 2000)
    private String path;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private Instant modifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Presence presence = Presence.PRESENT;

    /** The film file this belongs to; null when unmatched. */
    private Long mediaFileId;

    /** True when the file is timed for a whole split edition; it is then stored against part 1. */
    @Column(nullable = false)
    private boolean wholeEdition;

    /** True when the owner attached it by hand: a scan never re-matches it. */
    @Column(nullable = false)
    private boolean ownerAttached;

    /** The cues normalised to SRT. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String cuesText = "";

    /** How the file was read, in plain words. */
    @Column(nullable = false, length = 2000)
    private String parseReport = "";

    @Column(nullable = false)
    private int cueCount;

    /** The end of the last cue in milliseconds, without the offset. */
    @Column(nullable = false)
    private long lastEndMs;

    /** The owner's timing nudge in milliseconds, added to every cue. */
    @Column(nullable = false)
    private int offsetMs;

    /** When the owner said the lines are right and in sync; null when they have not. */
    private Instant ownerVerifiedAt;

    /** The comma-joined ids of the edition's part files when the owner verified. */
    @Column(length = 500)
    private String verifiedParts;

    @Column(nullable = false)
    private Instant updatedAt;

    /** The comma-joined ids of an edition's part files in play order: what {@link #verifiedParts} is compared with. */
    public static String partsKey(List<Long> partIds) {
        return partIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * True while the owner's check still counts: it was made, and the edition has the same part files now as then
     * (so an edit that joined, split or reordered the parts takes the check away without anything resetting it).
     */
    public boolean isVerifiedFor(List<Long> partIds) {
        return ownerVerifiedAt != null && partsKey(partIds).equals(verifiedParts);
    }

    /** Records that the owner has not checked the file (it changed, moved or was nudged). */
    public void clearVerification() {
        ownerVerifiedAt = null;
        verifiedParts = null;
    }
}
