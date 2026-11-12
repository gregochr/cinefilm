package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundSidecar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scan's sidecar sync never touches a subtitle downloaded from OpenSubtitles: it has a made-up path and no file on
 * disk, so a scan that did not find it must not mark it missing, and the name matcher must not move it.
 */
class SidecarSyncDownloadsTest extends ForcedDownloadTestBase {

    private static final Instant T1 = Instant.parse("2023-05-01T10:00:00Z");

    @TempDir
    Path library;

    private SidecarSync sync;

    @BeforeEach
    void setUp() {
        sync = new SidecarSync(sidecars, mediaFiles, new LibraryRoot(library.toString()), CLOCK);
    }

    private static Found found(FoundSidecar... files) {
        return new Found(List.of(), 0, List.of(), List.of(files));
    }

    private SubtitleSidecar reloaded(SubtitleSidecar row) {
        return sidecars.findById(row.getId()).orElseThrow();
    }

    @Test
    void aScanThatFindsNoFilesLeavesADownloadPresentAndExactlyAsItWas() {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.2008"));
        SubtitleSidecar download = download(film, 5011);
        sync.sync(found());

        SubtitleSidecar after = reloaded(download);

        assertThat(after.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(after.getMediaFileId()).isEqualTo(fileId(film, 1));
        assertThat(after.isWholeEdition()).isTrue();
        assertThat(after.getOrigin()).isEqualTo(SubtitleSidecar.Origin.OPENSUBTITLES);
        assertThat(after.getCuesText()).isEqualTo(CUES);
        assertThat(after.getUpdatedAt()).isEqualTo(download.getUpdatedAt());
        assertThat(sidecars.count()).isEqualTo(1);
    }

    @Test
    void aScanDoesNotRematchADownloadEvenWhenAFileBesideTheFilmMatchesTheSameFilm() throws IOException {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.2008"));
        SubtitleSidecar download = download(film, 5011);
        byte[] content = ("1\n00:00:10,000 --> 00:00:12,000\nHello\n").getBytes(StandardCharsets.UTF_8);
        Path file = library.resolve("Taken.en.forced.srt");
        Files.write(file, content);
        Files.setLastModifiedTime(file, FileTime.from(T1));

        sync.sync(found(new FoundSidecar("Taken.en.forced.srt", content.length, T1)));

        assertThat(sidecars.count()).isEqualTo(2);
        SubtitleSidecar owners = sidecars.findAll().stream().filter(s -> !s.isDownload()).findFirst().orElseThrow();
        assertThat(owners.getMediaFileId()).isEqualTo(fileId(film, 1));
        assertThat(owners.isWholeEdition()).isFalse();
        SubtitleSidecar after = reloaded(download);
        assertThat(after.isWholeEdition()).isTrue();
        assertThat(after.getPresence()).isEqualTo(Presence.PRESENT);
        assertThat(after.getUpdatedAt()).isEqualTo(download.getUpdatedAt());
    }

    @Test
    void aDownloadStaysWhateverTheScanFindsAndWhateverItDoesNotAndAFileThatWentIsStillMarkedMissing() {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.2008"));
        SubtitleSidecar download = download(film, 5011);
        SubtitleSidecar gone = sidecar("Gone.en.forced.srt", null, false);

        sync.sync(found());
        sync.sync(found());

        assertThat(reloaded(gone).getPresence()).isEqualTo(Presence.MISSING);
        assertThat(reloaded(download).getPresence()).isEqualTo(Presence.PRESENT);
    }

    @Test
    void aDownloadSurvivesASyncEvenWhenTheFolderItsMadeUpPathWouldBeInCouldNotBeRead() {
        Film film = searchedFilm("Taken", 1, result(5011, "Taken.2008"));
        SubtitleSidecar download = download(film, 5011);
        Found unreadable = new Found(List.of(), 0, List.of("opensubtitles:"), List.of());

        sync.sync(unreadable);

        assertThat(reloaded(download).getPresence()).isEqualTo(Presence.PRESENT);
    }
}
