package gregochr.cinefilm.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

class ClockConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ClockConfig.class);

    @Test
    void theClockRunsInTheConfiguredZoneWhateverTheMachineZoneIs() {
        Clock clock = new ClockConfig().clock(ZoneId.of("Pacific/Auckland"));

        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Pacific/Auckland"));
    }

    @Test
    void theClockIsTheSystemClockNotAFixedOne() {
        ZoneId london = ZoneId.of("Europe/London");

        assertThat(new ClockConfig().clock(london)).isEqualTo(Clock.system(london));
    }

    @Test
    void applicationPropertiesSetTheZoneToEuropeLondonExplicitly() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context.getBean(Clock.class).getZone())
                .isEqualTo(ZoneId.of("Europe/London")));
    }

    @Test
    void theZoneCanBeOverriddenByConfiguration() {
        runner.withPropertyValues("cinefilm.time-zone=America/New_York")
            .run(context -> assertThat(context.getBean(Clock.class).getZone())
                .isEqualTo(ZoneId.of("America/New_York")));
    }
}
