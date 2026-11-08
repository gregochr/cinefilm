package gregochr.cinefilm.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application clock so time-based rules are testable. Production code reads the time only through this
 * clock, never with {@code now()} on its own.
 */
@Configuration
public class ClockConfig {

    /**
     * The system clock in the application time zone ({@code cinefilm.time-zone}, {@code Europe/London} by default
     * in {@code application.properties}). The zone is explicit, not the machine's, so the weekly theme rolls over on
     * Monday in London whatever the container's zone is.
     *
     * @param zone the application time zone
     * @return the application clock
     */
    @Bean
    public Clock clock(@Value("${cinefilm.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
