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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An OpenSubtitles file the owner removed from an edition: it is never downloaded or promoted for that edition and
 * IMDb id again (a removal that the next run could undo would be pointless).
 */
@Entity
@Table(name = "forced_download_block")
@Getter
@Setter
@NoArgsConstructor
public class ForcedDownloadBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long editionId;

    @Column(nullable = false, length = 16)
    private String imdbId;

    @Column(nullable = false)
    private long osFileId;

    @Column(nullable = false)
    private Instant removedAt;

    /** Which kind of subtitle the file was removed as; {@code FORCED} unless a caller says otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SubtitleKind kind = SubtitleKind.FORCED;
}
