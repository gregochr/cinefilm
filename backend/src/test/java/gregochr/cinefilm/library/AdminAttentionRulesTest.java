package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.ratings.RatingsRefresher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The rules for "Several files" and "Parts don't match": only genuine ambiguity in a film the owner has not settled is
 * listed, a properly tagged or numbered film never is.
 */
class AdminAttentionRulesTest extends ApiTestBase {

    @Autowired
    private ScanRunRepository runs;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private GroupingChangeRepository groupingChanges;

    private AdminService admin;

    @BeforeEach
    void setUp() {
        admin = new AdminService(mock(ScanService.class), runs, films, mediaFiles, new LibraryRoot("/media/films"),
            mock(RatingsRefresher.class), groupingChanges);
    }

    /** One film with one edition per path, each 100 minutes (or the given runtime) long. */
    private Film filmOf(String title, boolean confirmed, long[] runtimesMin, String... paths) {
        Film film = film(null, title, 2000, NOW);
        film.setEditionsConfirmed(confirmed);
        for (int i = 0; i < paths.length; i++) {
            long minutes = runtimesMin.length > i ? runtimesMin[i] : 100;
            file(edition(film, null, "Edition " + i, minutes * MIN, null), paths[i]);
        }
        return save(film);
    }

    private Film filmOf(String title, String... paths) {
        return filmOf(title, false, new long[0], paths);
    }

    private List<AttentionItem> several() {
        return admin.attention().items().stream().filter(i -> i.kind().equals("multiple-files")).toList();
    }

    @Test
    void aFilmWithTwoDifferentlyTaggedCutsIsNotListed() {
        filmOf("Gladiator", "Gladiator {edition-Theatrical}.m4v", "Gladiator {edition-Extended}.m4v");

        assertThat(admin.attention().items()).isEmpty();
    }

    @Test
    void aTaggedCutBesideAnUntaggedOneIsNotListed() {
        filmOf("Gladiator", "Gladiator.m4v", "Gladiator {edition-Extended}.m4v", "Gladiator Directors Cut.m4v");

        assertThat(admin.attention().items()).isEmpty();
    }

    @Test
    void aSplitFilmJoinedIntoOneEditionIsNotListed() {
        Film towers = film(null, "The Two Towers", 2002, NOW);
        Edition edition = edition(towers, null, "Original", 200 * MIN, null);
        file(edition, "The Two Towers pt1.m4v").setPartNumber(1);
        file(edition, "The Two Towers pt2.m4v").setPartNumber(2);
        save(towers);

        assertThat(admin.attention().items()).isEmpty();
    }

    @Test
    void aFilmTheOwnerConfirmedIsExemptEvenWithUntaggedDuplicates() {
        filmOf("Heat", true, new long[0], "a/Heat.m4v", "b/Heat.m4v");

        assertThat(admin.attention().items()).isEmpty();
    }

