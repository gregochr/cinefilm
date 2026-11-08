package gregochr.cinefilm.film;

import gregochr.cinefilm.collection.FilmCollection;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A film in the library. Playable media hangs off its editions. */
@Entity
@Table(name = "film")
@Getter
@Setter
@NoArgsConstructor
public class Film {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tmdbId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String sortTitle;

    @Column(name = "release_year", nullable = false)
    private int year;

    private String overview;

    private String posterPath;

    private String backdropPath;

    @Column(nullable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id")
    private FilmCollection collection;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "film_genre", joinColumns = @JoinColumn(name = "film_id"))
    @Column(name = "genre")
    private Set<String> genres = new LinkedHashSet<>();

    @OneToMany(mappedBy = "film", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Edition> editions = new ArrayList<>();

    @OneToMany(mappedBy = "film", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence")
    private List<Credit> credits = new ArrayList<>();

    /** Sets the title and derives {@link #sortTitle} from it. */
    public void setTitleAndSort(String newTitle) {
        this.title = newTitle;
        this.sortTitle = SortTitles.of(newTitle);
    }
}
