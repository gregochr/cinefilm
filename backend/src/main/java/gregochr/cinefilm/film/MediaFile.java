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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A file on disk holding one rendition of an edition. The path is the only thing the stream endpoint serves. */
@Entity
@Table(name = "media_file")
@Getter
@Setter
@NoArgsConstructor
public class MediaFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "edition_id")
    private Edition edition;

    @Column(nullable = false, length = 2000)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaKind kind = MediaKind.ORIGINAL;

    private String container;

    private String videoCodec;

    private Integer width;

    private Integer height;

    private Long bitrate;

    private Long sizeBytes;

    private Long durationMs;

    @Column(nullable = false)
    private boolean spriteReady;

    @Column(nullable = false)
    private Instant addedAt;

    @OneToMany(mappedBy = "mediaFile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<AudioStream> audioStreams = new ArrayList<>();

    @OneToMany(mappedBy = "mediaFile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SubtitleStream> subtitleStreams = new ArrayList<>();
}
