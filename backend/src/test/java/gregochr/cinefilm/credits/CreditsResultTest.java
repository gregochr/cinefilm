package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditsResultTest {

    @Test
    void aFoundResultCarriesItsOffsetAndReason() {
        CreditsResult result = CreditsResult.found(5_000L, "because");

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isEqualTo(5_000L);
        assertThat(result.reasons()).containsExactly("because");
    }

    @Test
    void anUnsureResultMayHaveNoOffset() {
        assertThat(CreditsResult.unsure(null, "hmm").offsetMs()).isNull();
        assertThat(CreditsResult.unsure(7L, "hmm").offsetMs()).isEqualTo(7L);
    }

    @Test
    void aNotFoundResultHasNoOffset() {
        CreditsResult result = CreditsResult.notFound("none");

        assertThat(result.verdict()).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(result.offsetMs()).isNull();
    }

    @Test
    void refusesAFoundResultWithoutAnOffset() {
        assertThatThrownBy(() -> new CreditsResult(CreditsVerdict.FOUND, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A found result needs an offset");
    }

    @Test
    void refusesANotFoundResultWithAnOffset() {
        assertThatThrownBy(() -> new CreditsResult(CreditsVerdict.NOT_FOUND, 1L, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A not-found result has no offset");
    }

    @Test
    void refusesNoVerdict() {
        assertThatThrownBy(() -> new CreditsResult(null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A result needs a verdict");
    }

    @Test
    void copiesTheReasons() {
        List<String> reasons = new ArrayList<>(List.of("a"));
        CreditsResult result = new CreditsResult(CreditsVerdict.UNSURE, null, reasons);

        reasons.add("b");

        assertThat(result.reasons()).containsExactly("a");
    }
}
