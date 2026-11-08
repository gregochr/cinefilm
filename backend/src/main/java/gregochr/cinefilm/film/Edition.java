package gregochr.cinefilm.film;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One cut of a film ("The Final Cut"), with its own runtime, files and watch state. */
@Entity
@Table(name = "edition")
@Getter
@Setter
@NoArgsConstructor
public class Edition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "film_id")
    private Film film;

    @Column(nullable = false)
    private String name;

    @Column(name = "release_year")
    private Integer year;

    @Column(nullable = false)
    private long runtimeMs;

    private String note;

    /** Where the end credits start; null when unknown (then 92% of runtime applies). */
    private Long creditsMarkerMs;

    @OneToMany(mappedBy = "edition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<MediaFile> mediaFiles = new ArrayList<>();

    /** The original (direct-play) file, if the edition has one. */
    public MediaFile originalFile() {
        return mediaFiles.stream().filter(f -> f.getKind() == MediaKind.ORIGINAL).findFirst().orElse(null);
    }
}
