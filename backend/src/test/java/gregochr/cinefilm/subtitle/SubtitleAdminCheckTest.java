package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The OpenSubtitles check shown on the Admin list: only while it still describes what the edition serves. */
class SubtitleAdminCheckTest extends SubtitleTestBase {

    private static final Instant CHECKED = NOW.plus(Duration.ofHours(2));

    @Autowired
    private ForcedSubtitleCheckRepository checks;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    private Film filmWithImdb(String title, int parts) {
        Film film = filmWithParts(title, parts, 60);
        film.setImdbId("tt0936501");
        film.setOsSearchedImdbId("tt0936501");
        film.setOsForcedCount(1);
        film.setOsBest(best(5011));
        return films.saveAndFlush(film);
    }

    private static String best(long fileId) {
        return new gregochr.cinefilm.opensubtitles.OpenSubtitlesResult(1, fileId, "r", 23.976, 1, true, false, null,
            null).toJson();
    }

    private OpenSubtitlesReference reference(Film film, String imdbId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(film.getId());
        reference.setImdbId(imdbId);
        reference.setOsFileId(5011);
        reference.setRelease("Taken.2008.BluRay");
        reference.setCuesText(CUES);
        reference.setCueCount(2);
        reference.setFetchedAt(NOW);
        return references.saveAndFlush(reference);
    }

    private void check(Film film, String version, OpenSubtitlesReference reference) {
        ForcedSubtitleCheck row = new ForcedSubtitleCheck();
        row.setEditionId(film.getEditions().get(0).getId());
        row.setServedVersion(version);
        row.setReferenceId(reference.getId());
        row.setVerdict("CHECK");
        row.setMatchedShare(0.5);
        row.setServedCount(12);
        row.setReferenceCount(41);
        row.setBestOffsetMs(1200);
        row.setReasons("OpenSubtitles has 41 lines, this file has 12\nOpenSubtitles' file is 1.2 s later");
        row.setCheckedAt(CHECKED);
        checks.saveAndFlush(row);
    }

    private SidecarDto listed(SubtitleSidecar row) {
        return admin.list().stream().filter(d -> d.id().equals(row.getId())).findFirst().orElseThrow();
    }

    private String versionOf(Film film, SubtitleSidecar row) {
        return forced.served(film.getEditions()).get(film.getEditions().get(0).getId()).version();
    }

    @Test
    void aCurrentCheckIsShownOnTheFileTheEditionServesWithEveryField() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        check(film, versionOf(film, row), reference(film, "tt0936501"));

        assertThat(listed(row).check()).isEqualTo(new CheckDto("CHECK", List.of(
            "OpenSubtitles has 41 lines, this file has 12", "OpenSubtitles' file is 1.2 s later"), 1200, 0.5, 12,
            41, "Taken.2008.BluRay", CHECKED));
    }

    @Test
    void aFileWithNoCheckHasNone() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        assertThat(listed(row).check()).isNull();
    }

    @Test
    void aCheckForAnotherVersionOfWhatIsServedIsNotShown() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        check(film, versionOf(film, row), reference(film, "tt0936501"));

        row.setOffsetMs(250);
        row.setUpdatedAt(CHECKED);

        assertThat(listed(row).check()).isNull();
    }

    @Test
    void aCheckAgainstAReferenceForAnotherImdbIdIsNotShown() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        check(film, versionOf(film, row), reference(film, "tt0000002"));

        assertThat(listed(row).check()).isNull();
    }

    @Test
    void aFileTheEditionDoesNotServeShowsNoCheckEvenThoughTheEditionHasOne() {
        Film film = filmWithImdb("Towers", 2);
        SubtitleSidecar whole = sidecar("Towers.en.forced.srt", fileId(film, 1), true);
        SubtitleSidecar part = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        check(film, "p" + fileId(film, 1) + "," + fileId(film, 2) + "|" + whole.getId() + "@0/0/"
            + ForcedSubtitleServedTest.fingerprint(CUES), reference(film, "tt0936501"));

        assertThat(listed(whole).check()).isNotNull();
        assertThat(listed(part).check()).isNull();
    }

    @Test
    void aFileNoEditionServesAndAnUnmatchedFileShowNoCheck() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar missing = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        missing.setCueCount(0);
        SubtitleSidecar unmatched = sidecar("Other.en.forced.srt", null, false);

        assertThat(listed(missing).check()).isNull();
        assertThat(listed(unmatched).check()).isNull();
    }

    @Test
    void anEditionWhoseTimelineIsUnknownShowsNoCheckAndTheListStillWorks() {
        Film film = filmWithImdb("Towers", 2);
        SubtitleSidecar part = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        film.getEditions().get(0).originalParts().get(0).setDurationMs(null);

        assertThat(listed(part).check()).isNull();
    }

    @Test
    void aCheckIsNotShownOnceTheFilmsBestOpenSubtitlesFileIsAnotherOne() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        check(film, versionOf(film, row), reference(film, "tt0936501"));
        assertThat(listed(row).check()).isNotNull();

        film.setOsBest(best(6022));
        films.saveAndFlush(film);

        assertThat(listed(row).check()).isNull();
    }

    @Test
    void aCheckIsNotShownWhenTheFilmHasNoBestResultAtAll() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        check(film, versionOf(film, row), reference(film, "tt0936501"));

        film.setOsBest(null);
        films.saveAndFlush(film);

        assertThat(listed(row).check()).isNull();
    }

    @Test
    void aCheckAgainstABestResultThatIsNowMultiFileOrLegacyIsNotShown() {
        Film film = filmWithImdb("Taken", 1);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        check(film, versionOf(film, row), reference(film, "tt0936501"));

        film.setOsBest(new gregochr.cinefilm.opensubtitles.OpenSubtitlesResult(1, 5011, "r", 23.976, 1, true, false,
            null, null, 2).toJson());
        films.saveAndFlush(film);
        assertThat(listed(row).check()).isNull();

        film.setOsBest("{\"id\":1,\"fileId\":5011}");
        films.saveAndFlush(film);
        assertThat(listed(row).check()).isNull();
    }

    @Test
    void servedIsTrueOnlyForTheFilesPlaybackServes() {
        Film film = filmWithImdb("Towers", 2);
        SubtitleSidecar whole = sidecar("Towers.en.forced.srt", fileId(film, 1), true);
        SubtitleSidecar beaten = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        SubtitleSidecar unmatched = sidecar("Other.en.forced.srt", null, false);
        Film conflicted = filmWithImdb("Conflict", 1);
        SubtitleSidecar one = sidecar("Conflict.en.forced.srt", fileId(conflicted, 1), false);
        SubtitleSidecar two = sidecar("Conflict.forced.en.srt", fileId(conflicted, 1), false);
        Film empty = filmWithImdb("Empty", 1);
        SubtitleSidecar none = sidecar("Empty.en.forced.srt", fileId(empty, 1), false);
        none.setCueCount(0);

        assertThat(listed(whole).served()).isTrue();
        assertThat(listed(beaten).served()).isFalse();
        assertThat(listed(unmatched).served()).isFalse();
        assertThat(listed(one).served()).isFalse();
        assertThat(listed(two).served()).isFalse();
        assertThat(listed(none).served()).isFalse();
    }

    @Test
    void aFileOnAnEditionWhoseTimelineIsUnknownIsNotServed() {
        Film film = filmWithImdb("Towers", 2);
        SubtitleSidecar part = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        film.getEditions().get(0).originalParts().get(0).setDurationMs(null);

        assertThat(listed(part).served()).isFalse();
    }
}
