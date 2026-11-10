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
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.library.LibraryRoot;
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

/** What a session reads for an edition made of several files: every part, in order, with its timeline length. */
@ExtendWith(MockitoExtension.class)
class SessionSourcesPartsTest {

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
        edition = edition(film(4L, "Towers", 2002, NOW), EDITION_ID, "Extended", 0, null);
    }

    private MediaFile part(String path, int number, long id, long durationMs, Long videoDurationMs)
        throws IOException {
        Files.writeString(library.resolve(path), "x");
        MediaFile file = file(edition, path);
        file.setPartNumber(number);
        file.setId(id);
        file.setDurationMs(durationMs);
        file.setVideoDurationMs(videoDurationMs);
        file.getAudioStreams().get(0).setId(id * 10);
        file.getAudioStreams().get(1).setId(id * 10 + 1);
        return file;
    }

    @Test
    void everyPartIsReadInPlayOrderEachWithItsPathAudioAndVideoLength() throws IOException {
        part("pt2.m4v", 2, 2, 7_354_432, 7_354_347L);
        part("pt1.m4v", 1, 1, 6_354_460, 6_353_420L);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        Source source = sources.find(EDITION_ID);

        assertThat(source.parts()).hasSize(2);
        assertThat(source.parts().get(0).path()).isEqualTo(library.resolve("pt1.m4v").toRealPath());
        assertThat(source.parts().get(1).path()).isEqualTo(library.resolve("pt2.m4v").toRealPath());
        assertThat(source.parts()).extracting(SessionSources.Part::timelineMs)
            .containsExactly(6_353_420L, 7_354_347L);
        assertThat(source.parts().get(1).audio()).extracting(SessionSources.AudioChoice::id)
            .containsExactly(20L, 21L);
        assertThat(source.audio()).extracting(SessionSources.AudioChoice::id).containsExactly(10L, 11L);
        assertThat(source.file().getId()).isEqualTo(1L);
    }

    @Test
    void aPartWithNoVideoDurationFallsBackToTheContainerDuration() throws IOException {
        part("pt1.m4v", 1, 1, 6_354_460, null);
        part("pt2.m4v", 2, 2, 7_354_432, 0L);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        assertThat(sources.find(EDITION_ID).parts()).extracting(SessionSources.Part::timelineMs)
            .containsExactly(6_354_460L, 7_354_432L);
    }

    @Test
    void aPartWithNoDurationAtAllIsReadAsZeroForTheServiceToRefuse() throws IOException {
        part("pt1.m4v", 1, 1, 6_354_460, 6_353_420L);
        part("pt2.m4v", 2, 2, 7_354_432, null).setDurationMs(null);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        assertThat(sources.find(EDITION_ID).parts()).extracting(SessionSources.Part::timelineMs)
            .containsExactly(6_353_420L, 0L);
    }

    @Test
    void aFilmInOnePieceWithNoDurationIsReadAsZeroToo() throws IOException {
        part("Heat.m4v", 1, 1, 100 * MIN, null).setDurationMs(null);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        assertThat(sources.find(EDITION_ID).parts().get(0).timelineMs()).isZero();
    }

    @Test
    void aFilmInOnePieceUsesTheContainerDurationEvenWhenAVideoDurationIsStored() throws IOException {
        part("Heat.m4v", 1, 1, 100 * MIN, 99 * MIN);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        Source source = sources.find(EDITION_ID);

        assertThat(source.parts()).hasSize(1);
        assertThat(source.parts().get(0).timelineMs()).isEqualTo(100 * MIN);
        assertThat(source.partsMatch()).isTrue();
    }

    @Test
    void aMissingOrUnplayablePartMeansTheEditionIsNotAvailable() throws IOException {
        part("pt1.m4v", 1, 1, MIN, MIN);
        MediaFile second = part("pt2.m4v", 2, 2, MIN, MIN);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));
        second.setPlayability(Playability.UNREADABLE);

        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");

        second.setPlayability(Playability.PLAYABLE);
        Files.delete(library.resolve("pt2.m4v"));
        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Media file is not available");
    }

    @Test
    void partsThatDifferInSizeDoNotMatch() throws IOException {
        part("pt1.m4v", 1, 1, MIN, MIN);
        part("pt2.m4v", 2, 2, MIN, MIN).setHeight(720);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        assertThat(sources.find(EDITION_ID).partsMatch()).isFalse();
    }

    @Test
    void partsThatAreAlikeMatch() throws IOException {
        part("pt1.m4v", 1, 1, MIN, MIN);
        part("pt2.m4v", 2, 2, MIN, MIN);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        assertThat(sources.find(EDITION_ID).partsMatch()).isTrue();
        assertThat(sources.find(EDITION_ID).parts().get(0).audio().get(0).language()).isEqualTo("English");
    }

    @Test
    void anEditionWithNoOriginalFileIs404() {
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));

        assertThatThrownBy(() -> sources.find(EDITION_ID)).isInstanceOf(NotFoundException.class)
            .hasMessage("Edition has no original file");
    }
}
