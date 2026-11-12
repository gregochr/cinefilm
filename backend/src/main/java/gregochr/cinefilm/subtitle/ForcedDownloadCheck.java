package gregochr.cinefilm.subtitle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The current result of comparing a downloaded subtitle with a <em>different</em> OpenSubtitles file (the second
 * opinion that proves it when its timing cannot be fitted to the film's own English track). One row per download,
 * removed with it.
 */
@Entity
@Table(name = "forced_download_check")
@Getter
@Setter
@NoArgsConstructor
public class ForcedDownloadCheck extends SubtitleCheckBase {

    @Column(nullable = false, unique = true)
    private Long sidecarId;
}
