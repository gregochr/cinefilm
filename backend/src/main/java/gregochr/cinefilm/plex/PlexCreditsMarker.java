package gregochr.cinefilm.plex;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Where Plex says the credits start inside one media file, copied once by the credits import. The credits apply pass
 * and the Admin End credits list read it ({@code CreditsApply}). A row whose fingerprint no longer matches the file
 * is ignored.
 */
@Entity
@Table(name = "plex_credits_marker")
@Getter
@Setter
@NoArgsConstructor
public class PlexCreditsMarker {

    @Id
    private Long mediaFileId;

    /** The file's size and modification time when imported (see {@code MediaFile#fingerprint}). */
    @Column(nullable = false, length = 100)
    private String fingerprint;

    /** Where the credits start inside the file, in milliseconds. */
    @Column(nullable = false)
    private long startMs;

    @Column(nullable = false)
    private Instant importedAt;
}
