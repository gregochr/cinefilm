package gregochr.cinefilm.transcode;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The V5 table through Hibernate on H2: a row round-trips and the progress query counts present, playable files. */
class MediaKeyframesRepositoryTest extends ApiTestBase {

    @Autowired
    private MediaKeyframesRepository keyframes;

    private MediaFile savedFile(String title, String path) {
        Film film = film(null, title, 1995, NOW, "Crime");
        MediaFile file = file(edition(film, null, "Theatrical Cut", 100 * MIN, null), path);
        films.saveAndFlush(film);
        return file;
    }

    private void index(MediaFile file) {
        keyframes.saveAndFlush(new MediaKeyframes(file.getId(), "0,4000,8008", 4008, true, 123_456_789L,
            1_700_000_000_000L, CLOCK.instant(), 23.976));
    }

    @Test
    void aRowRoundTripsWithEveryColumn() {
        MediaFile file = savedFile("Heat", "heat.mp4");
        index(file);

        MediaKeyframes row = keyframes.findById(file.getId()).orElseThrow();

        assertThat(row.getCuts()).isEqualTo("0,4000,8008");
        assertThat(row.getMaxGapMs()).isEqualTo(4008);
        assertThat(row.isCopyOk()).isTrue();
        assertThat(row.getSourceSize()).isEqualTo(123_456_789L);
        assertThat(row.getSourceMtime()).isEqualTo(1_700_000_000_000L);
        assertThat(row.getIndexedAt()).isEqualTo(CLOCK.instant());
        assertThat(row.getFps()).isEqualTo(23.976);
    }

    @Test
    void aLongCutListFitsTheColumn() {
        MediaFile file = savedFile("Heat", "heat.mp4");
        String cuts = "0" + ",1234567".repeat(MediaKeyframes.MAX_CUTS_LENGTH / 8 - 1);

        keyframes.saveAndFlush(new MediaKeyframes(file.getId(), cuts, 1, true, 1, 1, CLOCK.instant(), null));

        assertThat(keyframes.findById(file.getId()).orElseThrow().getCuts()).isEqualTo(cuts);
    }

    @Test
    void onlyPresentPlayableFilesWithARowAreCountedAsIndexed() {
        MediaFile indexed = savedFile("Heat", "heat.mp4");
        MediaFile missing = savedFile("Alien", "alien.mp4");
        MediaFile protectedFile = savedFile("Brazil", "brazil.m4v");
        savedFile("Chinatown", "chinatown.mp4");
        missing.setPresence(Presence.MISSING);
        protectedFile.setPlayability(Playability.PROTECTED);
        films.flush();
        index(indexed);
        index(missing);
        index(protectedFile);

        assertThat(keyframes.countIndexedPlayable()).isEqualTo(1);
    }
}
