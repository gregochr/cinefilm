package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SubtitleServiceTest {

    private static final long FILE_ID = 7L;
    private static final String VTT = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHello\n";

    @TempDir
    Path base;

    private Path library;
    private Path cache;
    private Path video;
    private MediaFileRepository files;
    private CommandRunner runner;
    private SubtitleService service;
    private MediaFile mediaFile;

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectory(base.resolve("library"));
        cache = base.resolve("cache");
        video = Files.writeString(library.resolve("Heat.m4v"), "0123456789");
        files = mock(MediaFileRepository.class);
        runner = mock(CommandRunner.class);
        service = new SubtitleService(files, new LibraryRoot(library.toString()), runner,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), "ffmpeg", cache.toString());
        mediaFile = file(edition(film(1L, "Heat", 1995, NOW), 2L, "Original", 100, null), "Heat.m4v");
        mediaFile.setId(FILE_ID);
        mediaFile.setSizeBytes(10L);
        mediaFile.setModifiedAt(Instant.ofEpochMilli(1_700_000_000_123L));
        mediaFile.getSubtitleStreams().get(0).setIndex(3);
        when(files.findById(FILE_ID)).thenReturn(Optional.of(mediaFile));
    }

    private void ffmpegWrites(String content) throws IOException {
        when(runner.run(any(), eq(SubtitleService.TIMEOUT))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Files.writeString(Path.of(command.get(command.size() - 1)), content);
            return new CommandRunner.Result(0, "", false);
        });
    }

    @Test
    void theCommandIsAnArgumentListWithEachPathAsOneElementAndAnIntegerStreamNumber() {
        assertThat(SubtitleService.command("/usr/bin/ffmpeg", Path.of("/media/a; rm -rf ~.m4v"), 3,
            Path.of("/cache/out.tmp"))).containsExactly("/usr/bin/ffmpeg", "-v", "error", "-nostdin", "-y", "-i",
                "/media/a; rm -rf ~.m4v", "-map", "0:3", "-f", "webvtt", "/cache/out.tmp");
    }

    @Test
    void extractsTheStreamToWebVttOnFirstRequestAndReturnsTheCachedFile() throws IOException {
        ffmpegWrites(VTT);

        Path vtt = service.vtt(FILE_ID, 3);

        assertThat(vtt).hasContent(VTT);
        assertThat(vtt.getParent()).isEqualTo(cache.toAbsolutePath().resolve("subtitles"));
        assertThat(vtt.getFileName().toString()).isEqualTo("7-3-10-1700000000123.vtt");
        ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
        verify(runner).run(command.capture(), eq(SubtitleService.TIMEOUT));
        assertThat(command.getValue().subList(0, 9)).containsExactly("ffmpeg", "-v", "error", "-nostdin", "-y", "-i",
            video.toRealPath().toString(), "-map", "0:3");
        assertThat(SubtitleService.TIMEOUT.toSeconds()).isEqualTo(60);
    }

    @Test
    void aSecondRequestIsServedFromTheCacheWithoutRunningFfmpeg() throws IOException {
        ffmpegWrites(VTT);

        Path first = service.vtt(FILE_ID, 3);
        Path second = service.vtt(FILE_ID, 3);

        assertThat(second).isEqualTo(first);
        verify(runner, times(1)).run(any(), any());
    }

    @Test
    void aChangedFileIsExtractedAgainBecauseItsSizeAndTimeAreInTheCacheName() throws IOException {
        ffmpegWrites(VTT);
        service.vtt(FILE_ID, 3);
        mediaFile.setSizeBytes(11L);

        Path again = service.vtt(FILE_ID, 3);

        assertThat(again.getFileName().toString()).isEqualTo("7-3-11-1700000000123.vtt");
        verify(runner, times(2)).run(any(), any());
    }

    @Test
    void aFileWithNoRecordedSizeOrTimeStillGetsACacheName() throws IOException {
        mediaFile.setSizeBytes(null);
        mediaFile.setModifiedAt(null);
        ffmpegWrites(VTT);

        assertThat(service.vtt(FILE_ID, 3).getFileName().toString()).isEqualTo("7-3-0-0.vtt");
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws IOException {
        ffmpegWrites(VTT);

        Path vtt = service.vtt(FILE_ID, 3);

        try (var listing = Files.list(vtt.getParent())) {
            assertThat(listing.map(p -> p.getFileName().toString())).containsExactly(vtt.getFileName().toString());
        }
    }

    @Test
    void anUnknownMediaFileIsNotFoundAndNeverRunsFfmpeg() throws IOException {
        assertThatThrownBy(() -> service.vtt(99L, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such media file");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void aStreamIndexThatIsNotInTheDatabaseIsNotFoundEvenIfTheFileHasIt() throws IOException {
        assertThatThrownBy(() -> service.vtt(FILE_ID, 4)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle stream");
        assertThatThrownBy(() -> service.vtt(FILE_ID, -1)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle stream");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void aSourceThatHasGoneFromDiskIsNotFound() throws IOException {
        Files.delete(video);

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void aSourcePathThatEscapesTheLibraryRootIsNeverPassedToFfmpeg() throws IOException {
        Files.writeString(base.resolve("secret.m4v"), "secret");
        mediaFile.setPath("../secret.m4v");

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void aFailedExtractionIsNotFoundAndNothingIsCached() throws IOException {
        when(runner.run(any(), any())).thenReturn(new CommandRunner.Result(1, "", false));

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
        try (var listing = Files.list(cache.resolve("subtitles"))) {
            assertThat(listing).isEmpty();
        }
    }

    @Test
    void aFailedFfmpegThatLeftAPartialFileBehindIsStillNotFoundAndNothingIsCached() throws IOException {
        when(runner.run(any(), eq(SubtitleService.TIMEOUT))).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Files.writeString(Path.of(command.get(command.size() - 1)), "WEBVTT\n\npartial");
            return new CommandRunner.Result(1, "", false);
        });

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
        try (var listing = Files.list(cache.resolve("subtitles"))) {
            assertThat(listing).isEmpty();
        }
    }

    @Test
    void aTimedOutExtractionIsNotFound() throws IOException {
        when(runner.run(any(), any())).thenReturn(new CommandRunner.Result(-1, "", true));

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
    }

    @Test
    void aSuccessfulExitThatWroteNothingIsNotFound() throws IOException {
        when(runner.run(any(), any())).thenReturn(new CommandRunner.Result(0, "", false));

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
    }

    @Test
    void ffmpegNotBeingInstalledIsNotFound() throws IOException {
        when(runner.run(any(), any())).thenThrow(new IOException("Cannot run program \"ffmpeg\""));

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
    }

    @Test
    void aCacheFolderThatCannotBeCreatedIsNotFound() throws IOException {
        Files.writeString(cache, "a file where the cache folder should be");

        assertThatThrownBy(() -> service.vtt(FILE_ID, 3)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
    }
}
