package gregochr.cinefilm.credits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the analysis found for one media file (the last original part of an edition): one row per file. Stored for
 * checking only; nothing applies it yet.
 */
@Entity
@Table(name = "credits_detection")
@Getter
@Setter
@NoArgsConstructor
public class CreditsDetection {

    /** The most characters of reasons stored. */
    public static final int MAX_REASONS = 2000;

    @Id
    private Long mediaFileId;

    /** The file's size and modification time when analysed (see {@code MediaFile#fingerprint}). */
    @Column(nullable = false, length = 100)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditsVerdict verdict;

    /** Where the credits start inside the file, in milliseconds; null when none was found. */
    private Long offsetMs;

    /** Plain sentences, one per line. */
    @Column(nullable = false, length = MAX_REASONS)
    private String reasons;

    @Column(nullable = false)
    private Instant checkedAt;
}
