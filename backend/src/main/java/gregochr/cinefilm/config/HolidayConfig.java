package gregochr.cinefilm.config;

import gregochr.cinefilm.film.HolidayOverrides;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code cinefilm.holiday.*} to {@link HolidayOverrides}. */
@Configuration
@EnableConfigurationProperties(HolidayOverrides.class)
public class HolidayConfig {
}
