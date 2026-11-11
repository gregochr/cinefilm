package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.ForcedSubtitleDto;
import gregochr.cinefilm.film.Presence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which forced subtitle an edition plays and the WebVTT it is served as, against the real repositories on H2. */
class ForcedSubtitleServiceTest extends SubtitleTestBase {

    private static final String HELLO_WORLD = "WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
        + "00:00:20.000 --> 00:00:22.500\nWorld\n";

    @Test
    void aSingleFileEditionPlaysItsSidecarAsWebVtt() {
        Film film = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        assertThat(forced.vtt(film.getEditions().get(0).getId())).isEqualTo(HELLO_WORLD);
    }

    @Test
    void theOwnersOffsetIsAppliedToEveryCue() {
        Film film = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false).setOffsetMs(-2500);

        assertThat(forced.vtt(film.getEditions().get(0).getId())).isEqualTo("WEBVTT\n\n00:00:07.500 --> 00:00:09.500\n"
            + "Hello\n\n00:00:17.500 --> 00:00:20.000\nWorld\n");
    }

    @Test
    void aWholeEditionSidecarOfASplitFilmIsServedOnTheEditionTimelineAsItIs() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers.en.forced.srt", fileId(film, 1), true);

        assertThat(forced.vtt(film.getEditions().get(0).getId())).isEqualTo(HELLO_WORLD);
    }

    @Test
    void perPartSidecarsAreStitchedOntoTheEditionTimelineByEachPartsStart() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);

        assertThat(forced.vtt(film.getEditions().get(0).getId())).isEqualTo(HELLO_WORLD
            + "\n01:00:10.000 --> 01:00:12.000\nHello\n\n01:00:20.000 --> 01:00:22.500\nWorld\n");
    }

    @Test
    void aSecondPartAloneStartsWhereThatPartStarts() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);

        assertThat(forced.vtt(film.getEditions().get(0).getId())).isEqualTo("WEBVTT\n\n01:00:10.000 --> 01:00:12.000\n"
            + "Hello\n\n01:00:20.000 --> 01:00:22.500\nWorld\n");
    }

    @Test
    void threePartsEachMoveByTheirOwnStart() {
        Film film = filmWithParts("Trilogy", 3, 30);
        sidecar("a.en.forced.srt", fileId(film, 2), false);
        sidecar("b.en.forced.srt", fileId(film, 3), false);

        String vtt = forced.vtt(film.getEditions().get(0).getId());

        assertThat(vtt).contains("00:30:10.000 --> 00:30:12.000").contains("01:00:10.000 --> 01:00:12.000");
    }

    @Test
    void aSplitFilmWhosePartHasNoDurationHasNoTimelineSoPerPartSubtitlesAreNotAvailable() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        film.getEditions().get(0).originalParts().get(0).setDurationMs(null);

        assertThatThrownBy(() -> forced.vtt(film.getEditions().get(0).getId()))
            .isInstanceOf(NotFoundException.class).hasMessage("Forced subtitles are not available");
    }

    @Test
    void anEditionWithNoSidecarIs404() {
        Film film = filmWithParts("Taken", 1, 90);

        assertThatThrownBy(() -> forced.vtt(film.getEditions().get(0).getId()))
            .isInstanceOf(NotFoundException.class).hasMessage("No forced subtitles");
    }

    @Test
    void anUnknownEditionIs404() {
        assertThatThrownBy(() -> forced.vtt(9999L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void anEditionWithNoOriginalFileIs404() {
        Film film = filmWithParts("Taken", 1, 90);
        film.getEditions().get(0).getMediaFiles().clear();
        editionRepository.saveAndFlush(film.getEditions().get(0));

        assertThatThrownBy(() -> forced.vtt(film.getEditions().get(0).getId()))
            .isInstanceOf(NotFoundException.class).hasMessage("No forced subtitles");
    }

    @Test
    void twoCompetingSidecarsMeanNoneIsServedAndNoneIsOffered() {
        Film film = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        sidecar("Taken.forced.en.srt", fileId(film, 1), false);

        assertThatThrownBy(() -> forced.vtt(film.getEditions().get(0).getId()))
            .isInstanceOf(NotFoundException.class).hasMessage("No forced subtitles");
        assertThat(forced.forEditions(film.getEditions())).isEmpty();
    }

    @Test
    void aMissingSidecarAndOneWithNoCuesAreNotServed() {
        Film film = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false).setPresence(Presence.MISSING);
        SubtitleSidecar empty = sidecar("Taken.forced.en.srt", fileId(film, 1), false);
        empty.setCueCount(0);

        assertThatThrownBy(() -> forced.vtt(film.getEditions().get(0).getId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theFilmDetailGetsASidecarSourceTheVerifiedFlagAndTheLatestChangeAsTheVersion() {
        Film film = filmWithParts("Towers", 2, 60);
        Edition edition = film.getEditions().get(0);
        sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar second = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        Instant later = NOW.plusSeconds(60);
        second.setUpdatedAt(later);
        sidecars.flush();

        Map<Long, ForcedSubtitleDto> dtos = forced.forEditions(film.getEditions());

        assertThat(dtos).containsOnlyKeys(edition.getId());
        assertThat(dtos.get(edition.getId())).isEqualTo(new ForcedSubtitleDto("SIDECAR", false,
            later.toEpochMilli()));
    }

    @Test
    void theEditionIsVerifiedOnlyWhenEveryContributingFileIs() {
        Film film = filmWithParts("Towers", 2, 60);
        Edition edition = film.getEditions().get(0);
        String key = SubtitleSidecar.partsKey(List.of(fileId(film, 1), fileId(film, 2)));
        SubtitleSidecar first = sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar second = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        first.setOwnerVerifiedAt(NOW);
        first.setVerifiedParts(key);
        assertThat(forced.forEditions(film.getEditions()).get(edition.getId()).ownerVerified()).isFalse();

        second.setOwnerVerifiedAt(NOW);
        second.setVerifiedParts(key);

        assertThat(forced.forEditions(film.getEditions()).get(edition.getId()).ownerVerified()).isTrue();
    }

    @Test
    void anEditionWithNoFilesOrNoSidecarsIsAbsentFromTheDetail() {
        Film film = filmWithParts("Taken", 1, 90);
        assertThat(forced.forEditions(film.getEditions())).isEmpty();

        film.getEditions().get(0).getMediaFiles().clear();
        assertThat(forced.forEditions(film.getEditions())).isEmpty();
        assertThat(forced.forEditions(List.of())).isEmpty();
    }

    @Test
    void aSidecarFollowsItsFileToAnotherEditionAndTheOwnersCheckStopsCounting() {
        Film other = filmWithParts("Other", 1, 100);
        Film taken = filmWithParts("Taken", 1, 90);
        Long takenFile = fileId(taken, 1);
        SubtitleSidecar sidecar = sidecar("Taken.en.forced.srt", takenFile, false);
        admin.verify(sidecar.getId());
        Long takenEdition = taken.getEditions().get(0).getId();
        Long otherEdition = other.getEditions().get(0).getId();
        assertThat(forced.forEditions(taken.getEditions()).get(takenEdition).ownerVerified()).isTrue();

        mediaFiles.moveToEdition(List.of(takenFile), editionRepository.findById(otherEdition).orElseThrow());
        Film movedTo = films.findById(other.getId()).orElseThrow();

        Map<Long, ForcedSubtitleDto> dtos = forced.forEditions(movedTo.getEditions());
        assertThat(dtos).containsOnlyKeys(otherEdition);
        assertThat(dtos.get(otherEdition).source()).isEqualTo("SIDECAR");
        assertThat(dtos.get(otherEdition).ownerVerified()).isFalse();
        assertThat(forced.vtt(otherEdition)).isEqualTo("WEBVTT\n\n01:40:10.000 --> 01:40:12.000\nHello\n\n"
            + "01:40:20.000 --> 01:40:22.500\nWorld\n");
        assertThat(forced.forEditions(films.findById(taken.getId()).orElseThrow().getEditions())).isEmpty();
    }
}
