package gregochr.cinefilm.subtitle;

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
import gregochr.cinefilm.film.FilmDtos.FullSubtitleDto;
import gregochr.cinefilm.film.MediaFile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Which full English subtitle an edition offers and how it is written, with the repositories faked. */
@ExtendWith(MockitoExtension.class)
class FullSubtitleServiceUnitTest {

    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private EditionRepository editions;

    private FullSubtitleService service;
    private Edition edition;
    private MediaFile first;
    private MediaFile second;

    @BeforeEach
    void setUp() {
        service = new FullSubtitleService(editions, sidecars);
        edition = edition(film(1L, "Towers", 2002, NOW), 40L, "Original", 120 * MIN, null);
        first = part(10L, 1);
        second = part(20L, 2);
    }

    private MediaFile part(long id, int number) {
        MediaFile file = file(edition, "Towers - pt" + number + ".m4v");
        file.setId(id);
        file.setPartNumber(number);
        file.setDurationMs(60 * MIN);
        return file;
    }

    private static SubtitleSidecar full(long id, long fileId, boolean whole, int offsetMs) {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setId(id);
        sidecar.setKind(SubtitleKind.FULL);
        sidecar.setMediaFileId(fileId);
        sidecar.setWholeEdition(whole);
        sidecar.setCuesText(CUES);
        sidecar.setCueCount(1);
        sidecar.setOffsetMs(offsetMs);
        sidecar.setUpdatedAt(NOW.plusSeconds(id));
        return sidecar;
    }

    private void stubFound(SubtitleSidecar... rows) {
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L, 20L), SubtitleKind.FULL)).thenReturn(List.of(rows));
    }

    @Test
    void aWholeEditionFileIsWrittenOnTheEditionTimelineWithItsOffset() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        stubFound(full(1, 10L, true, 500));

        assertThat(service.vtt(40L)).isEqualTo("WEBVTT\n\n00:00:10.500 --> 00:00:12.500\nHello\n");
    }

    @Test
    void perPartFilesAreMovedByTheirPartsStartAndJoined() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        stubFound(full(1, 10L, false, 0), full(2, 20L, false, -3000));

        assertThat(service.vtt(40L)).isEqualTo("WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
            + "01:00:07.000 --> 01:00:09.000\nHello\n");
    }

    @Test
    void aWholeEditionFileBeatsPerPartFiles() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        SubtitleSidecar whole = full(3, 10L, true, 0);
        whole.setCuesText(CUES.replace("Hello", "Whole"));
        stubFound(full(1, 10L, false, 0), full(2, 20L, false, 0), whole);

        assertThat(service.vtt(40L)).isEqualTo("WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nWhole\n");
    }

    @Test
    void theTextIsEscapedAsInTheForcedRoute() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        SubtitleSidecar row = full(1, 10L, true, 0);
        row.setCuesText("1\n00:00:10,000 --> 00:00:12,000\nTom & Jerry > 3\n\n");
        stubFound(row);

        assertThat(service.vtt(40L)).contains("Tom &amp; Jerry &gt; 3");
    }

    @Test
    void twoFilesForOnePlaceAreAConflictAndNeitherPlays() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        stubFound(full(1, 10L, false, 0), full(2, 10L, false, 0));

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No full subtitles");
        assertThat(service.forEditions(List.of(edition))).isEmpty();
    }

    @Test
    void aForcedRowAmongTheFilesIsNeverServedAsFull() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        SubtitleSidecar forced = full(1, 10L, true, 0);
        forced.setKind(SubtitleKind.FORCED);
        stubFound(forced);

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No full subtitles");
    }

    @Test
    void aDownloadIsNeverServedAsFullYet() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        SubtitleSidecar download = full(1, 10L, true, 0);
        download.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        stubFound(download);

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No full subtitles");
    }

    @Test
    void nothingAttachedOrNoPartFilesIsNotFound() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        stubFound();

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No full subtitles");

        edition.getMediaFiles().clear();
        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No full subtitles");
    }

    @Test
    void anUnknownEditionIsNotFound() {
        when(editions.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vtt(9L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void aLaterPartWithNoTimelineIsNotAvailable() {
        first.setDurationMs(null);
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        stubFound(full(2, 20L, false, 0));

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("Full subtitles are not available");
        assertThat(service.forEditions(List.of(edition))).isEmpty();
    }

    @Test
    void theDetailOffersTheOwnersFileWithAVersionThatChangesWithTheOffsetAndTheFile() {
        stubFound(full(1, 10L, false, 0), full(2, 20L, false, 0));

        Map<Long, FullSubtitleDto> before = service.forEditions(List.of(edition));
        SubtitleSidecar nudged = full(1, 10L, false, 250);
        stubFound(nudged, full(2, 20L, false, 0));
        Map<Long, FullSubtitleDto> after = service.forEditions(List.of(edition));

        assertThat(before.get(40L).source()).isEqualTo("SIDECAR");
        assertThat(after.get(40L).version()).isNotEqualTo(before.get(40L).version());
        assertThat(service.forEditions(List.of(edition)).get(40L)).isEqualTo(after.get(40L));
    }

    @Test
    void anEditionWithNoFilesGetsNoEntryAndNothingIsQueriedForNoParts() {
        edition.getMediaFiles().clear();

        assertThat(service.forEditions(List.of(edition))).isEmpty();
    }
}
