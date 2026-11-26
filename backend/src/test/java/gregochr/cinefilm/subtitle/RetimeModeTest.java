package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RetimeModeTest {

    @Test
    void onAndRestoreAreReadInAnyCaseWithSpacesAroundThem() {
        assertThat(RetimeMode.parse("on")).isEqualTo(RetimeMode.ON);
        assertThat(RetimeMode.parse(" ON ")).isEqualTo(RetimeMode.ON);
        assertThat(RetimeMode.parse("Restore")).isEqualTo(RetimeMode.RESTORE);
    }

    @Test
    void offNullBlankAndAnythingElseAreOff() {
        assertThat(RetimeMode.parse("off")).isEqualTo(RetimeMode.OFF);
        assertThat(RetimeMode.parse(null)).isEqualTo(RetimeMode.OFF);
        assertThat(RetimeMode.parse("")).isEqualTo(RetimeMode.OFF);
        assertThat(RetimeMode.parse("true")).isEqualTo(RetimeMode.OFF);
    }

    @Test
    void applicationPropertiesShipRetimeOn() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(RetimeMode.parse(
                context.getEnvironment().getProperty("cinefilm.subtitles.retime"))).isEqualTo(RetimeMode.ON));
    }
}
