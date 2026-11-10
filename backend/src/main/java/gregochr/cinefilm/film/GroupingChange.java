package gregochr.cinefilm.film;

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

/** A line in the grouping report: something the scan, the Plex import or the owner split, joined or flagged. */
@Entity
@Table(name = "grouping_change")
@Getter
@Setter
@NoArgsConstructor
public class GroupingChange {

    /** The kind of a split. */
    public static final String SPLIT = "split";
    /** The kind of a join. */
    public static final String JOIN = "join";
    /** The kind of a flag (a suspicion left for the owner, nothing changed). */
    public static final String FLAG = "flag";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant happenedAt;

    /** One of {@link #SPLIT}, {@link #JOIN} or {@link #FLAG}. */
    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false, length = 500)
    private String summary;

    /** Creates a report line. */
    public GroupingChange(Instant happenedAt, String kind, String summary) {
        this.happenedAt = happenedAt;
        this.kind = kind;
        this.summary = summary;
    }
}
