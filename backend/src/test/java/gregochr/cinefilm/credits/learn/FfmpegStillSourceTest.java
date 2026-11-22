package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.FakeFfmpeg;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ffmpeg-backed still source against the stand-in for ffmpeg. */
class FfmpegStillSourceTest {

    private static final Path FILM = Path.of("/library/film.mkv");

    @TempDir
    Path cache;

    private final FakeFfmpeg ffmpeg = new FakeFfmpeg();

    private StillSource source() {
        return new FfmpegStillSource(new CreditsFrameReader(ffmpeg, "ffmpeg", 10, cache.toString()));
    }

    @Test
    void aFrameIsOneSeekAtTheFileTimeRunWithoutNice() {
        try (StillSource.Reading reading = source().open(FILM)) {
            assertThat(reading.frameAt(123_456)).isPresent();
        }

        assertThat(ffmpeg.commands).hasSize(1);
        assertThat(ffmpeg.commands.getFirst().getFirst()).isEqualTo("ffmpeg");
        assertThat(ffmpeg.commands.getFirst()).containsSequence("-ss", "123.456");
    }

    @Test
    void aSeekThatWritesNoFrameIsEmpty() {
        ffmpeg.writeNothing = true;
        try (StillSource.Reading reading = source().open(FILM)) {
            assertThat(reading.frameAt(9_000_000)).isEmpty();
        }
    }

    @Test
    void aFailingSeekIsAReadFailure() {
        ffmpeg.failOnCall = 1;
        try (StillSource.Reading reading = source().open(FILM)) {
            assertThatThrownBy(() -> reading.frameAt(1_000)).isInstanceOf(CreditsFrameReader.ReadException.class);
        }
    }

    @Test
    void aFullStillIsOneSeekThatWritesAPngAndReturnsItsBytes() {
        ffmpeg.garbage = new byte[] {1, 2, 3};
        try (StillSource.Reading reading = source().open(FILM)) {
            assertThat(reading.fullPngAt(5_000)).contains(new byte[] {1, 2, 3});
        }

        assertThat(ffmpeg.commands.getFirst()).containsSequence("-ss", "5.000").contains("png");
    }

    @Test
    void aFullStillPastTheEndIsEmpty() {
        ffmpeg.writeNothing = true;
        try (StillSource.Reading reading = source().open(FILM)) {
            assertThat(reading.fullPngAt(9_000_000)).isEmpty();
        }
    }

    @Test
    void closingTheReadingClosesTheJobAndDeletesItsDirectory() throws IOException {
        try (StillSource.Reading reading = source().open(FILM)) {
            reading.frameAt(1_000);
            try (var children = Files.list(cache.resolve("credits"))) {
                assertThat(children).hasSize(1);
            }
        }

        try (var children = Files.list(cache.resolve("credits"))) {
            assertThat(children).isEmpty();
        }
    }
}
