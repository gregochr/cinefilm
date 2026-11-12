package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LineStreamRunner;
import gregochr.cinefilm.subtitle.EmbeddedCueReader.ReadException;
import gregochr.cinefilm.subtitle.EmbeddedCueReader.Reading;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Reading a film file's full English subtitle track with ffprobe, against fake runners: the exact commands (an argument
 * list, the file protocol only, the file last and absolute, packets run under nice with a 20 minute timeout), what is
 * chosen, and that every way a run can fail throws and never gives a partial answer.
 */
class EmbeddedCueReaderTest {

    private static final Path FILE = Path.of("/library/Taken (2008).mkv");

    private static final String FULL_AND_FORCED = """
        {"streams":[
          {"index":2,"codec_name":"subrip","codec_type":"subtitle","disposition":{"default":1,"forced":0},
           "tags":{"language":"eng","title":"English"}},
          {"index":3,"codec_name":"hdmv_pgs_subtitle","codec_type":"subtitle","disposition":{"default":0,"forced":1},
           "tags":{"language":"eng"}},
          {"index":4,"codec_name":"subrip","codec_type":"subtitle","disposition":{"default":0,"forced":0},
           "tags":{"language":"fre"}},
          {"index":5,"codec_name":"mjpeg","codec_type":"video"},
          {"codec_name":"subrip","codec_type":"subtitle"}
        ]}""";

    private final List<List<String>> listed = new ArrayList<>();
    private final List<List<String>> packetRuns = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private CommandRunner.Result listResult = new CommandRunner.Result(0, FULL_AND_FORCED, false);
    private IOException listFailure;
    private LineStreamRunner.Outcome packetOutcome = new LineStreamRunner.Outcome(0, false);
    private IOException packetFailure;
    private List<String> packetLines = packetLines(2, 120);

    private EmbeddedCueReader reader(int niceness) {
        CommandRunner commands = (command, timeout) -> {
            listed.add(command);
            timeouts.add(timeout);
            if (listFailure != null) {
                throw listFailure;
            }
            return listResult;
        };
        LineStreamRunner lines = (command, timeout, consumer) -> {
            packetRuns.add(command);
            timeouts.add(timeout);
            if (packetFailure != null) {
                throw packetFailure;
            }
            packetLines.forEach(consumer);
            return packetOutcome;
        };
        return new EmbeddedCueReader(commands, lines, "ffprobe", niceness);
    }

