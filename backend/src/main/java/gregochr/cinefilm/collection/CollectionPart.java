package gregochr.cinefilm.collection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One film of a collection, owned or not. Owned parts are matched to films by tmdbId. */
@Entity
@Table(name = "collection_part")
@Getter
@Setter
@NoArgsConstructor
public class CollectionPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id")
    private FilmCollection collection;

    @Column(nullable = false)
    private Long tmdbId;

    @Column(nullable = false)
    private String title;

    @Column(name = "release_year")
    private Integer year;

    @Column(nullable = false)
    private int releaseOrder;

    private String posterPath;
}
