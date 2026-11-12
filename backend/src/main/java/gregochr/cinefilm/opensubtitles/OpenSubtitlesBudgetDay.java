package gregochr.cinefilm.opensubtitles;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The OpenSubtitles downloads spent on one UTC day, and when OpenSubtitles said the allowance comes back. */
@Entity
@Table(name = "opensubtitles_budget")
@Getter
@Setter
@NoArgsConstructor
public class OpenSubtitlesBudgetDay {

    @Id
    private LocalDate budgetDay;

    private int used;

    /** Nothing may be downloaded before this instant (OpenSubtitles said the limit is reached); null when free. */
    private Instant blockedUntil;

    /** Creates a day with nothing spent. */
    public OpenSubtitlesBudgetDay(LocalDate day) {
        this.budgetDay = day;
    }
}
