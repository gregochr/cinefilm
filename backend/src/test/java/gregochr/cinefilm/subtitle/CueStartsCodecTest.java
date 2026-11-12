package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cue starts stored as gaps in plain text. */
class CueStartsCodecTest {

    @Test
    void startsAreStoredAsTheFirstAndThenTheGaps() {
        assertThat(CueStartsCodec.encode(List.of(12_000L, 12_350L, 14_560L))).isEqualTo("12000,350,2210");
    }

    @Test
    void whatIsEncodedDecodesToTheSameStarts() {
        List<Long> starts = List.of(0L, 0L, 500L, 3_601_250L, 3_601_250L, 7_200_000L);

        assertThat(CueStartsCodec.decode(CueStartsCodec.encode(starts))).containsExactlyElementsOf(starts);
    }

    @Test
    void noStartsAreEmptyTextAndBack() {
        assertThat(CueStartsCodec.encode(List.of())).isEmpty();
        assertThat(CueStartsCodec.decode("")).isEmpty();
    }

    @Test
    void aSingleStartIsItself() {
        assertThat(CueStartsCodec.encode(List.of(42L))).isEqualTo("42");
        assertThat(CueStartsCodec.decode("42")).containsExactly(42L);
    }

    @Test
    void textThatIsNotNumbersIsRefused() {
        assertThatThrownBy(() -> CueStartsCodec.decode("12,x,3")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Stored cue starts are not numbers");
        assertThatThrownBy(() -> CueStartsCodec.decode("12,,3")).isInstanceOf(IllegalArgumentException.class);
    }
}
