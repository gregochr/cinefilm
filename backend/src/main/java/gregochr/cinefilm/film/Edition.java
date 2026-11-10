package gregochr.cinefilm.film;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Who grouped this edition's files; the scan only regroups {@link GroupingSource#AUTO} editions. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GroupingSource groupingSource = GroupingSource.AUTO;

    /**
     * True when the edition's original file is missing from disk or cannot be direct-played, so the edition must not
     * be offered. When the edition has several parts, any part with no duration also hides it (the parts cannot be
     * joined into one timeline without it); a single file's duration never does. An edition with no original file row
     * at all is not hidden here (it only has pre-made renditions).
     */
    public boolean hiddenFromBrowse() {
        List<MediaFile> parts = originalParts();
        boolean multiPart = parts.size() > 1;
        return parts.stream().anyMatch(p -> p.getPresence() != Presence.PRESENT
            || p.getPlayability() != Playability.PLAYABLE
            || multiPart && (p.getDurationMs() == null || p.getDurationMs() <= 0));
    }

    /** The original files in play order: by part number, then id. A single-file edition has one. */
    public List<MediaFile> originalParts() {
        return mediaFiles.stream().filter(f -> f.getKind() == MediaKind.ORIGINAL)
            .sorted(Comparator.comparingInt(MediaFile::getPartNumber)
                .thenComparing(MediaFile::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    /** How many original parts the edition has. */
    public int partCount() {
        return originalParts().size();
    }

    /**
     * The first original file (part 1), if the edition has one. Every reader that needs the whole film (streaming,
     * sessions, runtime) must handle {@link #originalParts()} once an edition can have several; today only the scan
     * and the timeline do.
     */
    public MediaFile originalFile() {
        List<MediaFile> parts = originalParts();
        return parts.isEmpty() ? null : parts.get(0);
    }

    /** Sets the runtime to the sum of the original parts' durations (an unknown duration counts as 0). */
    public void recomputeRuntime() {
        List<MediaFile> parts = originalParts();
        if (parts.isEmpty()) {
            return;
        }
        runtimeMs = parts.stream().mapToLong(p -> p.getDurationMs() == null ? 0 : p.getDurationMs()).sum();
    }
}
