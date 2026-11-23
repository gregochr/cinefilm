package gregochr.cinefilm.journey;

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

/** One stretch (segment) of one logger run on one train unit, with the laptop that logged it. */
@Entity
@Table(name = "journey")
@Getter
@Setter
@NoArgsConstructor
public class Journey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long runStartedMs;

    @Column(nullable = false)
    private int segment;

    private Long deviceId;

    private Long trainRunId;

    @Column(length = 40)
    private String osVersion;

    @Column(length = 10)
    private String travelClass;

    @Column(length = 100)
    private String trainUnit;

    @Column(length = 200)
    private String sourceFile;

    @Column(nullable = false)
    private int loggerVersion;

    @Column(nullable = false)
    private Instant importedAt;
}
