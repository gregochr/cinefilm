package gregochr.cinefilm.plex;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.plex.PlexFilmWriter.MediaWrite;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexMedia;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pairing a Plex film's versions and parts with the library's files, behind the different-film guard. */
class PlexPairingTest {

    private static final String PREFIX = "/m/";

    private static Map<String, Known> known(String... paths) {
        Map<String, Known> map = new LinkedHashMap<>();
        long id = 1;
        for (String path : paths) {
            map.put(path, new Known(id++, path, 1L, NOW, Presence.PRESENT, Playability.PLAYABLE, "yuv420p"));
        }
        return map;
    }

    private static PlexMedia media(String... names) {
        return new PlexMedia(Arrays.stream(names).map(n -> PREFIX + n).toList());
    }

    private static PlexFilm film(String title, PlexMedia... media) {
        return new PlexFilm("1", title, null, 1995, null, null, null, null, null, null, null, 0, 0, null,
            List.of(media));
    }

    @Test
    void everyPartOfEveryMediaIsPairedInPlexsOrder() {
        Map<String, Known> known = known("T - pt1.m4v", "T - pt2.m4v", "T HD - pt1.m4v", "T HD - pt2.m4v");

        PlexPairing.Result result = PlexPairing.pair(film("T", media("T - pt2.m4v", "T - pt1.m4v"),
            media("T HD - pt1.m4v", "T HD - pt2.m4v")), PREFIX, known, new HashSet<>());

        assertThat(result.media()).containsExactly(new MediaWrite(List.of(2L, 1L), 2),
            new MediaWrite(List.of(3L, 4L), 2));
        assertThat(result.media()).allMatch(MediaWrite::complete);
        assertThat(result.leftOut()).isEmpty();
    }

    @Test
    void aFileWhoseTitleIsNotThePlexFilmsIsLeftOutAndNotClaimed() {
        Map<String, Known> known = known("Rocky IV.m4v", "Rocky V.m4v");
        Set<Long> claimed = new HashSet<>();

        PlexPairing.Result result = PlexPairing.pair(film("Rocky IV", media("Rocky IV.m4v"), media("Rocky V.m4v")),
            PREFIX, known, claimed);

        assertThat(result.media()).containsExactly(new MediaWrite(List.of(1L), 1));
        assertThat(result.leftOut()).containsExactly("Rocky V.m4v");
        assertThat(claimed).containsExactly(1L);
    }

    @Test
    void withoutAFileTitledLikeThePlexFilmTheFirstPartsTitleDecides() {
        Map<String, Known> known = known("Leon.m4v", "Leon HD.m4v", "Matilda.m4v");

        PlexPairing.Result result = PlexPairing.pair(film("Léon: The Professional", media("Leon.m4v"),
            media("Leon HD.m4v"), media("Matilda.m4v")), PREFIX, known, new HashSet<>());

        assertThat(result.media()).extracting(MediaWrite::fileIds).containsExactly(List.of(1L), List.of(2L));
        assertThat(result.leftOut()).containsExactly("Matilda.m4v");
    }

    @Test
    void aSplitOffPartMakesItsVersionPartlyPaired() {
        Map<String, Known> known = known("Rocky IV - pt1.m4v", "Rocky V.m4v");

        PlexPairing.Result result = PlexPairing.pair(film("Rocky IV", media("Rocky IV - pt1.m4v", "Rocky V.m4v")),
            PREFIX, known, new HashSet<>());

        assertThat(result.media()).containsExactly(new MediaWrite(List.of(1L), 2));
        assertThat(result.media().get(0).complete()).isFalse();
    }

    @Test
    void filesAnEarlierFilmClaimedAreSkippedAndAFilmWithNothingPairedHasNoVersions() {
        Map<String, Known> known = known("A.m4v");

        PlexPairing.Result result = PlexPairing.pair(film("A", media("A.m4v"), media("Missing.m4v")), PREFIX, known,
            new HashSet<>(Set.of(1L)));

        assertThat(result.media()).isEmpty();
        assertThat(result.leftOut()).isEmpty();
    }

    @Test
    void aFilmWithoutVersionsOrATitlePairsNothing() {
        PlexPairing.Result none = PlexPairing.pair(film(null), PREFIX, known("A.m4v"), new HashSet<>());
        PlexPairing.Result outside = PlexPairing.pair(film("A", new PlexMedia(List.of("/other/A.m4v"))), PREFIX,
            known("A.m4v"), new HashSet<>());

        assertThat(none.media()).isEmpty();
        assertThat(outside.media()).isEmpty();
    }

    @Test
    void aSlashInAPlexTitleIsAWordBreakNotAFolder() {
        Map<String, Known> known = known("AC DC Live.m4v");

        PlexPairing.Result result = PlexPairing.pair(film("AC/DC Live", media("AC DC Live.m4v")), PREFIX, known,
            new HashSet<>());

        assertThat(result.media()).containsExactly(new MediaWrite(List.of(1L), 1));
    }
}
