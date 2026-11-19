package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChristmasOverridesTest {

    private static final Instant EARLIER = NOW.minusSeconds(86_400);

    @Mock
    private ChristmasOverrideRepository repository;

    private ChristmasOverrides service() {
        return new ChristmasOverrides(repository, CLOCK);
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
    void aFilmWithNoRowGetsANewOneStampedWithTheClock() {
        when(repository.findById(1572L)).thenReturn(Optional.empty());

        service().set(1572L, ChristmasChoice.INCLUDE);

        verify(repository).save(argThat((ChristmasOverride row) -> row.getTmdbId() == 1572L
            && row.getChoice() == ChristmasChoice.INCLUDE && row.getCreatedAt().equals(NOW)));
    }

    @Test
    void aFilmWithARowHasItsChoiceChangedInPlaceAndKeepsItsCreatedAt() {
        ChristmasOverride existing = new ChristmasOverride(700L, ChristmasChoice.INCLUDE, EARLIER);
        when(repository.findById(700L)).thenReturn(Optional.of(existing));

        service().set(700L, ChristmasChoice.EXCLUDE);

        assertThat(existing.getChoice()).isEqualTo(ChristmasChoice.EXCLUDE);
        assertThat(existing.getCreatedAt()).isEqualTo(EARLIER);
        assertThat(existing.getTmdbId()).isEqualTo(700L);
    }

    @Test
    void settingTheSameChoiceAgainChangesNothing() {
        ChristmasOverride existing = new ChristmasOverride(700L, ChristmasChoice.EXCLUDE, EARLIER);
        when(repository.findById(700L)).thenReturn(Optional.of(existing));

        service().set(700L, ChristmasChoice.EXCLUDE);

        assertThat(existing.getChoice()).isEqualTo(ChristmasChoice.EXCLUDE);
        assertThat(existing.getCreatedAt()).isEqualTo(EARLIER);
    }

    @Test
    void removeDeletesTheFilmsRowByItsTmdbId() {
        service().remove(700L);

        verify(repository).deleteById(700L);
    }
}
