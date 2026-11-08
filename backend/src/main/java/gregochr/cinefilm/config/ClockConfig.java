package gregochr.cinefilm.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the application clock so time-based rules are testable. */
@Configuration
public class ClockConfig {

    /** The system clock in the server's zone (the weekly theme rolls over on Monday local time). */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
