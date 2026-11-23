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
 * One timing point of a {@link TrainRun}: the place and its planned and actual arrival, departure and pass times (a
 * point has the ones that apply). The key is given by the caller, so the row is new until loaded or stored:
 * {@code save} is one insert and a key stored twice is refused.
 */
@Entity
@Table(name = "train_run_call")
@IdClass(TrainRunCallId.class)
@Getter
@Setter
@NoArgsConstructor
public class TrainRunCall implements Persistable<TrainRunCallId> {

    @Id
    private Long trainRunId;

    @Id
    private Integer seq;

    @Column(nullable = false, length = 100)
    private String location;

    private Instant plannedArrival;

    private Instant plannedDeparture;

    private Instant plannedPass;

    private Instant actualArrival;

    private Instant actualDeparture;

    private Instant actualPass;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean stored;

    @Override
    public TrainRunCallId getId() {
        return new TrainRunCallId(trainRunId, seq);
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
