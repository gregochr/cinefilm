package gregochr.cinefilm.subtitle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What was read from the full English subtitle track inside one media file: the cue start times, or the reason there
 * is no usable track. One row per media file; {@link #fingerprint} is the file's size and modification time when it
 * was read, so a changed file is read again and an unchanged one never is (reading demuxes the whole file, minutes
 * over SMB).
 */
@Entity
@Table(name = "embedded_cue_starts")
@Getter
@Setter
@NoArgsConstructor
public class EmbeddedCueStarts {

    /** What the read found. */
    public enum Status {
        /** A full English track was found; {@link #getStartsMs()} holds its cue starts. */
        USABLE,
        /** There is no full English track to use; {@link #getReason()} says why. Kept so the file is not read again. */
        NO_REFERENCE
    }

    @Id
    private Long mediaFileId;

    /** The file's size and modification time when read: see {@link TimingKeys#fingerprint}. */
    @Column(nullable = false, length = 100)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** ffprobe's index of the chosen stream; null when there is none. */
    private Integer streamIndex;

    /** The chosen stream's codec name; null when there is none. */
    @Column(length = 40)
    private String codec;

    /** How many subtitle streams the file has. */
    @Column(nullable = false)
    private int subtitleStreamCount;

    @Column(nullable = false)
    private int cueCount;

    /** The cue starts as gaps in milliseconds, comma-separated: see {@link CueStartsCodec}. Empty when none. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String startsText = "";

    /** Why there is no usable track, in plain words; null when there is one. */
    @Column(length = 300)
    private String reason;

    @Column(nullable = false)
    private Instant readAt;

    /** The cue starts in milliseconds, ascending. */
    public List<Long> getStartsMs() {
        return CueStartsCodec.decode(startsText);
    }

    /** Sets the cue starts (ascending) and their count. */
    public void setStartsMs(List<Long> starts) {
        startsText = CueStartsCodec.encode(starts);
        cueCount = starts.size();
    }
}
