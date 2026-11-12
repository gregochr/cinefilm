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

    /**
     * Who set {@link #offsetMs}: null when it was never set (it is 0), {@link OffsetSource#OWNER} once the owner has
     * put in an offset (a reset to 0 included), {@link OffsetSource#AUTO} when the timing check set it. An owner's
     * offset is never replaced automatically.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private OffsetSource offsetSource;

    /** When the owner said the lines are right and in sync; null when they have not. */
    private Instant ownerVerifiedAt;

    /** The comma-joined ids of the edition's part files when the owner verified. */
    @Column(length = 500)
    private String verifiedParts;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Who set a file's timing offset. */
    public enum OffsetSource {
        /** The owner put the offset in (0 included: "Reset timing"): the timing check never changes it. */
        OWNER,
        /** The timing check against the film's own English track set it. */
        AUTO
    }

    /**
     * Takes an automatically set offset away (back to 0, never set), because the file it was fitted for changed or
     * moved to another film file. An offset the owner set is left alone.
     */
    public void dropAutoOffset() {
        if (offsetSource == OffsetSource.AUTO) {
            offsetMs = 0;
            offsetSource = null;
        }
    }

    /**
     * Points the file at a film file, as a whole-edition file or not. The timing check's identity names the film file
     * and the whole flag, so an automatic offset (fitted for the old target) is dropped only when either changes; a
     * re-attach or re-match to the same place keeps it, because the check would never fit it again.
     *
     * @param fileId the film file, or null for none
     * @param whole true when the file is timed for a whole split edition
     */
    public void retarget(Long fileId, boolean whole) {
        if (!java.util.Objects.equals(mediaFileId, fileId) || wholeEdition != whole) {
            dropAutoOffset();
        }
        mediaFileId = fileId;
        wholeEdition = whole;
    }

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
