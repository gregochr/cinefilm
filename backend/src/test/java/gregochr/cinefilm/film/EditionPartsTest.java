package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** An edition made of several original files: their order, runtime and when the whole edition is hidden. */
class EditionPartsTest {

    private final Film film = film(1L, "Towers", 2002, NOW);
    private final Edition edition = edition(film, 10L, "Extended", 0, null);

    private MediaFile part(String path, int number, Long id, Long durationMs) {
        MediaFile file = file(edition, path);
        file.setPartNumber(number);
        file.setId(id);
        file.setDurationMs(durationMs);
        return file;
    }

    @Test
    void aNewEditionIsAutoGroupedAndAFileIsPartOne() {
        assertThat(edition.getGroupingSource()).isEqualTo(GroupingSource.AUTO);
        assertThat(new MediaFile().getPartNumber()).isEqualTo(1);
    }

    @Test
    void originalPartsAreOrderedByPartNumberThenIdAndLeaveOutPremadeRenditions() {
        part("b.m4v", 2, 5L, MIN);
        part("a2.m4v", 1, 9L, MIN);
        part("a1.m4v", 1, 3L, MIN);
        MediaFile premade = part("p.mp4", 1, 1L, MIN);
        premade.setKind(MediaKind.PREMADE_720);

        assertThat(edition.originalParts()).extracting(MediaFile::getPath).containsExactly("a1.m4v", "a2.m4v", "b.m4v");
        assertThat(edition.partCount()).isEqualTo(3);
    }

    @Test
    void filesNotYetSavedAreOrderedWithoutAnIdAfterTheSavedOnesOfTheSamePart() {
        part("saved.m4v", 1, 4L, MIN);
        part("new.m4v", 1, null, MIN);

        assertThat(edition.originalParts()).extracting(MediaFile::getPath).containsExactly("saved.m4v", "new.m4v");
    }

    @Test
    void theOriginalFileIsPartOneRegardlessOfListOrder() {
        part("pt2.m4v", 2, 1L, MIN);
        part("pt1.m4v", 1, 2L, MIN);

        assertThat(edition.originalFile().getPath()).isEqualTo("pt1.m4v");
    }

    @Test
    void anEditionWithNoOriginalHasNoOriginalFileNoPartsAndIsNotHidden() {
        MediaFile premade = file(edition, "p.mp4");
        premade.setKind(MediaKind.PREMADE_480);

        assertThat(edition.originalFile()).isNull();
        assertThat(edition.originalParts()).isEmpty();
        assertThat(edition.partCount()).isZero();
        assertThat(edition.hiddenFromBrowse()).isFalse();
    }

    @Test
    void recomputeRuntimeSumsThePartsAndTreatsAnUnknownDurationAsZero() {
        part("pt1.m4v", 1, 1L, 90 * MIN);
        part("pt2.m4v", 2, 2L, 45 * MIN);
        part("pt3.m4v", 3, 3L, null);

        edition.recomputeRuntime();

        assertThat(edition.getRuntimeMs()).isEqualTo(135 * MIN);
    }

    @Test
    void recomputeRuntimeLeavesAnEditionWithNoOriginalFilesAlone() {
        edition.setRuntimeMs(100 * MIN);

        edition.recomputeRuntime();

        assertThat(edition.getRuntimeMs()).isEqualTo(100 * MIN);
    }

    @Test
    void anEditionWithEveryPartPresentPlayableAndTimedIsShown() {
        part("pt1.m4v", 1, 1L, 90 * MIN);
        part("pt2.m4v", 2, 2L, 1L);

        assertThat(edition.hiddenFromBrowse()).isFalse();
    }

    @Test
    void aMissingPartHidesTheWholeEdition() {
        part("pt1.m4v", 1, 1L, 90 * MIN);
        part("pt2.m4v", 2, 2L, 45 * MIN).setPresence(Presence.MISSING);

        assertThat(edition.hiddenFromBrowse()).isTrue();
    }

    @Test
    void aPartThatCannotBePlayedHidesTheWholeEdition() {
        part("pt1.m4v", 1, 1L, 90 * MIN).setPlayability(Playability.UNREADABLE);
        part("pt2.m4v", 2, 2L, 45 * MIN);

        assertThat(edition.hiddenFromBrowse()).isTrue();
    }

    @Test
    void aPartWithNoDurationHidesAMultiPartEditionAtZeroAndNullButNotAtOneMillisecond() {
        MediaFile second = part("pt2.m4v", 2, 2L, 45 * MIN);
        part("pt1.m4v", 1, 1L, 90 * MIN);

        second.setDurationMs(null);
        assertThat(edition.hiddenFromBrowse()).as("null").isTrue();
        second.setDurationMs(0L);
        assertThat(edition.hiddenFromBrowse()).as("0").isTrue();
        second.setDurationMs(-1L);
        assertThat(edition.hiddenFromBrowse()).as("-1").isTrue();
        second.setDurationMs(1L);
        assertThat(edition.hiddenFromBrowse()).as("1").isFalse();
    }

    @Test
    void aSingleFilesMissingDurationNeverHidesItsEdition() {
        MediaFile only = part("a.m4v", 1, 1L, null);
        assertThat(edition.hiddenFromBrowse()).as("null").isFalse();
        only.setDurationMs(0L);
        assertThat(edition.hiddenFromBrowse()).as("0").isFalse();
        only.setPresence(Presence.MISSING);
        assertThat(edition.hiddenFromBrowse()).as("missing").isTrue();
    }
}
