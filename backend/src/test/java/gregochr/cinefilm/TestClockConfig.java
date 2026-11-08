package gregochr.cinefilm;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Replaces the application clock with {@link TestData#CLOCK} in every full-context test (profile {@code test}), so
 * "recently added", "not watched in years" and the weekly theme are the same on every day and in every time zone the
 * suite runs in. Found by component scan from {@code gregochr.cinefilm}; the {@code @Primary} bean wins over
 * {@code ClockConfig}'s. Slice tests ({@code @WebMvcTest}) skip {@code @Configuration} classes and need no clock.
 */
@Configuration
@Profile("test")
public class TestClockConfig {

    /** The fixed clock every full-context test sees. */
    @Bean
    @Primary
    Clock testClock() {
        return TestData.CLOCK;
    }
}