    private static List<String> packetLines(int stream, int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> "stream_index=" + stream + "|pts_time=" + (i * 5) + ".000000|duration_time=2.0|size=40")
            .toList();
    }

    @Test
    void theFullEnglishTextTrackIsChosenAndItsStartsReturned() {
        Reading reading = reader(10).read(FILE);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.USABLE);
        assertThat(reading.subtitleStreamCount()).isEqualTo(3);
        assertThat(reading.streamIndex()).isEqualTo(2);
        assertThat(reading.codec()).isEqualTo("subrip");
        assertThat(reading.reason()).isNull();
        assertThat(reading.startsMs()).hasSize(120).startsWith(0L, 5_000L, 10_000L).endsWith(595_000L);
    }

    @Test
    void theListingIsAnArgumentListOfTheFileProtocolOnlyWithTheAbsoluteFileLast() {
        reader(10).read(FILE);

        assertThat(listed).containsExactly(List.of("ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-select_streams", "s", "-show_streams", "-print_format", "json", "/library/Taken (2008).mkv"));
    }

    @Test
    void thePacketPassIsNicedReadsOnlySubtitlePacketsAndHasTheFileLast() {
        reader(10).read(FILE);

        assertThat(packetRuns).containsExactly(List.of("nice", "-n", "10", "ffprobe", "-v", "error",
            "-protocol_whitelist", "file", "-select_streams", "s", "-show_entries",
            "packet=stream_index,pts_time,duration_time,size", "-print_format", "compact=p=0",
            "/library/Taken (2008).mkv"));
        assertThat(timeouts).containsExactly(Duration.ofSeconds(60), Duration.ofMinutes(20));
    }

    @Test
    void withNoNicenessTheCommandIsNotWrapped() {
        reader(0).read(FILE);

        assertThat(packetRuns.get(0)).startsWith("ffprobe", "-v", "error", "-protocol_whitelist", "file");
    }

    @Test
    void aRelativeFileIsMadeAbsolute() {
        assertThat(EmbeddedCueReader.listCommand("ffprobe", Path.of("Taken.mkv")).get(10)).startsWith("/")
            .endsWith("Taken.mkv");
    }

    @Test
    void aFileWithNoSubtitleTracksIsNoReferenceAndIsNotReadForPackets() {
        listResult = new CommandRunner.Result(0, "{\"streams\":[]}", false);

        Reading reading = reader(10).read(FILE);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.NO_REFERENCE);
        assertThat(reading.subtitleStreamCount()).isZero();
        assertThat(reading.streamIndex()).isNull();
        assertThat(reading.codec()).isNull();
        assertThat(reading.startsMs()).isEmpty();
        assertThat(reading.reason()).isEqualTo("The file has no subtitle track");
        assertThat(packetRuns).isEmpty();
    }

    @Test
    void aFileWithOnlyForcedOrForeignTracksIsNoReferenceAndIsNotReadForPackets() {
        listResult = new CommandRunner.Result(0, """
            {"streams":[{"index":2,"codec_name":"subrip","codec_type":"subtitle","disposition":{"forced":1},
             "tags":{"language":"eng"}}]}""", false);

        Reading reading = reader(10).read(FILE);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.NO_REFERENCE);
        assertThat(reading.subtitleStreamCount()).isEqualTo(1);
        assertThat(reading.reason()).startsWith("The file has no full English subtitle track");
        assertThat(packetRuns).isEmpty();
    }

    @Test
    void aTrackThatIsAllForcedLinesIsNoReference() {
        packetLines = packetLines(2, 40);

        Reading reading = reader(10).read(FILE);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.NO_REFERENCE);
        assertThat(reading.reason()).isEqualTo("The English subtitle track has too few lines to be a full track");
        assertThat(reading.subtitleStreamCount()).isEqualTo(3);
    }

    @Test
    void aBlankOrNonTextLanguageAndTitleCountAsMissing() {
        listResult = new CommandRunner.Result(0, """
            {"streams":[{"index":2,"codec_name":"subrip","codec_type":"subtitle","disposition":{},
             "tags":{"language":"eng","title":7}},
             {"index":3,"codec_name":"subrip","codec_type":"subtitle","tags":{"language":"  ","title":""}}]}""",
            false);

        Reading reading = reader(10).read(FILE);

        // Stream 2 is English with no usable title; stream 3 has no language, so only stream 2 is read.
        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.USABLE);
        assertThat(reading.streamIndex()).isEqualTo(2);
        assertThat(reading.subtitleStreamCount()).isEqualTo(2);
    }

    @Test
    void aFailedListingThrows() {
        listResult = new CommandRunner.Result(1, "", false);
        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessage("ffprobe exited with status 1 listing the subtitle tracks");

        listResult = new CommandRunner.Result(-1, "", true);
        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessage("ffprobe timed out listing the subtitle tracks");
    }

    @Test
    void aListingThatCannotBeRunOrIsNotJsonThrows() {
        listFailure = new IOException("Cannot run program \"ffprobe\"");
        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessageStartingWith("ffprobe could not be run: ").hasCauseInstanceOf(IOException.class);

        listFailure = null;
        listResult = new CommandRunner.Result(0, "not json", false);
        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessage("ffprobe's stream list is not JSON");
    }

    @Test
    void aFailedPacketPassThrowsAndGivesNothing() {
        packetOutcome = new LineStreamRunner.Outcome(1, false);
        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessage("ffprobe exited with status 1 reading the subtitle packets");

        packetOutcome = new LineStreamRunner.Outcome(-1, true);
        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessage("ffprobe timed out reading the subtitle packets");
    }

    @Test
    void aPacketPassThatCannotBeRunThrows() {
        packetFailure = new IOException("broken pipe");

        assertThatThrownBy(() -> reader(10).read(FILE)).isInstanceOf(ReadException.class)
            .hasMessage("ffprobe could not be run: broken pipe").hasCauseInstanceOf(IOException.class);
    }

    @Test
    void aPictureTrackIsReadWithTheClearPacketsDropped() {
        listResult = new CommandRunner.Result(0, """
            {"streams":[{"index":3,"codec_name":"hdmv_pgs_subtitle","codec_type":"subtitle",
             "disposition":{"default":1,"forced":0},"tags":{"language":"eng"}}]}""", false);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines.add("stream_index=3|pts_time=" + (i * 10) + ".000000|duration_time=N/A|size=4000");
            lines.add("stream_index=3|pts_time=" + (i * 10 + 3) + ".500000|duration_time=N/A|size=30");
        }
        packetLines = lines;

        Reading reading = reader(10).read(FILE);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.USABLE);
        assertThat(reading.codec()).isEqualTo("hdmv_pgs_subtitle");
        assertThat(reading.startsMs()).hasSize(100).startsWith(0L, 10_000L);
    }

    @Test
    void theReadExceptionKeepsItsMessageAndCause() {
        Throwable cause = new IllegalStateException("x");

        assertThat(new ReadException("m", cause)).hasMessage("m").hasCause(cause);
        assertThat(new ReadException("m")).hasMessage("m").hasNoCause();
    }
}
