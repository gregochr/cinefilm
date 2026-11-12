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
import gregochr.cinefilm.film.FilmDtos.ForcedSubtitleDto;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Which forced subtitle an edition plays and how it is written, with the repositories faked. */
@ExtendWith(MockitoExtension.class)
class ForcedSubtitleServiceUnitTest {

    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private EditionRepository editions;

    @Mock
    private ForcedSubtitleCheckRepository checks;

    @Mock
    private ForcedDownloadCheckRepository downloadChecks;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private SidecarTimingService timing;

    private ForcedSubtitleService service;
    private Edition edition;
    private MediaFile first;
    private MediaFile second;

    @BeforeEach
    void setUp() {
        service = new ForcedSubtitleService(editions,
            new ForcedResolver(sidecars, checks, downloadChecks, references, timing));
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

    private static SubtitleSidecar sidecar(long id, long fileId, boolean whole, int offsetMs) {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setId(id);
        sidecar.setMediaFileId(fileId);
        sidecar.setWholeEdition(whole);
        sidecar.setCuesText(CUES);
        sidecar.setCueCount(1);
        sidecar.setOffsetMs(offsetMs);
        sidecar.setUpdatedAt(NOW.plusSeconds(id));
        return sidecar;
    }

    @Test
    void aWholeEditionFileIsWrittenOnTheEditionTimelineWithItsOffset() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        when(sidecars.findByMediaFileIdIn(List.of(10L, 20L))).thenReturn(List.of(sidecar(1, 10L, true, 500)));

        assertThat(service.vtt(40L)).isEqualTo("WEBVTT\n\n00:00:10.500 --> 00:00:12.500\nHello\n");
    }

    @Test
    void perPartFilesAreMovedByTheirPartsStartAndJoined() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        when(sidecars.findByMediaFileIdIn(List.of(10L, 20L)))
            .thenReturn(List.of(sidecar(1, 10L, false, 0), sidecar(2, 20L, false, -3000)));

        assertThat(service.vtt(40L)).isEqualTo("WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
            + "01:00:07.000 --> 01:00:09.000\nHello\n");
    }

    @Test
    void nothingAttachedOrNoFilesIsNotFound() {
        when(editions.findById(40L)).thenReturn(Optional.of(edition));
        when(sidecars.findByMediaFileIdIn(List.of(10L, 20L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No forced subtitles");

        edition.getMediaFiles().clear();
        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No forced subtitles");
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
        when(sidecars.findByMediaFileIdIn(List.of(10L, 20L))).thenReturn(List.of(sidecar(2, 20L, false, 0)));

        assertThatThrownBy(() -> service.vtt(40L)).isInstanceOf(NotFoundException.class)
            .hasMessage("Forced subtitles are not available");
    }

    @Test
    void theDetailOffersTheLatestChangeAsTheVersionAndVerifiedOnlyWhenEveryFileIs() {
        SubtitleSidecar a = sidecar(1, 10L, false, 0);
        SubtitleSidecar b = sidecar(2, 20L, false, 0);
        a.setOwnerVerifiedAt(NOW);
        a.setVerifiedParts("10,20");
        when(sidecars.findByMediaFileIdIn(List.of(10L, 20L))).thenReturn(List.of(a, b));

        Map<Long, ForcedSubtitleDto> unverified = service.forEditions(List.of(edition));

        assertThat(unverified.get(40L).source()).isEqualTo("SIDECAR");
        assertThat(unverified.get(40L).ownerVerified()).isFalse();

        b.setOwnerVerifiedAt(NOW);
        b.setVerifiedParts("10,20");
        assertThat(service.forEditions(List.of(edition)).get(40L).ownerVerified()).isTrue();
    }

    @Test
    void editionsWithNothingToPlayOrNoFilesAreAbsent() {
        Edition empty = edition(film(2L, "Empty", 2000, NOW), 41L, "Original", MIN, null);
        when(sidecars.findByMediaFileIdIn(List.of(10L, 20L))).thenReturn(List.of());

        assertThat(service.forEditions(List.of(edition, empty))).isEmpty();
        assertThat(service.forEditions(List.of(empty))).isEmpty();
    }
}
