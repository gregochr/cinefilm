package gregochr.cinefilm.film;

import gregochr.cinefilm.collection.FilmCollection;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    private static final int SORT_TITLE_MAX = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tmdbId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String sortTitle;

    /** The release year; null when the file gave none and TMDB has not been consulted. */
    @Column(name = "release_year")
    private Integer year;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus matchStatus = MatchStatus.MATCHED;

    /** JSON list of the top TMDB candidates while {@link #matchStatus} is NEEDS_MATCH; otherwise null. */
    @Column(length = 8000)
    private String matchCandidates;

    /** A film shows in browse unless every one of its editions is missing from disk or cannot be played. */
    public boolean browsable() {
        return editions.isEmpty() || editions.stream().anyMatch(e -> !e.hiddenFromBrowse());
    }

    /**
     * Sets the title and a sort title that someone else chose (Plex's {@code titleSort}); a blank one falls back to the
     * derived key. The key is lower-cased like every other sort title.
     */
    public void setTitleAndSort(String newTitle, String explicitSort) {
        this.title = newTitle;
        this.sortTitle = explicitSort == null || explicitSort.isBlank()
            ? SortTitles.of(newTitle)
            : bound(explicitSort.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** The sort key is a VARCHAR(255): a longer one from outside would fail the flush and roll the import back. */
    private static String bound(String key) {
        return key.substring(0, Math.min(key.length(), SORT_TITLE_MAX));
    }

    /** Sets the title and derives {@link #sortTitle} from it. */
    public void setTitleAndSort(String newTitle) {
        this.title = newTitle;
        this.sortTitle = SortTitles.of(newTitle);
    }
}
