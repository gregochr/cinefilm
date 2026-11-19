package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeakRssTest {

    private static final String STATUS = """
        Name:\tjava
        VmPeak:\t 4096000 kB
        VmHWM:\t  123456 kB
        VmRSS:\t   100000 kB
        Threads:\t12
        """;

    @TempDir
    Path temp;

    @Test
    void theVmHwmLineGivesItsKilobytes() {
        assertThat(PeakRss.parse(STATUS)).hasValue(123_456L);
    }

    @Test
    void aStatusWithoutVmHwmIsEmpty() {
        assertThat(PeakRss.parse("Name:\tjava\nVmRSS:\t 100 kB\n")).isEmpty();
        assertThat(PeakRss.parse("")).isEmpty();
    }

    @Test
    void aLineThatOnlyMentionsVmHwmIsNotTheVmHwmLine() {
        assertThat(PeakRss.parse("xVmHWM:\t 5 kB\nVmHWM: 6 kB extra\n")).isEmpty();
    }

    @Test
    void aValueThatIsNotANumberOfKilobytesIsEmpty() {
        assertThat(PeakRss.parse("VmHWM:\t abc kB\n")).isEmpty();
        assertThat(PeakRss.parse("VmHWM:\t 12 MB\n")).isEmpty();
        assertThat(PeakRss.parse("VmHWM:\t 99999999999999999999 kB\n")).isEmpty();
    }

    @Test
    void aStatusFileIsRead() throws IOException {
        Path status = temp.resolve("status");
        Files.writeString(status, STATUS);

        assertThat(PeakRss.read(status)).hasValue(123_456L);
    }

    @Test
    void aMissingFileIsEmptyAsOnTheMac() {
        assertThat(PeakRss.read(temp.resolve("no-such-status"))).isEmpty();
    }
}
