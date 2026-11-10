package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An edition's subtitles as one file: part 1's chosen stream, each later part's matching stream, shifted. */
class EditionSubtitleServiceTest {

    private static final long EDITION_ID = 40L;

    @TempDir
    Path dir;

    private final EditionRepository editions = mock(EditionRepository.class);
    private final SubtitleService subtitles = mock(SubtitleService.class);
    private EditionSubtitleService service;
    private Edition edition;

    @BeforeEach
    void setUp() {
        service = new EditionSubtitleService(editions, subtitles,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
        edition = edition(film(4L, "Towers", 2002, NOW), EDITION_ID, "Extended", 0, null);
        when(editions.findById(EDITION_ID)).thenReturn(Optional.of(edition));
    }

    private MediaFile part(long id, int number, Long durationMs, Long videoDurationMs) {
        MediaFile file = file(edition, "pt" + number + ".m4v");
        file.setId(id);
        file.setPartNumber(number);
        file.setDurationMs(durationMs);
        file.setVideoDurationMs(videoDurationMs);
        file.getSubtitleStreams().get(0).setIndex(2);
        return file;
    }

    private Path vtt(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text);
    }

    @Test
    void aFilmInOnePieceIsServedExactlyAsItsFilesExtraction() throws IOException {
        part(7, 1, 100_000L, null);
        when(subtitles.vtt(7L, 2)).thenReturn(vtt("a.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCafé\n"));

        assertThat(service.vtt(EDITION_ID, 2)).isEqualTo("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCafé\n");
    }

    @Test
    void theCuesOfALaterPartAreShiftedByTheVideoLengthOfThePartsBeforeIt() throws IOException {
        part(7, 1, 7_000L, 6_000L);
        MediaFile second = part(8, 2, 9_000L, null);
        second.getSubtitleStreams().get(0).setIndex(5);
        when(subtitles.vtt(7L, 2)).thenReturn(vtt("a.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n"));
        when(subtitles.vtt(8L, 5)).thenReturn(vtt("b.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nTwo\n"));

        assertThat(service.vtt(EDITION_ID, 2)).isEqualTo(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n\n00:00:07.000 --> 00:00:08.000\nTwo\n");
    }

    @Test
    void aLaterPartWithNoMatchingStreamContributesNothing() throws IOException {
        part(7, 1, 6_000L, 6_000L);
        MediaFile second = part(8, 2, 9_000L, 9_000L);
        second.getSubtitleStreams().clear();
        when(subtitles.vtt(7L, 2)).thenReturn(vtt("a.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n"));

        assertThat(service.vtt(EDITION_ID, 2)).isEqualTo("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nOne\n");
        verify(subtitles, never()).vtt(8L, 2);
    }

    @Test
    void aStreamPartOneDoesNotHaveIs404() {
        part(7, 1, 6_000L, 6_000L);

        assertThatThrownBy(() -> service.vtt(EDITION_ID, 9)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such subtitle stream");
    }

    @Test
    void anUnknownEditionAndAnEditionWithoutFilesAre404() {
        when(editions.findById(555L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vtt(555L, 2)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThatThrownBy(() -> service.vtt(EDITION_ID, 2)).isInstanceOf(NotFoundException.class)
            .hasMessage("Edition has no original file");
    }

    @Test
    void aSplitEditionWhosePartLacksADurationHasNoSubtitles() {
        part(7, 1, 6_000L, 6_000L);
        part(8, 2, null, null);

        assertThatThrownBy(() -> service.vtt(EDITION_ID, 2)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles are not available");
    }

    @Test
    void anExtractionThatCannotBeReadBackIs404() {
        part(7, 1, 6_000L, 6_000L);
        when(subtitles.vtt(7L, 2)).thenReturn(dir.resolve("gone.vtt"));

        assertThatThrownBy(() -> service.vtt(EDITION_ID, 2)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
    }

    @Test
    void anExtractionFailureOfAnyPartPropagates() throws IOException {
        part(7, 1, 6_000L, 6_000L);
        part(8, 2, 9_000L, 9_000L);
        when(subtitles.vtt(7L, 2)).thenReturn(vtt("a.vtt", "WEBVTT\n"));
        when(subtitles.vtt(8L, 2)).thenThrow(new NotFoundException("Subtitles could not be extracted"));

        assertThatThrownBy(() -> service.vtt(EDITION_ID, 2)).isInstanceOf(NotFoundException.class)
            .hasMessage("Subtitles could not be extracted");
    }
}
