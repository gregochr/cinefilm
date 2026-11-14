package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The dataset targets against the real mappings and queries on H2: which markers still describe a readable file. */
class DatasetTargetsApiTest extends ApiTestBase {

    private static final long LENGTH = 100 * MIN;

    @Autowired
    private DatasetTargets datasetTargets;

    @Autowired
    private PlexCreditsMarkerRepository markers;

    private Film filmOf(String title, int parts) {
        Film built = film(null, title, 2000, NOW);
        Edition edition = edition(built, null, "Original", parts * LENGTH, null);
        for (int i = 1; i <= parts; i++) {
            MediaFile file = file(edition, title + " - pt" + i + ".m4v");
            file.setPartNumber(i);
            file.setDurationMs(LENGTH);
            file.setSizeBytes(1_000L + i);
            file.setModifiedAt(NOW);
        }
        return films.saveAndFlush(built);
    }

    private static MediaFile part(Film film, int number) {
        return film.getEditions().get(0).originalParts().get(number - 1);
    }

    private PlexCreditsMarker mark(MediaFile file, String fingerprint, long startMs) {
        PlexCreditsMarker marker = new PlexCreditsMarker();
        marker.setMediaFileId(file.getId());
        marker.setFingerprint(fingerprint);
        marker.setStartMs(startMs);
        marker.setImportedAt(NOW);
        return markers.saveAndFlush(marker);
    }

    @Test
    void onlyMarkersThatStillDescribeTheLastPartOfAnUnchangedPlayableFileAreTargets() {
        Film good = filmOf("Good", 1);
        Film changed = filmOf("Changed", 1);
        Film gone = filmOf("Gone", 1);
        Film early = filmOf("Early", 1);
        Film split = filmOf("Split", 2);
        MediaFile goodFile = part(good, 1);
        mark(goodFile, goodFile.fingerprint(), 95 * MIN);
        mark(part(changed, 1), "other:1", 95 * MIN);
        MediaFile goneFile = part(gone, 1);
        mark(goneFile, goneFile.fingerprint(), 95 * MIN);
        goneFile.setPresence(Presence.MISSING);
        films.saveAndFlush(gone);
        MediaFile earlyFile = part(early, 1);
        mark(earlyFile, earlyFile.fingerprint(), 10 * MIN);
        MediaFile firstPart = part(split, 1);
        MediaFile lastPart = part(split, 2);
        mark(firstPart, firstPart.fingerprint(), 95 * MIN);
        mark(lastPart, lastPart.fingerprint(), 90 * MIN);

        List<DatasetTarget> targets = datasetTargets.targets();

        assertThat(targets).containsExactly(
            new DatasetTarget(goodFile.getId(), "Good - pt1.m4v", goodFile.fingerprint(), LENGTH, 95 * MIN),
            new DatasetTarget(lastPart.getId(), "Split - pt2.m4v", lastPart.fingerprint(), LENGTH, 90 * MIN));
    }

    @Test
    void stillCurrentFollowsTheFilesPresenceAndFingerprint() {
        Film film = filmOf("Current", 1);
        MediaFile file = part(film, 1);
        DatasetTarget target = new DatasetTarget(file.getId(), file.getPath(), file.fingerprint(), LENGTH, 95 * MIN);

        assertThat(datasetTargets.stillCurrent(target)).isTrue();

        file.setSizeBytes(42L);
        films.saveAndFlush(film);
        assertThat(datasetTargets.stillCurrent(target)).isFalse();
    }
}
