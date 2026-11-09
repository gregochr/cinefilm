package gregochr.cinefilm.transcode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The stored keyframe index of one media file: the segment start times (see {@link CutList}), the largest gap, and
 * the size and modification time the file had when it was indexed, so a changed file is re-indexed.
 */
@Entity
@Table(name = "media_keyframes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MediaKeyframes {

    /** Longest text the {@code cuts} column holds. */
    public static final int MAX_CUTS_LENGTH = 32_000;

    @Id
    private Long mediaFileId;

    @Column(nullable = false, length = MAX_CUTS_LENGTH)
    private String cuts;

    @Column(nullable = false)
    private long maxGapMs;

    @Column(nullable = false)
    private boolean copyOk;

    @Column(nullable = false)
    private long sourceSize;

    @Column(nullable = false)
    private long sourceMtime;

    @Column(nullable = false)
    private Instant indexedAt;

    /** Average frames per second of the video track; null when only ffprobe could index the file. */
    private Double fps;
}
