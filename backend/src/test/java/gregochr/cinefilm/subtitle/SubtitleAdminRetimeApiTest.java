package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SubtitleDtos.RetimeRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import gregochr.cinefilm.transcode.MediaKeyframes;
import gregochr.cinefilm.transcode.MediaKeyframesRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Admin's retime choice and its rate facts against real rows on H2: the choice takes effect at once, and the list
 * costs a fixed number of statements however many editions have downloads (no N+1).
 */
class SubtitleAdminRetimeApiTest extends ForcedDownloadTestBase {

    private static final long SIZE = 1_000L;

    @Autowired
    private MediaKeyframesRepository keyframes;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory factory;

    private Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void disableStatistics() {
        statistics.setStatisticsEnabled(false);
    }

    /** A film of one file at {@code fps}, with a download stated for {@code timed}. */
    private SubtitleSidecar statedDownload(String title, long osFileId, double fps, double timed) {
        return statedDownload(title, osFileId, fps, timed, SubtitleKind.FORCED);
    }

    private SubtitleSidecar statedDownload(String title, long osFileId, double fps, double timed,
                                           SubtitleKind kind) {
        Film film = searchedFilm(title, 1, result(osFileId, title + ".2008"));
        MediaFile file = edition(film).originalParts().get(0);
        file.setSizeBytes(SIZE);
        mediaFiles.saveAndFlush(file);
        MediaKeyframes index = new MediaKeyframes();
        index.setMediaFileId(file.getId());
        index.setCuts("0");
        index.setSourceSize(SIZE);
        index.setSourceMtime(1L);
        index.setIndexedAt(NOW);
        index.setFps(fps);
        keyframes.saveAndFlush(index);
        SubtitleSidecar download = download(film, osFileId);
        download.setTimedFps(timed);
        download.setKind(kind);
        return sidecars.saveAndFlush(download);
    }

    /** What the database holds for the row, read with nothing of it left in the persistence context. */
    private SubtitleSidecar reread(Long id) {
        entityManager.flush();
        entityManager.clear();
        return sidecars.findById(id).orElseThrow();
    }

    private SidecarDto listed(Long id) {
        return admin.list().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private long statementsOfOneList() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        admin.list();

        return statistics.getPrepareStatementCount();
    }

    @Test
    void aMatchedDownloadListsItsRateFactsAndAnOwnersFileListsNone() {
        SubtitleSidecar download = statedDownload("Taken", 2001, 25.0, 23.976);
        SubtitleSidecar file = sidecar("Taken.en.forced.srt", download.getMediaFileId(), true);

        SidecarDto dto = listed(download.getId());

        assertThat(dto.timedFps()).isEqualTo(23.976);
        assertThat(dto.filmFps()).isEqualTo(25.0);
        assertThat(dto.retime()).isNull();
        assertThat(dto.retimedBy()).isNull();
        assertThat(dto.rateLine()).isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is not retimed");
        assertThat(dto.retimeAction()).isEqualTo("RETIME");
        SidecarDto owners = listed(file.getId());
        assertThat(Arrays.asList(owners.timedFps(), owners.filmFps(), owners.retime(), owners.retimedBy(),
            owners.rateLine(), owners.retimeAction())).containsOnlyNulls();
    }

