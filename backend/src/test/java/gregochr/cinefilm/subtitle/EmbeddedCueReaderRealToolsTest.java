package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.ProcessCommandRunner;
import gregochr.cinefilm.library.ProcessLineStreamRunner;
import gregochr.cinefilm.subtitle.EmbeddedCueReader.Reading;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real {@code ffprobe} and {@code ffmpeg}: generates a Matroska clip with a full English {@code subrip} track
 * (120 cues, one every 5 seconds) and a forced one (12 cues), reads the clip's cue starts with the real
 * {@link EmbeddedCueReader}, and fits a forced file that is 2.5 seconds early against them. CI installs ffmpeg and
 * runs this; it is skipped (not failed) where the tools are not on the PATH, as on the dev Mac.
 *
 * <p>ffmpeg cannot make picture subtitles (PGS, DVD) from text, so the picture packet rules are covered by
 * {@code SubtitlePacketsTest} against a fixture of real-looking ffprobe output instead.
 */
@Tag("real-tools")
class EmbeddedCueReaderRealToolsTest {

    private static final ProcessCommandRunner RUNNER = new ProcessCommandRunner();
    private static final int FULL_CUES = 120;

    @TempDir
    Path library;

    @BeforeAll
    static void toolsAreInstalled() {
        assumeTrue(runs("ffprobe") && runs("ffmpeg"), "ffprobe and ffmpeg are not on the PATH");
    }

    private static boolean runs(String tool) {
        try {
            return RUNNER.run(List.of(tool, "-version"), Duration.ofSeconds(20)).succeeded();
        } catch (IOException e) {
            return false;
        }
    }

    private static String srt(List<Long> starts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < starts.size(); i++) {
            long start = starts.get(i);
            text.append(i + 1).append('\n').append(timestamp(start)).append(" --> ").append(timestamp(start + 2_000))
                .append("\nLine ").append(i + 1).append("\n\n");
        }
        return text.toString();
    }

    private static String timestamp(long ms) {
        return "%02d:%02d:%02d,%03d".formatted(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000);
    }

    /** A black ten-minute clip with a full English track, then a forced English track, both as {@code subrip}. */
    private Path clip(List<Long> fullStarts, List<Long> forcedStarts) throws IOException {
        Files.writeString(library.resolve("full.srt"), srt(fullStarts));
        Files.writeString(library.resolve("forced.srt"), srt(forcedStarts));
        Path clip = library.resolve("Test Film.mkv");
        CommandRunner.Result made = RUNNER.run(List.of("ffmpeg", "-v", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "color=c=black:s=64x48:r=1:d=620",
            "-i", library.resolve("forced.srt").toString(),
            "-i", library.resolve("full.srt").toString(),
            "-map", "0:v", "-map", "1:0", "-map", "2:0",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:s", "subrip",
            "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=Forced",
            "-disposition:s:0", "forced",
            "-metadata:s:s:1", "language=eng", "-metadata:s:s:1", "title=English",
            "-disposition:s:1", "default",
            "-f", "matroska", clip.toString()), Duration.ofSeconds(120));
        assertThat(made.succeeded()).as("ffmpeg generated the test clip").isTrue();
        return clip;
    }

    private static List<Long> every(long gap, int count, long from) {
        return IntStream.range(0, count).mapToObj(i -> from + i * gap).toList();
    }

    @Test
    void theFullEnglishTrackIsReadNotTheForcedOneAndAShiftedForcedFileFitsIt() throws IOException {
        List<Long> full = every(5_000, FULL_CUES, 5_000);
        List<Long> forced = every(50_000, 12, 5_000);
        Path clip = clip(full, forced);
        EmbeddedCueReader reader = new EmbeddedCueReader(RUNNER, new ProcessLineStreamRunner(), "ffprobe", 10);

        Reading reading = reader.read(clip);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.USABLE);
        assertThat(reading.subtitleStreamCount()).isEqualTo(2);
        assertThat(reading.streamIndex()).isEqualTo(2);
        assertThat(reading.codec()).isEqualTo("subrip");
        assertThat(reading.startsMs()).containsExactlyElementsOf(full);

        // The owner's forced file is 2.5 s early: its cues are the film's, minus 2.5 s.
        List<Long> early = forced.stream().map(start -> start - 2_500).toList();
        Result result = TimingFit.fit(early, reading.startsMs());

        assertThat(result.verdict()).isEqualTo(TimingFit.Verdict.ALIGNED);
        assertThat(result.fittedOffsetMs()).isEqualTo(2_500);
        assertThat(result.matchedShare()).isEqualTo(1.0);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void aFileWhoseOnlyEnglishTrackIsTheForcedOneHasNoReference() throws IOException {
        // Two tracks again, but the "full" one has only 12 cues: nothing in the file is a full track.
        Path clip = clip(every(50_000, 12, 5_000), every(50_000, 12, 5_000));
        EmbeddedCueReader reader = new EmbeddedCueReader(RUNNER, new ProcessLineStreamRunner(), "ffprobe", 0);

        Reading reading = reader.read(clip);

        assertThat(reading.status()).isEqualTo(EmbeddedCueStarts.Status.NO_REFERENCE);
        assertThat(reading.subtitleStreamCount()).isEqualTo(2);
        assertThat(reading.reason()).isEqualTo("The English subtitle track has too few lines to be a full track");
    }

    @Test
    void aFileThatIsNotMediaIsAReadFailureNotAnAnswer() throws IOException {
        Path junk = library.resolve("junk.mkv");
        Files.writeString(junk, "this is not a video");
        EmbeddedCueReader reader = new EmbeddedCueReader(RUNNER, new ProcessLineStreamRunner(), "ffprobe", 0);

        assertThatThrownBy(() -> reader.read(junk))
            .isInstanceOf(EmbeddedCueReader.ReadException.class);
    }
}
