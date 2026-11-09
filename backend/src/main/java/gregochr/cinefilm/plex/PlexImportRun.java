package gregochr.cinefilm.plex;

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

/** One Plex import: when it ran, the report it produced (JSON), and the error that stopped it, if any. */
@Entity
@Table(name = "plex_import_run")
@Getter
@Setter
@NoArgsConstructor
public class PlexImportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    /** The {@link PlexDtos.PlexImportReport} as JSON. */
    @Column(columnDefinition = "TEXT")
    private String report;

    @Column(length = 500)
    private String error;
}
