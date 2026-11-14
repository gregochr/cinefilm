package gregochr.cinefilm.summary;

import gregochr.cinefilm.summary.AdminSummaryDtos.AdminSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin's summary: the figures of the rail and the To do strip. */
@RestController
public class AdminSummaryController {

    private final AdminSummaryService summaries;

    /** Creates the controller over the summary service. */
    public AdminSummaryController(AdminSummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/api/admin/summary")
    AdminSummary summary() {
        return summaries.summary();
    }
}
