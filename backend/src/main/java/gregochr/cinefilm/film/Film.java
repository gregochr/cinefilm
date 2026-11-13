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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A film in the library. Playable media hangs off its editions. Updates write only the changed columns, so a ratings
 * refresh and a scan that load the same film cannot overwrite each other's fields.
 */
@Entity
@Table(name = "film")
@DynamicUpdate
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

    /** True once the owner settled this film's files (Fix match, Edit editions): the scan never regroups it. */
    @Column(nullable = false)
    private boolean editionsConfirmed;

    /** The IMDb id (for example {@code tt0113277}), from TMDB; null when TMDB has none or has not been asked. */
    @Column(length = 16)
    private String imdbId;

    /** The IMDb score out of 10, one decimal; null when IMDb has none. */
    @Column(precision = 3, scale = 1)
    private BigDecimal imdbRating;

    /** How many votes the IMDb score rests on; null when unknown. */
    private Integer imdbVotes;

    /** The Rotten Tomatoes percentage, 0 to 100; null when there is none. */
    private Integer rtScore;

    /** The film's Rotten Tomatoes page ({@code http} or {@code https}); null when there is none. */
    @Column(length = 512)
    private String rtUrl;

    /** When OMDb was last asked about this film (a film with nothing to show is stamped too); null = never. */
    private Instant ratingsFetchedAt;

    /** TMDB's original-language code (lower case, for example {@code en}); null when unknown or not asked. */
    @Column(length = 8)
    private String originalLanguage;

    /** TMDB's spoken-language codes, comma-separated ({@link SpokenLanguages}); empty = none listed, null = unknown. */
    @Column(length = SpokenLanguages.MAX_LENGTH)
    private String spokenLanguages;

    /** When TMDB was last asked for the languages (a film TMDB no longer has is stamped too); null = not yet. */
    private Instant languagesFetchedAt;

    /** The owner's choice on forced subtitles; null = decided automatically. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ForcedNeedChoice forcedNeedOwner;

    /** The owner's choice on full English subtitles; null = decided automatically. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FullNeedChoice fullNeedOwner;

    /** How many English foreign-parts-only subtitles OpenSubtitles had at the last search; null = never searched. */
    private Integer osForcedCount;

    /** When OpenSubtitles was last searched for this film; null = never. */
    private Instant osSearchedAt;

    /** The IMDb id the OpenSubtitles result belongs to: the result counts only while it equals {@link #imdbId}. */
    @Column(length = 16)
    private String osSearchedImdbId;

    /** JSON of the best OpenSubtitles result ({@code OpenSubtitlesResult}); null when there is none. */
    @Column(length = 2000)
    private String osBest;

    /**
     * JSON array of the single-file OpenSubtitles results the check may compare with, in ranked order
     * ({@code OpenSubtitlesResult.listToJson}); {@code []} = none usable; null = stored before it was kept.
     */
    @Column(columnDefinition = "TEXT")
    private String osCandidates;

    /**
     * Sets the IMDb id. A different id means a different film, so what OpenSubtitles said about the old one is
     * forgotten in the entity (the explicit row write is {@link FilmRepository#clearOpenSubtitles}).
     */
    public void setImdbId(String newImdbId) {
        if (!java.util.Objects.equals(imdbId, newImdbId)) {
            osForcedCount = null;
            osSearchedAt = null;
            osSearchedImdbId = null;
            osBest = null;
            osCandidates = null;
        }
        this.imdbId = newImdbId;
    }

    /**
     * Sets the TMDB id. A film stamped as "no ratings" only because it had no identity at all (no TMDB id, no IMDb id)
     * is un-stamped when it gains a different TMDB id, so the next refresh retries it instead of waiting out the
     * stamp. A film that already has an IMDb id keeps its ratings: they are keyed by that id.
     */
    public void setTmdbId(Long newTmdbId) {
        if (newTmdbId != null && !newTmdbId.equals(tmdbId) && imdbId == null) {
            ratingsFetchedAt = null;
            imdbRating = null;
            imdbVotes = null;
            rtScore = null;
            rtUrl = null;
        }
        if (!java.util.Objects.equals(tmdbId, newTmdbId)) {
            // The languages belong to the old TMDB film: forget them so the backfill asks again for the new one, and
            // the need is never worked out from another film's languages. applyMovie sets the id first, then these.
            originalLanguage = null;
            spokenLanguages = null;
            languagesFetchedAt = null;
        }
        this.tmdbId = newTmdbId;
    }

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
