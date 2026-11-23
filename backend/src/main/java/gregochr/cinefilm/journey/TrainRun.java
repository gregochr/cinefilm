package gregochr.cinefilm.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One train service on one date, as the timetable names it, stored once however many journeys were on it. */
@Entity
@Table(name = "train_run")
@Getter
@Setter
@NoArgsConstructor
public class TrainRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String timetableUid;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false, length = 10)
    private String headcode;

    @Column(length = 100)
    private String operator;

    @Column(length = 200)
    private String source;

    @Column(nullable = false)
    private Instant importedAt;
}
