package gregochr.cinefilm.film;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The owner's choice for one film (by TMDB id) in Christmas week: one row per film, so never both. */
@Entity
@Table(name = "christmas_override")
@Getter
@Setter
@NoArgsConstructor
public class ChristmasOverride {

    @Id
    @Column(name = "tmdb_id")
    private Long tmdbId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ChristmasChoice choice;

    @Column(nullable = false)
    private Instant createdAt;

    /** Creates an override. */
    public ChristmasOverride(Long tmdbId, ChristmasChoice choice, Instant createdAt) {
        this.tmdbId = tmdbId;
        this.choice = choice;
        this.createdAt = createdAt;
    }
}
