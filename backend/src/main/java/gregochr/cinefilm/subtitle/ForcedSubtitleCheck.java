package gregochr.cinefilm.subtitle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The current result of comparing the owner's forced subtitle file(s) of an edition with an OpenSubtitles reference.
 * One row per edition; the served version says exactly what was compared (the files' own pick, not what playback
 * finally serves), so any change to the files makes it stale. A downloaded subtitle's check is a
 * {@link ForcedDownloadCheck}.
 */
@Entity
@Table(name = "forced_subtitle_check")
@Getter
@Setter
@NoArgsConstructor
public class ForcedSubtitleCheck extends SubtitleCheckBase {

    @Column(nullable = false, unique = true)
    private Long editionId;
}
