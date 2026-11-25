package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The retime service's candidate query and restore, against real rows on H2. */
class SubtitleRetimeServiceApiTest extends ForcedDownloadTestBase {

    private static final String SCALED = SrtParser.format(java.util.List.of(new SubtitleCue(9_590, 11_509, "Hello"),
        new SubtitleCue(19_181, 21_578, "World")));

    @Autowired
    private SubtitleRetimeService service;

    @Autowired
    private EntityManager entityManager;

    @Test
    void theCandidatesAreTheDownloadsWithAStatedRateOrARetimedRowAndRestoreWritesTheStoredCues() {
        Film film = searchedFilm("Taken", 1, result(2001, "Taken.2008"));
        SubtitleSidecar file = sidecar("Taken.en.forced.srt", fileId(film, 1), true);
        file.setTimedFps(23.976);
        file = sidecars.saveAndFlush(file);
        SubtitleSidecar stated = download(film, 2001);
        stated.setTimedFps(23.976);
        stated = sidecars.saveAndFlush(stated);
        SubtitleSidecar retimed = download(film, 2002);
        retimed.setCuesText(SCALED);
        retimed.setOriginalCuesText(CUES);
        retimed.setRetimedBy(23.976 / 25);
        retimed.setRetimedForFps(25.0);
        retimed = sidecars.saveAndFlush(retimed);
        SubtitleSidecar neither = download(film, 2003);

        assertThat(service.candidateIds()).containsExactly(stated.getId(), retimed.getId());

        assertThat(service.process(stated.getId(), RetimeMode.RESTORE)).isEqualTo(Outcome.UNCHANGED);
        assertThat(service.process(retimed.getId(), RetimeMode.RESTORE)).isEqualTo(Outcome.RESTORED);
        // The test's transaction is shared, so read back from H2, not from the managed entities.
        entityManager.flush();
        entityManager.clear();

        assertThat(sidecars.findById(retimed.getId()).orElseThrow().getCuesText()).isEqualTo(CUES);
        assertThat(sidecars.findById(retimed.getId()).orElseThrow().getOriginalCuesText()).isNull();
        assertThat(sidecars.findById(retimed.getId()).orElseThrow().getRetimedBy()).isNull();
        assertThat(sidecars.findById(stated.getId()).orElseThrow().getCuesText()).isEqualTo(CUES);
        assertThat(sidecars.findById(file.getId()).orElseThrow().getCuesText()).isEqualTo(CUES);
        assertThat(sidecars.findById(neither.getId()).orElseThrow().getCuesText()).isEqualTo(CUES);
    }
}
