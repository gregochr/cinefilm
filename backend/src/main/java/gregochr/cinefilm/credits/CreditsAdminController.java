package gregochr.cinefilm.credits;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsList;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsRow;
import gregochr.cinefilm.credits.CreditsAdminDtos.MarkerUpdate;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Admin End credits routes. */
@RestController
public class CreditsAdminController {

    private static final int DEFAULT_SIZE = 25;

    private final CreditsAdminService credits;

    /** Creates the controller over the credits service. */
    public CreditsAdminController(CreditsAdminService credits) {
        this.credits = credits;
    }

    /**
     * One page of editions with their credits markers. {@code filter} is {@code none} (the default), {@code plex},
     * {@code owner} or {@code all}; {@code page} is 0-based (default 0); {@code size} is 1 to
     * {@link CreditsAdminService#MAX_SIZE} (default 25). A parameter that is present must be valid: anything
     * else, or a page whose first row would sit past {@link Integer#MAX_VALUE}, is a 400 {@code Invalid request}.
     */
    @GetMapping("/api/admin/credits")
    CreditsList list(@RequestParam Map<String, String> params) {
        CreditsFilter filter = params.containsKey("filter") ? CreditsFilter.parse(params.get("filter"))
            : CreditsFilter.NONE;
        int page = number(params, "page", 0, 0, Integer.MAX_VALUE);
        int size = number(params, "size", DEFAULT_SIZE, 1, CreditsAdminService.MAX_SIZE);
        if (filter == null || (long) page * size > Integer.MAX_VALUE) {
            throw new BadRequestException("Invalid request");
        }
        return credits.list(filter, page, size);
    }

    /**
     * Sets the owner's end-credits time; the body is {@code { markerMs }} in whole seconds. A missing body reaches the
     * service as a missing time, which it answers with a 400 once the edition is known to exist.
     */
    @PutMapping("/api/admin/editions/{id}/credits")
    CreditsRow set(@PathVariable long id, @RequestBody(required = false) MarkerUpdate body) {
        return credits.set(id, body == null ? null : body.wholeSecondsMs());
    }

    /** Clears the owner's end-credits time, going back to Plex's marker or none. */
    @DeleteMapping("/api/admin/editions/{id}/credits")
    CreditsRow clear(@PathVariable long id) {
        return credits.clear(id);
    }

    private static int number(Map<String, String> params, String name, int fallback, int min, int max) {
        String value = params.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // falls through to the 400
        }
        throw new BadRequestException("Invalid request");
    }
}
