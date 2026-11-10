package gregochr.cinefilm.ratings;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The OMDb requests spent on one UTC day. */
@Entity
@Table(name = "ratings_budget")
@Getter
@Setter
@NoArgsConstructor
public class RatingsBudgetDay {

    @Id
    private LocalDate budgetDay;

    private int used;

    /** Creates a day with nothing spent. */
    public RatingsBudgetDay(LocalDate day) {
        this.budgetDay = day;
    }
}
