package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.library.LineStreamRunner;
import gregochr.cinefilm.library.LineStreamRunner.Outcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyframeIndexerTest {

    private static final Path FILE = Path.of("/media/Heat.mp4");

    private final List<List<String>> commands = new ArrayList<>();

    private Mp4SampleTableReader reader(KeyframeIndex result) {
        return new Mp4SampleTableReader() {
            @Override
            public KeyframeIndex index(Path file) {
                if (result == null) {
                    throw new KeyframeIndexException("Not an MP4 file");
                }
                return result;
            }
        };
    }

    private final List<java.time.Duration> timeouts = new ArrayList<>();

    private KeyframeIndexer indexer(KeyframeIndex readerResult, Outcome outcome, String... lines) {
        LineStreamRunner runner = (command, timeout, handler) -> {
            commands.add(command);
            timeouts.add(timeout);
            for (String line : lines) {
                handler.accept(line);
            }
            return outcome;
        };
        return new KeyframeIndexer(reader(readerResult), runner, "ffprobe");
    }

    private static final Outcome OK = new Outcome(0, false);

    @Test
    void theSampleTablesAreUsedWhenTheyGiveKeyframesAndKeepTheFrameRate() {
        KeyframeIndex tables = new KeyframeIndex(List.of(0L, 1000L), 23.976);

        assertThat(indexer(tables, null).index(FILE)).isEqualTo(tables);
        assertThat(commands).isEmpty();
    }

    @Test
    void theFallbackCommandListsVideoPacketTimesAndFlagsAsAnArgumentArrayWithTheFileProtocolOnly() {
        indexer(null, OK, "0.000000,K_").index(FILE);

        assertThat(commands).containsExactly(List.of("ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0",
            "/media/Heat.mp4"));
    }

    @Test
    void onlyKeyframePacketsAreKeptSortedDeduplicatedInMillisecondsWithAnUnknownFrameRate() {
        String[] output = {"4.004000,K_", "0.000000,K_", "0.041000,__", "8.008600,K_",
            "N/A,K_", "", "NaN,K_", "Infinity,K_", "4.004000,K_", "-1.500000,K_", "abc,K_", "9.000000", "10.5,_K",
            "11.0,KD"};

        assertThat(indexer(null, OK, output).index(FILE))
            .isEqualTo(new KeyframeIndex(List.of(0L, 4004L, 8009L, 10_500L, 11_000L), null));
    }

    @Test
    void aFallbackWithNoKeyframePacketFails() {
        assertThatThrownBy(() -> indexer(null, OK, "0.000,__", "N/A,K_", "").index(FILE))
            .isInstanceOf(KeyframeIndexException.class).hasMessage("ffprobe found no keyframes");
    }

    @Test
    void aFailingOrTimedOutFfprobeFails() {
        assertThatThrownBy(() -> indexer(null, new Outcome(1, false)).index(FILE))
            .hasMessage("ffprobe exited with status 1");
        assertThatThrownBy(() -> indexer(null, new Outcome(-1, true)).index(FILE))
            .hasMessage("ffprobe timed out");
    }

    @Test
    void aRunThatDiesPartWayNeverYieldsAPartialIndexEvenWhenKeyframesWereAlreadySeen() {
        assertThatThrownBy(() -> indexer(null, new Outcome(1, false), "0.000000,K_", "4.000000,K_").index(FILE))
            .isInstanceOf(KeyframeIndexException.class).hasMessage("ffprobe exited with status 1");
        assertThatThrownBy(() -> indexer(null, new Outcome(-1, true), "0.000000,K_", "4.000000,K_").index(FILE))
            .isInstanceOf(KeyframeIndexException.class).hasMessage("ffprobe timed out");
        assertThatThrownBy(() -> indexer(null, new Outcome(0, true), "0.000000,K_").index(FILE))
            .hasMessage("ffprobe timed out");
    }

    @Test
    void theFallbackIsGivenFifteenMinutes() {
        indexer(null, OK, "0.000000,K_").index(FILE);

        assertThat(timeouts).containsExactly(KeyframeIndexer.FFPROBE_TIMEOUT);
        assertThat(KeyframeIndexer.FFPROBE_TIMEOUT).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    void ffprobeThatCannotBeStartedFails() {
        LineStreamRunner missing = (command, timeout, handler) -> {
            throw new IOException("No such file");
        };
        KeyframeIndexer indexer = new KeyframeIndexer(reader(null), missing, "ffprobe");

        assertThatThrownBy(() -> indexer.index(FILE)).isInstanceOf(KeyframeIndexException.class)
            .hasMessage("ffprobe could not be run: No such file");
    }
}
