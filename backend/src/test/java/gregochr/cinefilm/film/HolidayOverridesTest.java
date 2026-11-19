package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class HolidayOverridesTest {

    private static HolidayOverrides bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bind("cinefilm.holiday.christmas", HolidayOverrides.class)
            .orElse(HolidayOverrides.NONE);
    }

    @Test
    void commaSeparatedIdsBindToLists() {
        HolidayOverrides overrides = bind(Map.of(
            "cinefilm.holiday.christmas.include", "1572, 5555",
            "cinefilm.holiday.christmas.exclude", "562"));

        assertThat(overrides.include()).containsExactly(1572L, 5555L);
        assertThat(overrides.exclude()).containsExactly(562L);
        assertThat(overrides.includeSet()).containsExactlyInAnyOrder(1572L, 5555L);
        assertThat(overrides.excludeSet()).containsExactly(562L);
    }

    @Test
    void aTrailingOrDoubledCommaDropsTheEmptyEntry() {
        HolidayOverrides overrides = bind(Map.of(
            "cinefilm.holiday.christmas.include", "1572,",
            "cinefilm.holiday.christmas.exclude", "1572,,5555"));

        assertThat(overrides.include()).containsExactly(1572L);
        assertThat(overrides.exclude()).containsExactly(1572L, 5555L);
    }

    @Test
    void emptyValuesBindToEmptyLists() {
        HolidayOverrides overrides = bind(Map.of(
            "cinefilm.holiday.christmas.include", "",
            "cinefilm.holiday.christmas.exclude", ""));

        assertThat(overrides.include()).isEmpty();
        assertThat(overrides.exclude()).isEmpty();
    }

    @Test
    void unsetListsAreEmpty() {
        HolidayOverrides overrides = new HolidayOverrides(null, null);

        assertThat(overrides.include()).isEmpty();
        assertThat(overrides.exclude()).isEmpty();
        assertThat(overrides.includeSet()).isEmpty();
        assertThat(overrides).isEqualTo(new HolidayOverrides(List.of(), List.of()));
    }
}
