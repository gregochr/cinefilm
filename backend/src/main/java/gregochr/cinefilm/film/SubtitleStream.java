package gregochr.cinefilm.film;

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

/** A subtitle track of a media file. */
@Entity
@Table(name = "subtitle_stream")
@Getter
@Setter
@NoArgsConstructor
public class SubtitleStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_file_id")
    private MediaFile mediaFile;

    @Column(name = "idx", nullable = false)
    private int index;

    private String language;

    @Column(nullable = false)
    private boolean forced;

    private String title;

    private String format;

    /**
     * True when a stored stream language is English or not stated. The scan stores a language as its English name
     * ({@code English}, from {@code eng}), a code it does not know as it came, and nothing for an undetermined one, so
     * this accepts the name and the raw codes, in any case. The forced-track queries in {@link MediaFileRepository}
     * spell the same list in JPQL.
     *
     * @param language the stored language, or null
     * @return true for null, {@code English}, {@code eng}, {@code en} and {@code und}
     */
    public static boolean isEnglishOrUnlabelled(String language) {
        if (language == null) {
            return true;
        }
        String lower = language.toLowerCase(java.util.Locale.ROOT);
        return "english".equals(lower) || "eng".equals(lower) || "en".equals(lower) || "und".equals(lower);
    }
}
