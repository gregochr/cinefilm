package gregochr.cinefilm.playback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Resume position and watched flag of one edition. */
@Entity
@Table(name = "playback_state")
@Getter
@Setter
@NoArgsConstructor
public class PlaybackState {

    @Id
    private Long editionId;

    @Column(nullable = false)
    private long positionMs;

    @Column(nullable = false)
    private boolean watched;

    private Instant watchedAt;

    private Instant lastPlayedAt;

    public PlaybackState(Long editionId) {
        this.editionId = editionId;
    }
}
