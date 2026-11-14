package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.CandidateDto;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRefresher;
import gregochr.cinefilm.ratings.RatingsRefresher;
import gregochr.cinefilm.subtitle.SidecarCheckRefresher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The attention list's new fields (the candidates' score and the confident flag), with every collaborator mocked. */
@ExtendWith(MockitoExtension.class)
class AdminServiceUnitTest {

    @Mock
    private ScanService scans;

    @Mock
    private ScanRunRepository runs;

    @Mock
    private FilmRepository films;

    @Mock
    private MediaFileRepository files;

    @Mock
    private RatingsRefresher ratings;

    @Mock
    private OpenSubtitlesRefresher subtitleSearch;

    @Mock
    private SidecarCheckRefresher subtitleChecks;

    @Mock
    private GroupingChangeRepository changes;

    private AdminService admin;

    @BeforeEach
    void setUp() {
        admin = new AdminService(scans, runs, films, files, new LibraryRoot("/media/films"), ratings, subtitleSearch,
            subtitleChecks, changes);
    }

    private static String candidate(long id, int score) {
        return "{\"tmdbId\":" + id + ",\"title\":\"T" + id + "\",\"year\":2000,\"runtimeMinutes\":90,"
            + "\"posterPath\":null,\"score\":" + score + "}";
    }

    private static Film unmatched(long id, String title, String candidatesJson) {
        Film film = film(id, title, 2000, NOW);
        film.setMatchStatus(MatchStatus.NEEDS_MATCH);
        film.setMatchCandidates(candidatesJson);
        return film;
    }

    private List<AttentionItem> attentionFor(Film... unmatched) {
        when(films.findByMatchStatusOrderBySortTitle(MatchStatus.NEEDS_MATCH)).thenReturn(List.of(unmatched));
        when(files.findOriginals(Presence.PRESENT, Playability.PROTECTED)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.NEEDS_CONVERSION)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.UNREADABLE)).thenReturn(List.of());
        when(films.findAll()).thenReturn(List.of());
        return admin.attention().items();
    }

    @Test
    void theCandidatesCarryTheirScoreAndTheConfidentFlagComesFromTheRule() {
        Film sure = unmatched(1, "Sure", "[" + candidate(10, 85) + "," + candidate(11, 70) + "]");
        Film unsure = unmatched(2, "Unsure", "[" + candidate(20, 80) + "," + candidate(21, 75) + "]");

        List<AttentionItem> items = attentionFor(sure, unsure);

        assertThat(items).extracting(AttentionItem::title, AttentionItem::confident)
            .containsExactly(org.assertj.core.api.Assertions.tuple("Sure", true),
                org.assertj.core.api.Assertions.tuple("Unsure", false));
        assertThat(items.get(0).candidates()).extracting(CandidateDto::score).containsExactly(85, 70);
        assertThat(items.get(1).candidates()).extracting(CandidateDto::score).containsExactly(80, 75);
    }

    @Test
    void aTaggedMatchTheGuardRefusedIsOneCandidateAtScore100AndIsConfident() {
        List<AttentionItem> items = attentionFor(unmatched(1, "Refused", "[" + candidate(10, 100) + "]"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.confident()).isTrue();
            assertThat(item.candidates()).containsExactly(new CandidateDto(10, "T10", 2000, 90, null, 100));
        });
    }

    @Test
    void aSingleCandidateAtTheAcceptScoreIsNotConfidentButOneAboveItIs() {
        assertThat(attentionFor(unmatched(1, "At", "[" + candidate(10, 60) + "]")))
            .extracting(AttentionItem::confident).containsExactly(false);
        assertThat(attentionFor(unmatched(2, "Above", "[" + candidate(10, 61) + "]")))
            .extracting(AttentionItem::confident).containsExactly(true);
    }

    @Test
    void aWeakOrMissingOrUnreadableCandidateListIsNeverConfident() {
        Film weak = unmatched(1, "Weak", "[" + candidate(10, 59) + "]");
        Film none = unmatched(2, "None", null);
        Film broken = unmatched(3, "Broken", "not json");

        assertThat(attentionFor(weak, none, broken)).extracting(AttentionItem::confident)
            .containsExactly(false, false, false);
    }

    @Test
    void everyOtherKindIsNotConfident() {
        MediaFile drm = file(edition(film(5L, "Drm", 2002, NOW), 50L, "Original", 100, null), "/media/films/Drm.m4v");
        drm.setPresence(Presence.PRESENT);
        when(films.findByMatchStatusOrderBySortTitle(MatchStatus.NEEDS_MATCH)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.PROTECTED)).thenReturn(List.of(drm));
        when(files.findOriginals(Presence.PRESENT, Playability.NEEDS_CONVERSION)).thenReturn(List.of());
        when(files.findOriginals(Presence.PRESENT, Playability.UNREADABLE)).thenReturn(List.of());
        when(films.findAll()).thenReturn(List.of());

        List<AttentionItem> items = admin.attention().items();

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("protected");
            assertThat(item.confident()).isFalse();
        });
    }
}
