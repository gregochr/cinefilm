package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which downloads count for an edition, and in which order they are tried. */
class DownloadsTest {

    private static final String IMDB = "tt0936501";

    private Edition edition;
    private List<Long> partIds;

    @BeforeEach
    void setUp() {
        Film film = film(1L, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        MediaFile part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setPartNumber(1);
        partIds = List.of(10L);
    }

    private SubtitleSidecar download(long id, long fileId, boolean chosen) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(id);
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setKind(SubtitleKind.FULL);
        row.setPath("opensubtitles:40:" + fileId);
        row.setOpenSubtitlesFileId(fileId);
        row.setImdbId(IMDB);
        row.setMediaFileId(10L);
        row.setCueCount(500);
        if (chosen) {
            row.setOwnerChosenAt(NOW);
        }
        return row;
    }

    @Test
    void downloadsComeLowestIdFirstWhenNoneIsPicked() {
        List<SubtitleSidecar> found = List.of(download(9, 109, false), download(3, 103, false),
            download(5, 105, false));

        List<SubtitleSidecar> counted = Downloads.of(edition, partIds, found, SubtitleKind.FULL);

        assertThat(counted).extracting(SubtitleSidecar::getId).containsExactly(3L, 5L, 9L);
    }

    @Test
    void aPickedDownloadComesFirstWhateverItsIdAndTheRestKeepTheLowestIdOrder() {
        List<SubtitleSidecar> found = List.of(download(3, 103, false), download(9, 109, true),
            download(5, 105, false), download(7, 107, true));

        List<SubtitleSidecar> counted = Downloads.of(edition, partIds, found, SubtitleKind.FULL);

        assertThat(counted).extracting(SubtitleSidecar::getId).containsExactly(7L, 9L, 3L, 5L);
    }

    @Test
    void anOwnersCheckDoesNotPutADownloadFirstOnlyAPickDoes() {
        SubtitleSidecar checked = download(9, 109, false);
        checked.setOwnerVerifiedAt(NOW);
        checked.setVerifiedParts(SubtitleSidecar.partsKey(partIds));
        List<SubtitleSidecar> found = List.of(download(3, 103, false), checked);

        List<SubtitleSidecar> counted = Downloads.of(edition, partIds, found, SubtitleKind.FULL);

        assertThat(counted).extracting(SubtitleSidecar::getId).containsExactly(3L, 9L);
    }

    @Test
    void aNotNeededFilmCountsNoDownloadsButANoneExistFilmStillCountsTheOnesItHas() {
        List<SubtitleSidecar> found = List.of(download(3, 103, true));

        edition.getFilm().setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        assertThat(Downloads.of(edition, partIds, found, SubtitleKind.FULL)).isEmpty();
        assertThat(Downloads.isNotNeeded(edition, SubtitleKind.FULL)).isTrue();

        edition.getFilm().setFullNeedOwner(FullNeedChoice.NONE_EXIST);
        assertThat(Downloads.isNotNeeded(edition, SubtitleKind.FULL)).isFalse();
        assertThat(Downloads.of(edition, partIds, found, SubtitleKind.FULL))
            .extracting(SubtitleSidecar::getId).containsExactly(3L);
    }
}
