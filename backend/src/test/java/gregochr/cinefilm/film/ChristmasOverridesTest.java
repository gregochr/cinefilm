package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChristmasOverridesTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Mock
    private ChristmasOverrideRepository repository;

    private ChristmasOverrides service() {
        return new ChristmasOverrides(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void includesAndExcludesAreTheTmdbIdsOfTheRowsWithThatChoice() {
        when(repository.findByChoice(ChristmasChoice.INCLUDE)).thenReturn(List.of(
            new ChristmasOverride(1572L, ChristmasChoice.INCLUDE, NOW),
            new ChristmasOverride(5555L, ChristmasChoice.INCLUDE, NOW)));
        when(repository.findByChoice(ChristmasChoice.EXCLUDE)).thenReturn(List.of(
            new ChristmasOverride(562L, ChristmasChoice.EXCLUDE, NOW)));

        assertThat(service().includes()).containsExactlyInAnyOrder(1572L, 5555L);
        assertThat(service().excludes()).containsExactly(562L);
    }

    @Test
    void anEmptyTableGivesEmptySets() {
        when(repository.findByChoice(ChristmasChoice.INCLUDE)).thenReturn(List.of());
        when(repository.findByChoice(ChristmasChoice.EXCLUDE)).thenReturn(List.of());

        assertThat(service().includes()).isEmpty();
        assertThat(service().excludes()).isEmpty();
    }

    @Test
    void theSetsCannotBeChangedByTheCaller() {
        when(repository.findByChoice(ChristmasChoice.INCLUDE)).thenReturn(List.of(
            new ChristmasOverride(1L, ChristmasChoice.INCLUDE, NOW)));

        Set<Long> includes = service().includes();

        assertThatThrownBy(() -> includes.add(2L))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void setSavesOneRowWithTheChoiceAndTheClocksStamp() {
        service().set(1572L, ChristmasChoice.INCLUDE);

        verify(repository).save(argThat((ChristmasOverride row) -> row.getTmdbId() == 1572L
            && row.getChoice() == ChristmasChoice.INCLUDE && row.getCreatedAt().equals(NOW)));
    }

    @Test
    void flippingAnIncludeToAnExcludeReplacesTheOneRowForTheFilm() {
        Map<Long, ChristmasOverride> table = new HashMap<>();
        when(repository.save(any(ChristmasOverride.class))).thenAnswer(call -> {
            ChristmasOverride row = call.getArgument(0);
            table.put(row.getTmdbId(), row);
            return row;
        });
        when(repository.findByChoice(ChristmasChoice.INCLUDE)).thenAnswer(call -> rows(table, ChristmasChoice.INCLUDE));
        when(repository.findByChoice(ChristmasChoice.EXCLUDE)).thenAnswer(call -> rows(table, ChristmasChoice.EXCLUDE));
        ChristmasOverrides service = service();

        service.set(700L, ChristmasChoice.INCLUDE);
        assertThat(service.includes()).containsExactly(700L);
        service.set(700L, ChristmasChoice.EXCLUDE);

        assertThat(table).hasSize(1);
        assertThat(service.includes()).isEmpty();
        assertThat(service.excludes()).containsExactly(700L);
    }

    @Test
    void removeDeletesTheFilmsRowByItsTmdbId() {
        service().remove(700L);

        verify(repository).deleteById(700L);
    }

    private static List<ChristmasOverride> rows(Map<Long, ChristmasOverride> table, ChristmasChoice choice) {
        return table.values().stream().filter(r -> r.getChoice() == choice).toList();
    }
}
