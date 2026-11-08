package gregochr.cinefilm.library;

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

/** One library scan: when it ran, why, and what it found. */
@Entity
@Table(name = "scan_run")
@Getter
@Setter
@NoArgsConstructor
public class ScanRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    /** What started it: startup, nightly or admin. */
    @Column(name = "trigger_source", nullable = false)
    private String trigger;

    private int filesSeen;

    private int newFiles;

    private int missingFiles;

    @Column(length = 500)
    private String error;
}
