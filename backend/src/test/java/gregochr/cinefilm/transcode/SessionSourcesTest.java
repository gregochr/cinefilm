package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import gregochr.cinefilm.transcode.SessionSources.Source;
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
class SessionSourcesTest {

    private static final long EDITION_ID = 40L;

    @TempDir
    Path library;

    @Mock
    private EditionRepository editions;

    private SessionSources sources;
    private Edition edition;

    @BeforeEach
    void setUp() {
        sources = new SessionSources(editions, new LibraryRoot(library.toString()));
        Film film = film(4L, "Heat", 1995, NOW);
        edition = edition(film, EDITION_ID, "Theatrical Cut", 100 * MIN, null);
    }

    private MediaFile original(String path) {
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));
        MediaFile original = file(edition, path);
        original.getAudioStreams().get(0).setId(100L);
        original.getAudioStreams().get(1).setId(101L);
        return original;
    }

    @Test
    void findsThePlayableOriginalWithItsPathAndAudioStreamsInOrder() throws IOException {
        Path film = Files.writeString(library.resolve("Heat.m4v"), "x");
        MediaFile original = original("Heat.m4v");

        Source source = sources.find(EDITION_ID);

        assertThat(source.editionId()).isEqualTo(EDITION_ID);
        assertThat(source.filmTitle()).isEqualTo("Heat");
        assertThat(source.file()).isSameAs(original);
        assertThat(source.path()).isEqualTo(film.toRealPath());
        assertThat(source.audio()).containsExactly(new AudioChoice(100, 0, true, "English"),
            new AudioChoice(101, 1, false, "English"));
    }

    @Test
    void theAudioPositionIsTheRankOfTheFileStreamIndexNotTheListOrder() throws IOException {
        Files.writeString(library.resolve("Heat.m4v"), "x");
        MediaFile original = original("Heat.m4v");
        original.getAudioStreams().get(0).setIndex(5);
        original.getAudioStreams().get(1).setIndex(2);

        Source source = sources.find(EDITION_ID);

        assertThat(source.audio()).containsExactly(new AudioChoice(100, 1, true, "English"),
            new AudioChoice(101, 0, false, "English"));
    }

    @Test
    void anUnknownEditionIs404() {
        when(editions.findById(555L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sources.find(555L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void anEditionWithoutAnOriginalFileIs404() throws IOException {
        Files.writeString(library.resolve("Heat.m4v"), "x");
        MediaFile premade = original("Heat.m4v");
        premade.setKind(MediaKind.PREMADE_720);

        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Edition has no original file");
    }

    @Test
    void aFileThatCannotBePlayedIs404() throws IOException {
        Files.writeString(library.resolve("Heat.m4v"), "x");
        original("Heat.m4v").setPlayability(Playability.PROTECTED);

        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void aFileMissingFromDiskOrOutsideTheLibraryIs404() {
        original("gone.m4v");

        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");

        edition.getMediaFiles().clear();
        original("../outside.m4v");
        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }
}
