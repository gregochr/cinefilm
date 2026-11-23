package gregochr.cinefilm.journey;

import jakarta.persistence.Column;
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
 * A modem the train's router reported during a journey. The key is given by the caller, so the row is new until
 * loaded or stored: {@code save} is one insert and a key stored twice is refused.
 */
@Entity
@Table(name = "journey_modem")
@IdClass(JourneyModemId.class)
@Getter
@Setter
@NoArgsConstructor
public class JourneyModem implements Persistable<JourneyModemId> {

    @Id
    private Long journeyId;

    @Id
    private Integer modemIndex;

    @Column(length = 40)
    private String deviceType;

    @Column(length = 40)
    private String deviceSubtype;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean stored;

    @Override
    public JourneyModemId getId() {
        return new JourneyModemId(journeyId, modemIndex);
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
