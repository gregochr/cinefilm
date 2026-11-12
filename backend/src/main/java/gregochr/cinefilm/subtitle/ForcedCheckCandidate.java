package gregochr.cinefilm.subtitle;

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

/**
 * One OpenSubtitles file an edition has been compared with, for one IMDb id. The rows are the ledger of the cap of
 * {@link ReferenceWalk#MAX_CANDIDATES} candidates per edition and IMDb id, ever: once there are that many, no other
 * file is tried for that edition and IMDb id.
 */
@Entity
@Table(name = "forced_check_candidate")
@Getter
@Setter
@NoArgsConstructor
public class ForcedCheckCandidate {

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
    private Instant triedAt;
}
