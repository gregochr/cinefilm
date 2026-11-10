package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.subtitle.SubtitleService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The real {@code ffprobe} and {@code ffmpeg}: generates a two second clip with a {@code mov_text} subtitle, probes
 * it and extracts the WebVTT. CI installs ffmpeg and runs this; it is skipped (not failed) where the tools are not on
 * the PATH, as on the dev Mac.
 */
@Tag("real-tools")
class RealToolsTest {

    private static final ProcessCommandRunner RUNNER = new ProcessCommandRunner();

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

    private Path clip() throws IOException {
        Files.writeString(library.resolve("cue.srt"), "1\n00:00:00,000 --> 00:00:01,500\nHello from CineFilm\n");
        Path clip = library.resolve("Test Film.m4v");
        CommandRunner.Result made = RUNNER.run(List.of("ffmpeg", "-v", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-i", library.resolve("cue.srt").toString(),
            "-map", "0:v", "-map", "1:a", "-map", "2:s",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-c:s", "mov_text", "-shortest",
            "-f", "mp4", clip.toString()), Duration.ofSeconds(60));
        assertThat(made.succeeded()).as("ffmpeg generated the test clip").isTrue();
        return clip;
    }

    @Test
    void ffprobeReadsTheGeneratedClip() throws IOException {
        ProbeResult probe = new FfprobeService(RUNNER, "ffprobe", CLOCK).probe(clip());

        assertThat(probe.durationMs()).isBetween(1_500L, 2_600L);
        assertThat(probe.container()).isEqualTo("mp4");
        assertThat(probe.videoCodec()).isEqualTo("H.264");
        assertThat(probe.width()).isEqualTo(160);
        assertThat(probe.height()).isEqualTo(120);
        assertThat(probe.audio()).singleElement().satisfies(audio -> {
            assertThat(audio.codec()).isEqualTo("AAC");
            assertThat(audio.channels()).isEqualTo("Mono");
            assertThat(audio.safariPlayable()).isTrue();
        });
        assertThat(probe.subtitles()).singleElement().satisfies(subtitle -> {
            assertThat(subtitle.format()).isEqualTo("mov_text");
            assertThat(subtitle.index()).isEqualTo(2);
        });
        assertThat(probe.protectedContent()).isFalse();
    }

    @Test
    void ffmpegGrabsAJpegStillAtTwelveSeconds() throws IOException {
        Path clip = library.resolve("Long Film.mp4");
        CommandRunner.Result made = RUNNER.run(List.of("ffmpeg", "-v", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=20:size=1920x1080:rate=10",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "10", "-f", "mp4", clip.toString()),
            Duration.ofSeconds(60));
        assertThat(made.succeeded()).as("ffmpeg generated the test clip").isTrue();
        gregochr.cinefilm.film.Edition edition = edition(film(1L, "Long Film", 2024, NOW), 3L, "Original", 20_000,
            null);
        MediaFile media = file(edition, "Long Film.mp4");
        media.setId(9L);
        gregochr.cinefilm.film.EditionRepository editions = mock(gregochr.cinefilm.film.EditionRepository.class);
        when(editions.findById(3L)).thenReturn(Optional.of(edition));
        gregochr.cinefilm.still.StillService service = new gregochr.cinefilm.still.StillService(editions,
            new LibraryRoot(library.toString()), RUNNER,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg",
            library.resolve("cache").toString());

        byte[] bytes = service.still(3L, 12_400L);

        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xD8);
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertThat(image.getWidth()).isLessThanOrEqualTo(1280);
    }

    @Test
    void anAnamorphicSourceKeepsItsTrueAspectInTheStill() throws IOException {
        Path clip = library.resolve("Anamorphic.mp4");
        CommandRunner.Result made = RUNNER.run(List.of("ffmpeg", "-v", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=20:size=720x576:rate=25", "-vf", "setsar=64/45",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", "-f", "mp4", clip.toString()),
            Duration.ofSeconds(60));
        assertThat(made.succeeded()).as("ffmpeg generated the anamorphic clip").isTrue();
        gregochr.cinefilm.film.Edition edition = edition(film(1L, "Anamorphic", 2024, NOW), 4L, "Original", 20_000,
            null);
        MediaFile media = file(edition, "Anamorphic.mp4");
        media.setId(10L);
        gregochr.cinefilm.film.EditionRepository editions = mock(gregochr.cinefilm.film.EditionRepository.class);
        when(editions.findById(4L)).thenReturn(Optional.of(edition));
        gregochr.cinefilm.still.StillService service = new gregochr.cinefilm.still.StillService(editions,
            new LibraryRoot(library.toString()), RUNNER,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg",
            library.resolve("cache").toString());

        byte[] jpeg = service.still(4L, 12_000L);

        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertThat(image.getWidth()).isLessThanOrEqualTo(1280);
        assertThat((double) image.getWidth() / image.getHeight()).isBetween(16.0 / 9 * 0.98, 16.0 / 9 * 1.02);
    }

    @Test
    void ffmpegExtractsTheSubtitleToWebVtt() throws IOException {
        clip();
        MediaFileRepository files = mock(MediaFileRepository.class);
        MediaFile media = file(edition(film(1L, "Test Film", 2024, NOW), 2L, "Original", 2_000, null),
            "Test Film.m4v");
        media.setId(9L);
        media.getSubtitleStreams().get(0).setIndex(2);
        when(files.findById(9L)).thenReturn(Optional.of(media));
        SubtitleService service = new SubtitleService(files, new LibraryRoot(library.toString()), RUNNER,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg",
            library.resolve("cache").toString());

        Path vtt = service.vtt(9L, 2);

        assertThat(Files.readString(vtt)).startsWith("WEBVTT").contains("00:00.000 --> 00:01.500")
            .contains("Hello from CineFilm");
    }
}