    @Test
    void twoUntaggedCopiesAreListedAndTheRuntimeDifferenceIsStatedWhenOver15Percent() {
        filmOf("Heat", false, new long[] {100, 140}, "a/Heat.m4v", "b/Heat.m4v");

        assertThat(several()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Heat");
            assertThat(item.detail()).isEqualTo("2 untagged versions of one film, runtimes differ by 29%");
            assertThat(item.files()).containsExactly("Heat.m4v", "Heat.m4v");
        });
    }

    @Test
    void twoUntaggedCopiesWithCloseRuntimesAreListedWithoutTheDifference() {
        filmOf("Heat", false, new long[] {100, 110}, "a/Heat.m4v", "b/Heat.m4v");

        assertThat(several()).singleElement().extracting(AttentionItem::detail)
            .isEqualTo("2 untagged versions of one film");
    }

    @Test
    void aFileAndItsFolderWithDifferentEditionTagsAreAConflict() {
        filmOf("Heat", "Heat {edition-Extended}/Heat {edition-Theatrical}.m4v");

        assertThat(several()).singleElement().extracting(AttentionItem::detail)
            .isEqualTo("The edition tags in a file name and its folder disagree");
    }

    @Test
    void twoFilesWithTheSamePartNumberAreDuplicates() {
        filmOf("Heat", "a/Heat pt1.m4v", "b/Heat pt1.m4v");

        assertThat(several()).singleElement().extracting(AttentionItem::detail)
            .isEqualTo("Duplicate part numbers: 1");
    }

    @Test
    void partOneAndThreeHaveAGapAndPartTwoAloneIsMissingPartOne() {
        filmOf("Heat", "Heat pt1.m4v", "Heat pt3.m4v");
        filmOf("Alien", "Alien pt2.m4v");

        assertThat(several()).extracting(AttentionItem::title, AttentionItem::detail).containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("Heat", "Parts found: 1, 3, missing: 2"),
            org.assertj.core.api.Assertions.tuple("Alien", "Parts found: 2, missing: 1"));
    }

    @Test
    void partsAreCheckedPerEditionTagSoTwoTaggedCutsEachInTwoPartsAreFine() {
        filmOf("Heat", "Heat {edition-Extended} pt1.m4v", "Heat {edition-Extended} pt2.m4v",
            "Heat {edition-Theatrical} pt1.m4v", "Heat {edition-Theatrical} pt2.m4v");
        filmOf("Alien", "Alien {edition-Extended} pt1.m4v", "Alien {edition-Extended} pt3.m4v");

        assertThat(several()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Alien");
            assertThat(item.detail()).isEqualTo("Parts found: 1, 3, missing: 2");
        });
    }

    @Test
    void untaggedCopiesWithNoKnownRuntimeAreListedWithoutAComparison() {
        filmOf("Heat", false, new long[] {0, 0}, "a/Heat.m4v", "b/Heat.m4v");

        assertThat(several()).singleElement().extracting(AttentionItem::detail)
            .isEqualTo("2 untagged versions of one film");
    }

    @Test
    void aMissingFileIsLeftOutOfTheFileNamesOfAListedFilm() {
        Film heat = filmOf("Heat", "a/Heat.m4v", "b/Heat.m4v", "c/Heat.m4v");
        MediaFile gone = heat.getEditions().get(2).originalFile();
        gone.setPresence(Presence.MISSING);
        mediaFiles.save(gone);

        assertThat(several()).singleElement().extracting(AttentionItem::files)
            .isEqualTo(List.of("Heat.m4v", "Heat.m4v"));
    }

    @Test
    void aSingleFilePartOneAloneIsNotAGap() {
        filmOf("Heat", "Heat pt1.m4v");

        assertThat(admin.attention().items()).isEmpty();
    }

    @Test
    void filesWithDifferentTitlesAreSaidToLookLikeDifferentFilms() {
        filmOf("Heat", "Heat.m4v", "Alien.m4v");

        assertThat(several()).singleElement().extracting(AttentionItem::detail).asString()
            .startsWith("Files look like different films");
    }

    @Test
    void severalReasonsAreJoinedIntoOneLine() {
        filmOf("Heat", "Heat pt1.m4v", "Heat pt3.m4v", "Alien.m4v");

        assertThat(several()).singleElement().extracting(AttentionItem::detail).isEqualTo(
            "Files look like different films; Parts found: 1, 3, missing: 2");
    }

    @Test
    void aMissingFileDoesNotCountTowardsTheAmbiguity() {
        Film heat = filmOf("Heat", "a/Heat.m4v", "b/Heat.m4v");
        MediaFile second = heat.getEditions().get(1).originalFile();
        second.setPresence(Presence.MISSING);
        mediaFiles.save(second);

        assertThat(admin.attention().items()).isEmpty();
    }

    private Film twoPartFilm(boolean confirmed, String secondCodec, String secondFormat) {
        Film film = film(null, "The Two Towers", 2002, NOW);
        film.setEditionsConfirmed(confirmed);
        Edition edition = edition(film, null, "Original", 200 * MIN, null);
        MediaFile one = file(edition, "The Two Towers pt1.m4v");
        one.setPartNumber(1);
        one.setPixelFormat("yuv420p");
        MediaFile two = file(edition, "The Two Towers pt2.m4v");
        two.setPartNumber(2);
        two.setPixelFormat(secondFormat);
        two.setVideoCodec(secondCodec);
        return save(film);
    }

    @Test
    void partsThatDoNotMatchAreListedAsWillTranscodeEvenForAConfirmedFilm() {
        twoPartFilm(true, "h264", "yuv420p");

        assertThat(admin.attention().items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("parts-mismatch");
            assertThat(item.title()).isEqualTo("The Two Towers");
            assertThat(item.detail()).isEqualTo("Part 2: video codec is h264, part 1 has HEVC");
            assertThat(item.files()).containsExactly("The Two Towers pt1.m4v", "The Two Towers pt2.m4v");
        });
    }

    @Test
    void manyDifferencesAreCountedAfterTheFirst() {
        twoPartFilm(false, "h264", "yuv422p");

        assertThat(admin.attention().items()).singleElement().extracting(AttentionItem::detail)
            .isEqualTo("Part 2: video codec is h264, part 1 has HEVC (and 1 more)");
    }

    @Test
    void partsThatMatchAreNotListed() {
        twoPartFilm(false, "HEVC", "yuv420p");

        assertThat(admin.attention().items()).isEmpty();
    }
}
