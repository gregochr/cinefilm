package gregochr.cinefilm.stream;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StreamServiceTest {

    private static final long EDITION_ID = 40L;

    @TempDir
    Path dir;

    @Mock
    private EditionRepository editions;

    private StreamService service;
    private Edition edition;

    @BeforeEach
    void setUp() {
        service = new StreamService(editions, new LibraryRoot(dir.toString()));
        Film film = film(4L, "Heat", 1995, NOW);
        edition = edition(film, EDITION_ID, "Theatrical Cut", 100 * MIN, null);
    }

    private void known() {
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));
    }

    private MediaFile original(String path) {
        known();
        return file(edition, path);
    }

    @Test
    void anUnknownEditionIsNotFound() {
        assertThatThrownBy(() -> service.originalPath(404L))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void anEditionWithoutAnOriginalFileIsNotFound() {
        known();
        MediaFile premade = file(edition, "t.mp4");
        premade.setKind(MediaKind.PREMADE_720);

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Edition has no original file");
    }

    @Test
    void aReadableRegularFileIsResolvedUnderTheLibraryRoot() throws IOException {
        Path video = Files.writeString(dir.resolve("heat.mp4"), "frames");
        original("heat.mp4");

        assertThat(service.originalPath(EDITION_ID).toRealPath()).isEqualTo(video.toRealPath());
    }

    @Test
    void aMissingFileOnDiskIsNotFound() {
        original("gone.mp4");

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void aDirectoryIsNotServed() {
        original(".");

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void anUnreadableFileIsNotServed() throws IOException {
        Path video = Files.writeString(dir.resolve("locked.mp4"), "frames");
        assertThat(video.toFile().setReadable(false)).isTrue();
        assumeFalse(Files.isReadable(video), "running as a user that ignores file permissions");
        original("locked.mp4");

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void aPathThatIsNotAValidFileNameIsNotFoundRatherThanAServerError() {
        original("bad\0name.mp4");

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void aParentTraversalOutOfTheRootIsNotFoundEvenWhenTheFileExists() throws IOException {
        Path outside = Files.writeString(dir.resolveSibling(dir.getFileName() + "-secret.mp4"), "secret");
        try {
            original("../" + outside.getFileName());

            assertThatThrownBy(() -> service.originalPath(EDITION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Media file is not available");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void anAbsolutePathIsNotFoundEvenWhenTheFileExists() throws IOException {
        Path video = Files.writeString(dir.resolve("abs.mp4"), "frames");
        original(video.toString());

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void aSymlinkPointingOutOfTheRootIsNotFound() throws IOException {
        Path outside = Files.writeString(dir.resolveSibling(dir.getFileName() + "-target.mp4"), "secret");
        try {
            Files.createSymbolicLink(dir.resolve("link.mp4"), outside);
            original("link.mp4");

            assertThatThrownBy(() -> service.originalPath(EDITION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Media file is not available");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aFileThatCannotBePlayedIsNotServed() throws IOException {
        Files.writeString(dir.resolve("drm.m4v"), "protected");
        original("drm.m4v").setPlayability(Playability.PROTECTED);

        assertThatThrownBy(() -> service.originalPath(EDITION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }
}