    @Test
    void aChoiceThatChangesNoCuesIsStillWrittenToTheDatabase() {
        SubtitleSidecar neverRetimed = statedDownload("Taken", 2001, 25.0, 23.976);
        SubtitleSidecar matching = statedDownload("Alien", 2002, 25.0, 25.0);
        Film unrated = searchedFilm("Heat", 1, result(2003, "Heat.1995"));
        SubtitleSidecar unknown = download(unrated, 2003);
        unknown.setTimedFps(23.976);
        unknown = sidecars.saveAndFlush(unknown);

        admin.retime(neverRetimed.getId(), new RetimeRequest(false));
        admin.retime(matching.getId(), new RetimeRequest(true));
        admin.retime(unknown.getId(), new RetimeRequest(true));

        assertThat(reread(neverRetimed.getId()).getRetime()).isEqualTo(RetimeChoice.OFF);
        assertThat(reread(neverRetimed.getId()).getCuesText()).isEqualTo(CUES);
        assertThat(reread(matching.getId()).getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(reread(matching.getId()).getCuesText()).isEqualTo(CUES);
        assertThat(reread(unknown.getId()).getRetime()).isEqualTo(RetimeChoice.AUTO);
        assertThat(reread(unknown.getId()).getCuesText()).isEqualTo(CUES);
    }

    @Test
    void aDontRetimeOnAFitsAsItIsRowKeepsItAndItsRateLineInTheDatabase() {
        SubtitleSidecar fits = statedDownload("Taken", 2001, 25.0, 23.976);
        fits.setRetime(RetimeChoice.OFF_FITS);
        sidecars.saveAndFlush(fits);

        SidecarDto dto = admin.retime(fits.getId(), new RetimeRequest(false));

        assertThat(dto.retime()).isEqualTo("OFF_FITS");
        assertThat(dto.rateLine()).isEqualTo("Fits this copy as it is");
        assertThat(dto.retimeAction()).isNull();
        assertThat(reread(fits.getId()).getRetime()).isEqualTo(RetimeChoice.OFF_FITS);
        assertThat(listed(fits.getId()).rateLine()).isEqualTo("Fits this copy as it is");
    }

    @Test
    void anOnForAFitsAsItIsRowIsRefusedWithA409AndTheRowIsUntouchedInTheDatabase() {
        SubtitleSidecar fits = statedDownload("Taken", 2001, 25.0, 23.976);
        fits.setRetime(RetimeChoice.OFF_FITS);
        sidecars.saveAndFlush(fits);
        Long id = fits.getId();

        assertThatThrownBy(() -> admin.retime(id, new RetimeRequest(true))).isInstanceOf(ConflictException.class)
            .hasMessage("This file already fits this copy as it is, so it is not retimed");

        assertThat(reread(id).getRetime()).isEqualTo(RetimeChoice.OFF_FITS);
        assertThat(reread(id).getCuesText()).isEqualTo(CUES);
        assertThat(reread(id).getRetimedBy()).isNull();
    }

    @Test
    void aFullDownloadListsItsRateFactsToo() {
        SubtitleSidecar full = statedDownload("Taken", 2101, 25.0, 23.976, SubtitleKind.FULL);

        SidecarDto dto = listed(full.getId());

        assertThat(dto.kind()).isEqualTo("FULL");
        assertThat(dto.filmFps()).isEqualTo(25.0);
        assertThat(dto.rateLine()).isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is not retimed");
    }

    @Test
    void theOwnersOnRetimesTheFileAtOnceAndTheOwnersOffPutsItBack() {
        SubtitleSidecar download = statedDownload("Taken", 2001, 25.0, 23.976);
        Long id = download.getId();

        SidecarDto on = admin.retime(id, new RetimeRequest(true));

        assertThat(on.retime()).isEqualTo("AUTO");
        assertThat(on.retimedBy()).isEqualTo(23.976 / 25);
        assertThat(on.rateLine()).isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
        assertThat(on.retimeAction()).isEqualTo("DONT");
        assertThat(reread(id).getOriginalCuesText()).isEqualTo(CUES);
        assertThat(reread(id).getCuesText()).isNotEqualTo(CUES);

        SidecarDto off = admin.retime(id, new RetimeRequest(false));

        assertThat(off.retime()).isEqualTo("OFF");
        assertThat(off.retimedBy()).isNull();
        assertThat(off.rateLine()).isEqualTo("Not retimed; it may drift");
        assertThat(off.retimeAction()).isEqualTo("RETIME");
        assertThat(reread(id).getCuesText()).isEqualTo(CUES);
        assertThat(reread(id).getOriginalCuesText()).isNull();
    }

    @Test
    void theListCostsNoMoreStatementsWithThreeEditionsOfDownloadsThanWithOne() {
        statedDownload("One", 2001, 25.0, 23.976);
        statedDownload("OneFull", 2101, 25.0, 23.976, SubtitleKind.FULL);
        long withOne = statementsOfOneList();

        statedDownload("Two", 2002, 23.976, 25.0);
        statedDownload("Three", 2003, 25.0, 25.0);
        statedDownload("TwoFull", 2102, 23.976, 25.0, SubtitleKind.FULL);
        statedDownload("ThreeFull", 2103, 25.0, 25.0, SubtitleKind.FULL);
        long withThree = statementsOfOneList();

        assertThat(withOne).isPositive();
        assertThat(withThree).isEqualTo(withOne);
    }
}
