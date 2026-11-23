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
 * One modem's state in one round of a journey. The key is given by the caller, so the row is new until loaded or
 * stored: {@code save} is one insert and a key stored twice is refused.
 */
@Entity
@Table(name = "journey_modem_sample")
@IdClass(JourneyModemSampleId.class)
@Getter
@Setter
@NoArgsConstructor
public class JourneyModemSample implements Persistable<JourneyModemSampleId> {

    @Id
    private Long journeyId;

    @Id
    private Integer roundNo;

    @Id
    private Integer modemIndex;

    @Column(length = 40)
    private String deviceState;

    @Column(length = 40)
    private String linkState;

    private Integer rssi;

    @Column(length = 40)
    private String technology;

    @Column(length = 10)
    private String operatorId;

    private Long cellId;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean stored;

    @Override
    public JourneyModemSampleId getId() {
        return new JourneyModemSampleId(journeyId, roundNo, modemIndex);
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
