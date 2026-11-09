package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StartRungTest {

    // peak: original 6,128,000 (4 Mbps * 1.5 + 128k); 720p 3,128,000; 480p 1,328,000; 360p 816,000
    private static final List<Rung> RUNGS = List.of(new Rung("original", 1080, 4_000_000, true),
        new Rung("720p", 720, 2_500_000, false), new Rung("480p", 480, 1_000_000, false),
        new Rung("360p", 360, 600_000, false));

    @Test
    void noProbeStartsOnTheTopRung() {
        assertThat(StartRung.pick(RUNGS, null).id()).isEqualTo("original");
    }

    @Test
    void theHighestRungWhosePeakWithHeadroomFitsIsChosen() {
        assertThat(StartRung.pick(RUNGS, 30_000L).id()).isEqualTo("original");
        assertThat(StartRung.pick(RUNGS, 5_000L).id()).isEqualTo("720p");
        assertThat(StartRung.pick(RUNGS, 2_000L).id()).isEqualTo("480p");
    }

    @Test
    void theBoundaryIsExactlyPeakTimes1Point25() {
        // 720p: 3,128,000 * 1.25 = 3,910,000 bps = 3,910 kbps
        assertThat(StartRung.pick(RUNGS, 3_910L).id()).isEqualTo("720p");
        assertThat(StartRung.pick(RUNGS, 3_909L).id()).isEqualTo("480p");
        // 360p: 816,000 * 1.25 = 1,020,000; 480p: 1,328,000 * 1.25 = 1,660,000
        assertThat(StartRung.pick(RUNGS, 1_660L).id()).isEqualTo("480p");
        assertThat(StartRung.pick(RUNGS, 1_659L).id()).isEqualTo("360p");
    }

    @Test
    void aProbeBelowEveryRungStartsOnTheLowest() {
        assertThat(StartRung.pick(RUNGS, 100L).id()).isEqualTo("360p");
        assertThat(StartRung.pick(RUNGS, 0L).id()).isEqualTo("360p");
    }

    @Test
    void noRungsIsAProgrammingError() {
        assertThatThrownBy(() -> StartRung.pick(List.of(), 1000L)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a film has at least one rung");
    }
}
