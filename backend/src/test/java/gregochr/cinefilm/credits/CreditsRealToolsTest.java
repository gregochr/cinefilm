package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsFrameReader.Job;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.ProcessCommandRunner;
import gregochr.cinefilm.subtitle.EmbeddedCueStartsRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The real {@code ffmpeg}: makes an 8 minute clip of plain colour (6 minutes) followed by a black background with
 * white blocks of text scrolling up (2 minutes), reads it with the real {@link CreditsFrameReader} and analyses it with
 * {@link CreditsDetectionService#detect}. CI installs ffmpeg and runs this; it is skipped (not failed) where ffmpeg is
 * not on the PATH, as on the dev Mac.
 *
 * <p>The crawl is drawn by this test as PGM frames (rows of blocks that scroll 10 pixels each half second, every line a
 * different width) and encoded with {@code libx264}, instead of with {@code drawtext}: that filter needs
 * {@code libfreetype} and a font, which a minimal ffmpeg build does not have, and a clip made of files needs only what
 * the existing real-tools test already relies on. The clip is made at two frames a second, the rate of the dense pass,
 * so every decoded dense frame is exactly one source frame and the crawl is steady.
 */
@Tag("real-tools")
class CreditsRealToolsTest {

    private static final ProcessCommandRunner RUNNER = new ProcessCommandRunner();
    private static final int WIDTH = 480;
    private static final int HEIGHT = 270;
    private static final int TEXT = 235;
    private static final int PICTURE_SECONDS = 360;
    private static final int CREDITS_SECONDS = 120;
    private static final long CLIP_MS = (PICTURE_SECONDS + CREDITS_SECONDS) * 1000L;
    /** The crawl moves this many pixels every half second (20 pixels a second). */
    private static final int SHIFT = 10;

    @TempDir
    Path dir;

    @BeforeAll
    static void ffmpegIsInstalled() {
        assumeTrue(runs("ffmpeg"), "ffmpeg is not on the PATH");
    }

    private static boolean runs(String tool) {
        try {
            return RUNNER.run(List.of(tool, "-version"), Duration.ofSeconds(20)).succeeded();
        } catch (IOException e) {
            return false;
        }
    }

    /** One frame of the crawl: a text line every 24 pixels, each its own width, rising {@link #SHIFT} pixels. */
    private static GreyFrame crawlFrame(int index) {
        byte[] pixels = new byte[WIDTH * HEIGHT];
        for (int line = 0; line < 400; line++) {
            int top = 10 + 24 * line - index * SHIFT;
            if (top >= HEIGHT) {
                break;
            }
            if (top + 8 <= 0) {
                continue;
            }
            int blocks = 10 + line * 37 % 9;
            int left = (WIDTH - blocks * 14) / 2 + line * 53 % 21 - 10;
            for (int block = 0; block < blocks; block++) {
                for (int y = Math.max(0, top); y < Math.min(HEIGHT, top + 8); y++) {
                    for (int x = left + block * 14; x < left + block * 14 + 8; x++) {
                        pixels[y * WIDTH + x] = (byte) TEXT;
                    }
                }
            }
        }
        return new GreyFrame(WIDTH, HEIGHT, pixels);
    }

    /** The clip: plain colour for {@code pictureSeconds}, then {@code creditsSeconds} of crawl (none when 0). */
    private Path clip(int pictureSeconds, int creditsSeconds) throws IOException {
        Path frames = Files.createDirectories(dir.resolve("frames"));
        for (int i = 0; i < creditsSeconds * 2; i++) {
            Files.write(frames.resolve("c_%04d.pgm".formatted(i)), FakeFfmpeg.pgm(crawlFrame(i)));
        }
        Path clip = dir.resolve("Test Film.mkv");
        List<String> command = creditsSeconds == 0
            ? List.of("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
                "color=c=0x6080a0:s=480x270:r=2:d=" + pictureSeconds, "-c:v", "libx264", "-crf", "10", "-g", "4",
                "-pix_fmt", "yuv420p", clip.toString())
            : List.of("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
                "color=c=0x6080a0:s=480x270:r=2:d=" + pictureSeconds, "-framerate", "2", "-i",
                frames.resolve("c_%04d.pgm").toString(), "-filter_complex",
                "[0:v]setsar=1,fps=2,format=yuv420p[a];[1:v]setsar=1,fps=2,format=yuv420p[b];"
                    + "[a][b]concat=n=2:v=1:a=0[v]", "-map", "[v]", "-c:v", "libx264", "-crf", "10", "-g", "4",
                "-pix_fmt", "yuv420p", clip.toString());
        CommandRunner.Result made = RUNNER.run(command, Duration.ofSeconds(300));
        assertThat(made.succeeded()).as("ffmpeg generated the test clip").isTrue();
        return clip;
    }

    private CreditsFrameReader reader() {
        return new CreditsFrameReader(RUNNER, "ffmpeg", 0, dir.resolve("cache").toString());
    }

    private CreditsDetectionService detection() {
        // Only the reader and the cue starts are used by detect(); a read of the cue starts finds nothing.
        return new CreditsDetectionService(null, null, null, mock(EmbeddedCueStartsRepository.class), null, reader(),
            null, mock(PlatformTransactionManager.class));
    }

    @Test
    void framesAreWrittenAsFourHundredAndEightyPixelWideGreyscalePictures() throws IOException {
        Path clip = clip(PICTURE_SECONDS, CREDITS_SECONDS);

        try (Job job = reader().open(clip, CLIP_MS, () -> { })) {
            List<GreyFrame> active = job.activeAreaFrames();
            List<DenseFrame> dense = job.denseFrames(363_000);

            assertThat(active).hasSize(5).allSatisfy(frame -> {
                assertThat(frame.width()).isEqualTo(WIDTH);
                assertThat(frame.height()).isEqualTo(HEIGHT);
            });
            // Plain colour has a mid luma, not black or white.
            assertThat(active.getFirst().luma(10, 10)).isBetween(90, 160);
            assertThat(dense).hasSizeBetween(358, 360);
            assertThat(dense.getFirst().offsetMs()).isEqualTo(273_000);
            assertThat(dense.get(1).offsetMs()).isEqualTo(273_500);
            // Index 100 is 323 s, before the credits: plain colour. Index 200 is 373 s, inside the crawl.
            assertThat(dense.get(100).frame().luma(0, 0)).isBetween(90, 160);
            boolean white = false;
            GreyFrame crawl = dense.get(200).frame();
            for (int y = 0; y < crawl.height() && !white; y++) {
                for (int x = 0; x < crawl.width() && !white; x++) {
                    white = crawl.luma(x, y) > 200;
                }
            }
            assertThat(white).isTrue();
        }
        assertThat(dir.resolve("cache").resolve("credits")).isEmptyDirectory();
    }

    @Test
    void creditsCrawlingUpAfterSixMinutesOfPictureAreFoundNearSixMinutes() throws IOException {
        Path clip = clip(PICTURE_SECONDS, CREDITS_SECONDS);
        DueFile due = new DueFile(1L, "Test Film.mkv", "1:1", CLIP_MS, "Test Film");

        CreditsResult result = detection().detect(due, clip, () -> { });

        assertThat(result.verdict()).as(String.valueOf(result.reasons())).isEqualTo(CreditsVerdict.FOUND);
        assertThat(result.offsetMs()).isBetween(358_000L, 366_000L);
        assertThat(dir.resolve("cache").resolve("credits")).isEmptyDirectory();
    }

    @Test
    void aFilmThatEndsOnPlainPictureIsNotFound() throws IOException {
        Path clip = clip(480, 0);
        DueFile due = new DueFile(1L, "Test Film.mkv", "1:1", CLIP_MS, "Test Film");

        CreditsResult result = detection().detect(due, clip, () -> { });

        assertThat(result.verdict()).as(String.valueOf(result.reasons())).isEqualTo(CreditsVerdict.NOT_FOUND);
        assertThat(result.offsetMs()).isNull();
    }
}
