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

/** An audio track of a media file. */
@Entity
@Table(name = "audio_stream")
@Getter
@Setter
@NoArgsConstructor
public class AudioStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_file_id")
    private MediaFile mediaFile;

    @Column(name = "idx", nullable = false)
    private int index;

    private String language;

    private String channels;

    private String codec;

    private String title;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false)
    private boolean safariPlayable = true;
}
