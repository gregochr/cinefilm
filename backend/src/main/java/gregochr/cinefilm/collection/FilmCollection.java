package gregochr.cinefilm.collection;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A TMDB collection such as "Alien". Parts include films not owned. */
@Entity
@Table(name = "film_collection")
@Getter
@Setter
@NoArgsConstructor
public class FilmCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tmdbId;

    @Column(nullable = false)
    private String name;

    private String posterPath;

    /** False while the parts could not be fetched from TMDB; later scans retry. */
    @Column(nullable = false)
    private boolean partsComplete = true;

    @OneToMany(mappedBy = "collection", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("releaseOrder")
    private List<CollectionPart> parts = new ArrayList<>();
}
