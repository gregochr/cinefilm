package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.Attention;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import gregochr.cinefilm.ratings.RatingsRefresher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What the two Admin panels show, built from rows saved directly (the scan itself is {@link ScanApiTest}). */
class AdminServiceTest extends ApiTestBase {

    @Autowired
    private ScanRunRepository runs;

    @Autowired
    private MediaFileRepository mediaFiles;

    private ScanService scans;
    private RatingsRefresher ratings;
    private AdminService admin;

    @BeforeEach
    void setUp() {
        scans = mock(ScanService.class);
        ratings = mock(RatingsRefresher.class);
        admin = new AdminService(scans, runs, films, mediaFiles, new LibraryRoot("/media/films"), ratings);
    }

    @Test
    void theStatusReportsWhenRatingsWereLastFetchedForAnyFilmAndWhetherARefreshRuns() {
        Film older = saved("Older", 2000, "a.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.PRESENT);
        older.setRatingsFetchedAt(NOW.minus(Duration.ofDays(3)));
        films.save(older);
        Film newer = saved("Newer", 2001, "b.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.PRESENT);
        newer.setRatingsFetchedAt(NOW.minus(Duration.ofDays(1)));
        films.save(newer);
        saved("Never", 2002, "c.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.PRESENT);
        when(ratings.isRunning()).thenReturn(true);
        when(ratings.enabled()).thenReturn(true);

        ScanStatusDto status = admin.status();

        assertThat(status.ratingsLastRefreshedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(status.ratingsRunning()).isTrue();
        assertThat(status.ratingsEnabled()).isTrue();
    }

    @Test
    void theStatusSaysRatingsWereNeverRefreshedWhenNoFilmHasBeenStamped() {
        saved("Never", 2002, "c.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.PRESENT);

        ScanStatusDto status = admin.status();

        assertThat(status.ratingsLastRefreshedAt()).isNull();
        assertThat(status.ratingsRunning()).isFalse();
        assertThat(status.ratingsEnabled()).isFalse();
    }

    private Film saved(String title, Integer year, String path, MatchStatus status, Playability playability,
                       Presence presence) {
        Film film = film(null, title, year, NOW);
        film.setMatchStatus(status);
        MediaFile file = file(edition(film, null, "Original", 100 * MIN, null), path);
        file.setPlayability(playability);
        file.setPresence(presence);
        return save(film);
    }

    private ScanRun run(Instant started, Instant finished, int seen, int added, int missing, String error) {
        ScanRun run = new ScanRun();
        run.setStartedAt(started);
        run.setFinishedAt(finished);
        run.setTrigger("nightly");
        run.setFilesSeen(seen);
        run.setNewFiles(added);
        run.setMissingFiles(missing);
        run.setError(error);
        return runs.save(run);
    }

    @Test
    void theStatusShowsTheFolderTheLastFinishedScanAndLiveProgress() {
        run(NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)).plusSeconds(60), 10, 2, 0, null);
        run(NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(5)).plusSeconds(90), 12, 3, 1, "a note");
        run(NOW.minusSeconds(30), null, 0, 0, 0, null);
        when(scans.isRunning()).thenReturn(true);
        when(scans.progress()).thenReturn(new ScanService.Progress(40, 12, "Heat.m4v"));

        ScanStatusDto status = admin.status();

        assertThat(status.running()).isTrue();
        assertThat(status.folder()).isEqualTo("/media/films");
        assertThat(status.progress()).isEqualTo(new AdminDtos.ProgressDto(12, 40, "Heat.m4v"));
        assertThat(status.lastScan()).isEqualTo(new AdminDtos.LastScanDto(NOW.minus(Duration.ofHours(5)),
            NOW.minus(Duration.ofHours(5)).plusSeconds(90), "nightly", 12, 3, 1, "a note"));
    }

    @Test
    void aStatusBeforeAnyScanHasNoLastScanNoProgressAndZeroCounts() {
        ScanStatusDto status = admin.status();

        assertThat(status.running()).isFalse();
        assertThat(status.progress()).isNull();
        assertThat(status.lastScan()).isNull();
        assertThat(status.counts()).isEqualTo(new AdminDtos.CountsDto(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void theCountsSplitTheLibraryIntoWhatBrowsesAndWhatNeedsAttention() {
        run(NOW.minusSeconds(100), NOW.minusSeconds(50), 8, 4, 0, null);
        saved("Fine", 2000, "a.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.PRESENT);
        saved("Review", 2001, "b.m4v", MatchStatus.NEEDS_MATCH, Playability.PLAYABLE, Presence.PRESENT);
        saved("Drm", 2002, "c.m4v", MatchStatus.PENDING, Playability.PROTECTED, Presence.PRESENT);
        saved("Avi", 2003, "d.avi", MatchStatus.PENDING, Playability.NEEDS_CONVERSION, Presence.PRESENT);
        saved("Avi Two", 2003, "e.avi", MatchStatus.PENDING, Playability.NEEDS_CONVERSION, Presence.PRESENT);
        saved("Broken", 2004, "f.m4v", MatchStatus.PENDING, Playability.UNREADABLE, Presence.PRESENT);
        saved("Gone", 2005, "g.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.MISSING);
        saved("Gone Drm", 2006, "h.m4v", MatchStatus.PENDING, Playability.PROTECTED, Presence.MISSING);

        assertThat(admin.status().counts()).isEqualTo(new AdminDtos.CountsDto(2, 4, 2, 1, 2, 1, 1));
    }

    @Test
    void anUnmatchedFileShowsItsCandidatesReadOnly() {
        Film dune = saved("Dune", null, "Dune.m4v", MatchStatus.NEEDS_MATCH, Playability.PLAYABLE, Presence.PRESENT);
        dune.setMatchCandidates("[{\"tmdbId\":841,\"title\":\"Dune\",\"year\":1984,\"runtimeMinutes\":136,"
            + "\"posterPath\":\"/p.jpg\",\"score\":50},{\"tmdbId\":438631,\"title\":\"Dune\",\"year\":2021,"
            + "\"runtimeMinutes\":null,\"posterPath\":null,\"score\":50}]");

        List<AttentionItem> items = admin.attention().items();

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("needs-match");
            assertThat(item.filmId()).isEqualTo(dune.getId());
            assertThat(item.title()).isEqualTo("Dune");
            assertThat(item.year()).as("year 0 means unknown").isNull();
            assertThat(item.detail()).isEqualTo("2 possible matches");
            assertThat(item.files()).containsExactly("Dune.m4v");
            assertThat(item.candidates()).containsExactly(
                new AdminDtos.CandidateDto(841, "Dune", 1984, 136, "/p.jpg"),
                new AdminDtos.CandidateDto(438631, "Dune", 2021, null, null));
        });
    }

    @Test
    void oneCandidateReadsAsOnePossibleMatchAndNoCandidatesAsNoMatchFound() {
        Film one = saved("One", 2000, "one.m4v", MatchStatus.NEEDS_MATCH, Playability.PLAYABLE, Presence.PRESENT);
        one.setMatchCandidates("[{\"tmdbId\":1,\"title\":\"One\",\"year\":2000,\"runtimeMinutes\":90,"
            + "\"posterPath\":null,\"score\":40}]");
        saved("Zero", 2001, "zero.m4v", MatchStatus.NEEDS_MATCH, Playability.PLAYABLE, Presence.PRESENT);
        Film broken = saved("Broken", 2002, "broken.m4v", MatchStatus.NEEDS_MATCH, Playability.PLAYABLE,
            Presence.PRESENT);
        broken.setMatchCandidates("not json");

        assertThat(admin.attention().items()).extracting(AttentionItem::title, AttentionItem::detail)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Broken", "No match found on TMDB"),
                org.assertj.core.api.Assertions.tuple("One", "1 possible match"),
                org.assertj.core.api.Assertions.tuple("Zero", "No match found on TMDB"));
        assertThat(admin.attention().items().get(0).candidates()).isEmpty();
    }

    @Test
    void protectedConversionAndUnreadableFilesAreListedWithTheirFileNameOnly() {
        saved("Drm Film", 2002, "Sub/Drm Film.m4v", MatchStatus.PENDING, Playability.PROTECTED, Presence.PRESENT);
        saved("Old", 1999, "Old.avi", MatchStatus.PENDING, Playability.NEEDS_CONVERSION, Presence.PRESENT);
        saved("Corrupt", 2004, "Corrupt.m4v", MatchStatus.PENDING, Playability.UNREADABLE, Presence.PRESENT);
        saved("Gone", 2005, "Gone.avi", MatchStatus.PENDING, Playability.NEEDS_CONVERSION, Presence.MISSING);

        Attention attention = admin.attention();

        assertThat(attention.items()).extracting(AttentionItem::kind, AttentionItem::title, AttentionItem::files,
            AttentionItem::detail).containsExactly(
                org.assertj.core.api.Assertions.tuple("protected", "Drm Film", List.of("Drm Film.m4v"),
                    "Protected by FairPlay, so it can't be played"),
                org.assertj.core.api.Assertions.tuple("needs-conversion", "Old", List.of("Old.avi"),
                    "Needs converting to MP4 before it can be played"),
                org.assertj.core.api.Assertions.tuple("unreadable", "Corrupt", List.of("Corrupt.m4v"),
                    "ffprobe could not read this file; it is tried again at the next scan"));
        assertThat(attention.items().get(0).candidates()).isEmpty();
        assertThat(attention.items().get(1).year()).isEqualTo(1999);
    }

    @Test
    void severalPresentFilesForOneFilmAreListedButAMissingOneDoesNotCount() {
        Film heat = film(null, "Heat", 1995, NOW);
        file(edition(heat, null, "Original", 100 * MIN, null), "Heat.m4v");
        file(edition(heat, null, "Original", 100 * MIN, null), "Heat 2.m4v");
        MediaFile gone = file(edition(heat, null, "Original", 100 * MIN, null), "Heat 3.m4v");
        gone.setPresence(Presence.MISSING);
        save(heat);
        Film alien = film(null, "Alien", 1979, NOW);
        file(edition(alien, null, "Original", 100 * MIN, null), "Alien.m4v");
        MediaFile alienGone = file(edition(alien, null, "Original", 100 * MIN, null), "Alien old.m4v");
        alienGone.setPresence(Presence.MISSING);
        save(alien);

        assertThat(admin.attention().items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("multiple-files");
            assertThat(item.title()).isEqualTo("Heat");
            assertThat(item.detail()).isEqualTo("2 files match one film");
            assertThat(item.files()).containsExactly("Heat.m4v", "Heat 2.m4v");
        });
    }

    @Test
    void aLibraryWithNothingWrongHasAnEmptyAttentionList() {
        saved("Fine", 2000, "a.m4v", MatchStatus.MATCHED, Playability.PLAYABLE, Presence.PRESENT);

        assertThat(admin.attention().items()).isEmpty();
    }

    @Test
    void anEditionWithNoFileRowIsSimplyNotListedAmongTheFileNames() {
        Film dune = film(null, "Dune", null, NOW);
        dune.setMatchStatus(MatchStatus.NEEDS_MATCH);
        edition(dune, null, "No file", 100 * MIN, null);
        file(edition(dune, null, "Original", 100 * MIN, null), "Dune.m4v");
        save(dune);

        assertThat(admin.attention().items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("needs-match");
            assertThat(item.files()).containsExactly("Dune.m4v");
        });
    }
}
