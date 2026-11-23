package gregochr.cinefilm.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * One round of the logger on a journey: the train's feed and the laptop's state at a moment; a round that failed
 * holds only its number and time. The key is given by the caller, so the row is new until loaded or stored:
 * {@code save} is one insert and a key stored twice is refused.
 */
@Entity
@Table(name = "journey_sample")
@IdClass(JourneySampleId.class)
@Getter
@Setter
@NoArgsConstructor
public class JourneySample implements Persistable<JourneySampleId> {

    @Id
    private Long journeyId;

    @Id
    private Integer roundNo;

    @Column(nullable = false)
    private Instant sampledAt;

    private Instant feedAt;

    private Integer fixMode;

    @Column(name = "fix_age_s")
    private Integer fixAgeS;

    private Double latitude;

    private Double longitude;

    @Column(name = "altitude_m")
    private Double altitudeM;

    private Double speedMps;

    private Double courseDeg;

    private Integer satellites;

    private Integer online;

    private Integer usersOnline;

    private Integer usersTotal;

    private Long downLimit;

    private Long upLimit;

    private Integer capLevel;

    private Integer userClass;

    private Long dataDownUsed;

    private Long dataUpUsed;

    private Integer probeMs;

    private Integer probeCode;

    @Column(length = 10)
    private String powerSource;

    private Integer batteryPercent;

    private Integer lowPower;

    private Integer cpuLimit;

    private Double loadAvg;

    private Double swapMb;

    private Integer wifiRssi;

    private Integer wifiNoise;

    private Double wifiRate;

    private Integer wifiPhy;

    private Integer wifiChannel;

    private Integer wifiBand;

    private Integer wifiWidth;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean stored;

    @Override
    public JourneySampleId getId() {
        return new JourneySampleId(journeyId, roundNo);
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
