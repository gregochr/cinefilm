package gregochr.cinefilm.journey;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * The link between a journey and a playback record (a viewing watched during it). The key is given by the caller, so
 * the row is new until loaded or stored: {@code save} is one insert and a pair stored twice is refused.
 */
@Entity
@Table(name = "journey_playback")
@IdClass(JourneyPlaybackId.class)
@Getter
@Setter
@NoArgsConstructor
public class JourneyPlayback implements Persistable<JourneyPlaybackId> {

    @Id
    private Long journeyId;

    @Id
    private Long playbackHealthId;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean stored;

    @Override
    public JourneyPlaybackId getId() {
        return new JourneyPlaybackId(journeyId, playbackHealthId);
    }

    @Override
    public boolean isNew() {
        return !stored;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        stored = true;
    }
}
