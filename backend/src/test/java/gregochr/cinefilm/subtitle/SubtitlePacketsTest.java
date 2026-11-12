package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cue starts from ffprobe's packet lines: a PGS clear packet is not a cue, a DVD or text packet always is, and
 * anything unusable is ignored. The PGS fixture is real-looking ffprobe output (a display set is hundreds of bytes to
 * tens of kilobytes, the clear set that removes it is about 30 bytes).
 */
class SubtitlePacketsTest {

    private static final Path PGS_FIXTURE = Path.of("src/test/resources/ffprobe/pgs-packets.txt");

    private static SubtitlePackets read(Map<Integer, String> codecs, List<String> lines) {
        SubtitlePackets packets = new SubtitlePackets(codecs);
        lines.forEach(packets::accept);
        return packets;
    }

    @Test
    void aPgsClearPacketIsNotACueAndTheDisplayPacketsAre() throws IOException {
        SubtitlePackets packets = read(Map.of(3, "hdmv_pgs_subtitle"), Files.readAllLines(PGS_FIXTURE));

        // 49 bytes is a clear packet, exactly 50 is the smallest display set; the 1 ms later clear-then-display pair
        // at 77.150 and 77.151 keeps the display one.
        assertThat(packets.starts().get(3)).containsExactly(62_563L, 70_070L, 75_900L, 77_151L, 80_000L, 83_000L,
            3_601_250L);
    }

    @Test
    void onlyTheWantedStreamsAreKept() throws IOException {
        SubtitlePackets packets = read(Map.of(3, "hdmv_pgs_subtitle", 4, "hdmv_pgs_subtitle"),
            Files.readAllLines(PGS_FIXTURE));

        assertThat(packets.starts()).containsOnlyKeys(3, 4);
        assertThat(packets.starts().get(4)).containsExactly(64_000L);
    }

    @Test
    void aDvdSubtitlePacketIsACueWhateverItsSize() {
        SubtitlePackets packets = read(Map.of(2, "dvd_subtitle"), List.of(
            "stream_index=2|pts_time=10.000000|duration_time=2.000000|size=30",
            "stream_index=2|pts_time=20.500000|duration_time=N/A|size=4096"));

        assertThat(packets.starts().get(2)).containsExactly(10_000L, 20_500L);
    }

    @Test
    void aTextPacketIsACueWhateverItsSize() {
        SubtitlePackets packets = read(Map.of(2, "subrip"), List.of(
            "stream_index=2|pts_time=1.000000|duration_time=1.500000|size=12",
            "stream_index=2|pts_time=5.250000|duration_time=1.000000|size=3"));

        assertThat(packets.starts().get(2)).containsExactly(1_000L, 5_250L);
    }

    @Test
    void anMp4MovTextEmptyEndSampleIsNotACueButAnyTextIs() {
        SubtitlePackets packets = read(Map.of(2, "mov_text", 3, "tx3g"), List.of(
            "stream_index=2|pts_time=1.000000|duration_time=2.000000|size=9",
            "stream_index=2|pts_time=3.000000|duration_time=1.000000|size=2",
            "stream_index=2|pts_time=4.000000|duration_time=1.000000|size=3",
            "stream_index=2|pts_time=5.000000|duration_time=1.000000|size=N/A",
            "stream_index=3|pts_time=6.000000|duration_time=1.000000|size=2",
            "stream_index=3|pts_time=7.000000|duration_time=1.000000|size=12"));

        assertThat(packets.starts().get(2)).containsExactly(1_000L, 4_000L);
        assertThat(packets.starts().get(3)).containsExactly(7_000L);
    }

    @Test
    void fieldOrderDoesNotMatter() {
        SubtitlePackets packets = read(Map.of(2, "subrip"), List.of(
            "size=12|duration_time=1.500000|pts_time=1.000000|stream_index=2"));

        assertThat(packets.starts().get(2)).containsExactly(1_000L);
    }

    @Test
    void startsAreSortedAndOnesWithinFiftyMillisecondsAreOneCue() {
        SubtitlePackets packets = read(Map.of(2, "ass"), List.of(
            "stream_index=2|pts_time=30.000000|size=40",
            "stream_index=2|pts_time=10.000000|size=40",
            "stream_index=2|pts_time=10.049000|size=40",
            "stream_index=2|pts_time=10.050000|size=40"));

        assertThat(packets.starts().get(2)).containsExactly(10_000L, 10_050L, 30_000L);
    }

    @Test
    void aPacketWithNoTimeOrNoStreamOrNotANumberIsIgnored() {
        SubtitlePackets packets = read(Map.of(2, "subrip"), List.of(
            "stream_index=2|pts_time=N/A|size=12",
            "pts_time=3.0|size=12",
            "stream_index=x|pts_time=3.0|size=12",
            "stream_index=2|pts_time=NaN|size=12",
            "stream_index=2|pts_time=Infinity|size=12",
            "stream_index=2|pts_time=-0.5|size=12",
            "stream_index=2|junk|pts_time=7.0",
            ""));

        assertThat(packets.starts().get(2)).containsExactly(7_000L);
    }

    @Test
    void aStreamWithNoPacketsHasNoStarts() {
        assertThat(read(Map.of(2, "subrip"), List.of()).starts().get(2)).isEmpty();
    }
}
