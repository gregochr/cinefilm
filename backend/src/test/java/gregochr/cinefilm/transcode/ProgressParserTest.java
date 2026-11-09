package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProgressParserTest {

    private final ProgressParser parser = new ProgressParser();

    private Optional<EncoderProgress> feed(String... lines) {
        Optional<EncoderProgress> last = Optional.empty();
        for (String line : lines) {
            last = parser.accept(line);
        }
        return last;
    }

    @Test
    void aBlockEndingInProgressContinueReportsTheTimeAndSpeed() {
        Optional<EncoderProgress> report = feed("frame=120", "fps=30.0", "out_time_us=4004000", "speed=3.5x",
            "progress=continue");

        assertThat(report).contains(new EncoderProgress(4004, 3.5));
    }

    @Test
    void linesInsideABlockReportNothing() {
        assertThat(parser.accept("out_time_us=1000")).isEmpty();
        assertThat(parser.accept("speed=1.0x")).isEmpty();
        assertThat(parser.accept("frame=3")).isEmpty();
        assertThat(parser.accept("not a key value line")).isEmpty();
    }

    @Test
    void outTimeMsIsAlsoMicroseconds() {
        assertThat(feed("out_time_ms=2500000", "progress=end")).contains(new EncoderProgress(2500, 0));
    }

    @Test
    void notAvailableValuesKeepTheLastTimeAndZeroTheSpeed() {
        feed("out_time_us=3000000", "speed=2.0x", "progress=continue");

        assertThat(feed("out_time_us=N/A", "speed=N/A", "progress=continue")).contains(new EncoderProgress(3000, 0));
    }

    @Test
    void aNegativeTimeIsClampedToZeroAndANonPositiveSpeedIsZero() {
        assertThat(feed("out_time_us=-5000", "speed=-1x", "progress=continue")).contains(new EncoderProgress(0, 0));
        assertThat(feed("speed=0x", "progress=continue").orElseThrow().speed()).isZero();
        assertThat(feed("speed=inf", "progress=continue").orElseThrow().speed()).isZero();
    }

    @Test
    void aSpeedWithoutTheTrailingXParses() {
        assertThat(feed("speed=1.25", "progress=continue").orElseThrow().speed()).isEqualTo(1.25);
        assertThat(feed("speed= 0.5 x", "progress=continue").orElseThrow().speed()).isEqualTo(0.5);
    }

    @Test
    void aSpeedThatIsNotFiniteIsZero() {
        assertThat(feed("speed=Infinity", "progress=continue").orElseThrow().speed()).isZero();
        assertThat(feed("speed=NaN", "progress=continue").orElseThrow().speed()).isZero();
    }
}
