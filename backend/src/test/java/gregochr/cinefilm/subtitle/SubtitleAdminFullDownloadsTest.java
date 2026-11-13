package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Admin and full downloads against the real services on H2: a full download is listed as one, plays unless the owner's
 * full file does, can be nudged, and removing it blocks that file for the edition as a FULL block (never a forced one)
 * so the next candidate is looked for.
 */
@RecordApplicationEvents
class SubtitleAdminFullDownloadsTest extends ForcedDownloadTestBase {

    @Autowired
    private ForcedDownloadService downloads;

    @Autowired
    private ApplicationEvents events;

    private Film fullFilm() {
        Film film = filmWithParts("Oldboy", 1, 60);
        film.setImdbId(IMDB);
        film.setFullNeedOwner(FullNeedChoice.NEEDED);
        film.setOsFullSearchedImdbId(IMDB);
        film.setOsFullSearchedAt(NOW);
        film.setOsFullCandidates(OpenSubtitlesResult.listToJson(List.of(result(5011, "a"), result(5012, "b"),
            result(5013, "c"))));
        return films.saveAndFlush(film);
    }

    private SubtitleSidecar fullDownload(Film film, long osFileId) {
        SubtitleSidecar row = download(film, osFileId);
        row.setKind(SubtitleKind.FULL);
        return sidecars.saveAndFlush(row);
    }

    private SidecarDto listed(SubtitleSidecar row) {
        return admin.list().stream().filter(d -> d.id().equals(row.getId())).findFirst().orElseThrow();
    }

    @Test
    void aFullDownloadIsListedAsFullFromOpenSubtitlesAndReadyToPlay() {
        Film film = fullFilm();
        SubtitleSidecar download = fullDownload(film, 5012);

        SidecarDto dto = listed(download);

        assertThat(dto.kind()).isEqualTo("FULL");
        assertThat(dto.origin()).isEqualTo("OPENSUBTITLES");
        assertThat(dto.openSubtitlesFileId()).isEqualTo(5012L);
        assertThat(dto.state()).isEqualTo("matched");
        assertThat(dto.served()).isTrue();
        assertThat(dto.servedReason()).isEqualTo("Playing as English (full); not checked yet");
        assertThat(dto.check()).isNull();
        assertThat(dto.alignment()).isNull();
    }

    @Test
    void anOwnersFullFileBeatsTheDownloadAndTheListSaysWhichPlays() {
        Film film = fullFilm();
        SubtitleSidecar download = fullDownload(film, 5012);
        SubtitleSidecar owner = fullSidecar("Oldboy.en.srt", fileId(film, 1), false);

        assertThat(listed(owner).served()).isTrue();
        SidecarDto dto = listed(download);
        assertThat(dto.served()).isFalse();
        assertThat(dto.servedReason()).isEqualTo("Not used: your own full file plays instead");
    }

    @Test
    void aSecondFullDownloadIsNotUsedBecauseAnotherDownloadTakesItsPlace() {
        Film film = fullFilm();
        SubtitleSidecar first = fullDownload(film, 5011);
        SubtitleSidecar second = fullDownload(film, 5012);

        assertThat(listed(first).served()).isTrue();
        SidecarDto dto = listed(second);
        assertThat(dto.served()).isFalse();
        assertThat(dto.servedReason()).isEqualTo("Not used: another full subtitle file takes its place");
    }

    @Test
    void aConflictBetweenTheOwnersFullFilesKeepsTheDownloadOutToo() {
        Film film = fullFilm();
        SubtitleSidecar download = fullDownload(film, 5012);
        fullSidecar("Oldboy.en.srt", fileId(film, 1), true);
        fullSidecar("Oldboy.eng.srt", fileId(film, 1), true);

        SidecarDto dto = listed(download);

        assertThat(dto.served()).isFalse();
        assertThat(dto.servedReason())
            .isEqualTo("Not used: your own full files compete for the same place, so none plays");
    }

    @Test
    void aFullDownloadForAFilmTheOwnerSaidIsNotNeededIsNotPlayed() {
        Film film = fullFilm();
        SubtitleSidecar download = fullDownload(film, 5012);
        film.setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        films.saveAndFlush(film);

        SidecarDto dto = listed(download);

        assertThat(dto.served()).isFalse();
        assertThat(dto.servedReason()).isEqualTo("Not used: this film is marked as needing no full subtitles");
    }

    @Test
    void theOwnerCanNudgeAFullDownloadAndItsOffsetMovesTheServedVersion() {
        Film film = fullFilm();
        SubtitleSidecar download = fullDownload(film, 5012);

        SidecarDto dto = admin.offset(download.getId(), new OffsetRequest(1_500));

        assertThat(dto.offsetMs()).isEqualTo(1_500);
        assertThat(dto.kind()).isEqualTo("FULL");
        assertThat(sidecars.findById(download.getId()).orElseThrow().getOffsetMs()).isEqualTo(1_500);
    }

    @Test
    void removingAFullDownloadBlocksThatFileAsFullOnlyAndAsksForTheNextCandidate() {
        Film film = fullFilm();
        SubtitleSidecar download = fullDownload(film, 5011);

        admin.remove(download.getId());

        assertThat(sidecars.findById(download.getId())).isEmpty();
        assertThat(blocks.findByEditionIdInAndKind(List.of(edition(film).getId()), SubtitleKind.FULL))
            .singleElement().satisfies(block -> {
                assertThat(block.getOsFileId()).isEqualTo(5011L);
                assertThat(block.getImdbId()).isEqualTo(IMDB);
                assertThat(block.getKind()).isEqualTo(SubtitleKind.FULL);
            });
        assertThat(blocks.findByEditionIdInAndKind(List.of(edition(film).getId()), SubtitleKind.FORCED)).isEmpty();
        assertThat(events.stream(SubtitleDownloadRemoved.class).count()).isEqualTo(1);
        DueDownload due = downloads.due().get(0);
        assertThat(due.kind()).isEqualTo(Kind.FULL);
        assertThat(due.remainingFetches()).isEqualTo(2);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L);
    }
}
