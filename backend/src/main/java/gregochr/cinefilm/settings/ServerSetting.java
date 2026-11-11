package gregochr.cinefilm.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The server's settings: a single row with id 1, one column per setting. */
@Entity
@Table(name = "server_setting")
@Getter
@Setter
@NoArgsConstructor
public class ServerSetting {

    /** The id of the only row. */
    public static final short ONLY_ROW = 1;

    @Id
    private Short id = ONLY_ROW;

    @Column(name = "continue_watching_min_minutes", nullable = false)
    private int continueWatchingMinMinutes = ServerSettingsService.DEFAULT_MIN_MINUTES;
}
