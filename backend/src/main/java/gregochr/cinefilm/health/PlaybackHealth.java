package gregochr.cinefilm.health;

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
 * The stored health report of one viewing: when it began and was last reported, the film it was of (copied at the
 * first report, there is no foreign key) and the report itself as the server's own JSON serialisation.
 */
@Entity
@Table(name = "playback_health")
@Getter
@Setter
@NoArgsConstructor
public class PlaybackHealth {

    /** Longest text the {@code summary} column holds. */
    public static final int MAX_SUMMARY_LENGTH = 16_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String playbackId;

    @Column(nullable = false)
    private Long editionId;

    @Column(nullable = false)
    private Long filmId;

    @Column(nullable = false)
    private String filmTitle;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = MAX_SUMMARY_LENGTH)
    private String summary;

    /** The figures below are copied from the report by {@link HealthFigures}; see V26. */
    @Column(nullable = false)
    private long watchedMs;

    @Column(nullable = false)
    private long stallCount;

    @Column(nullable = false)
    private long stallMs;

    private Long startupMs;

    @Column(nullable = false)
    private boolean hasProblem;

    @Column(length = 16)
    private String environment;

    /** Copies the queryable figures from a report's {@link HealthFigures}. */
    public void setFigures(HealthFigures figures) {
        watchedMs = figures.watchedMs();
        stallCount = figures.stallCount();
        stallMs = figures.stallMs();
        startupMs = figures.startupMs();
        hasProblem = figures.hasProblem();
        environment = figures.environment();
    }
}
