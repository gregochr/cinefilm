package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.opensubtitles.CutTag.DIRECTORS_CUT;
import static gregochr.cinefilm.opensubtitles.CutTag.EXTENDED;
import static gregochr.cinefilm.opensubtitles.CutTag.SPECIAL_EDITION;
import static gregochr.cinefilm.opensubtitles.CutTag.THEATRICAL;
import static gregochr.cinefilm.opensubtitles.CutTag.UNRATED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The cut words read from release names and edition names, and how well one set of tags fits another. */
class CutTagTest {

    @Test
    void everyTagIsReadCaseInsensitivelyFromEditionAndReleaseNames() {
        assertThat(CutTag.in("Extended Edition")).containsExactly(EXTENDED);
        assertThat(CutTag.in("The.Lord.of.the.Rings.2003.EXTENDED.1080p.BluRay")).containsExactly(EXTENDED);
        assertThat(CutTag.in("Director's Cut")).containsExactly(DIRECTORS_CUT);
        assertThat(CutTag.in("Directors Cut")).containsExactly(DIRECTORS_CUT);
        assertThat(CutTag.in("Blade.Runner.Director.s.Cut.1982")).containsExactly(DIRECTORS_CUT);
        assertThat(CutTag.in("Aliens.DirectorsCut")).containsExactly(DIRECTORS_CUT);
        assertThat(CutTag.in("Special Edition")).containsExactly(SPECIAL_EDITION);
        assertThat(CutTag.in("Film.2001.UNRATED.BluRay")).containsExactly(UNRATED);
        assertThat(CutTag.in("Theatrical Cut")).containsExactly(THEATRICAL);
        assertThat(CutTag.in("film.theatrical.version")).containsExactly(THEATRICAL);
    }

    @Test
    void aNameWithNoCutWordHasNoTagsAndNullReadsAsNone() {
        assertThat(CutTag.in("Original")).isEmpty();
        assertThat(CutTag.in("Taken.2008.BluRay.x264")).isEmpty();
        assertThat(CutTag.in("")).isEmpty();
        assertThat(CutTag.in(null)).isEmpty();
    }

    @Test
    void aWordThatOnlyContainsATagIsNotATag() {
        assertThat(CutTag.in("Extendedly")).isEmpty();
        assertThat(CutTag.in("Untheatrical")).isEmpty();
        assertThat(CutTag.in("Subunrated")).isEmpty();
    }

    @Test
    void aNameCanCarryTwoTags() {
        assertThat(CutTag.in("Extended Director's Cut")).containsExactlyInAnyOrder(EXTENDED, DIRECTORS_CUT);
    }

    @Test
    void anEditionWithNoTagHasNoPreference() {
        assertThat(CutTag.affinity(Set.of(), Set.of(EXTENDED))).isZero();
        assertThat(CutTag.affinity(Set.of(), Set.of(THEATRICAL))).isZero();
        assertThat(CutTag.affinity(Set.of(), Set.of())).isZero();
    }

    @Test
    void aSharedTagScoresOneAndTheOppositeCutMinusOne() {
        assertThat(CutTag.affinity(Set.of(EXTENDED), Set.of(EXTENDED))).isEqualTo(1);
        assertThat(CutTag.affinity(Set.of(EXTENDED), Set.of(THEATRICAL))).isEqualTo(-1);
        assertThat(CutTag.affinity(Set.of(THEATRICAL), Set.of(THEATRICAL))).isEqualTo(1);
        assertThat(CutTag.affinity(Set.of(THEATRICAL), Set.of(EXTENDED))).isEqualTo(-1);
        assertThat(CutTag.affinity(Set.of(THEATRICAL), Set.of(UNRATED))).isEqualTo(-1);
        assertThat(CutTag.affinity(Set.of(UNRATED), Set.of(THEATRICAL))).isEqualTo(-1);
    }

    @Test
    void aResultWithNoTagOrWithAnotherNonOppositeTagIsNeutral() {
        assertThat(CutTag.affinity(Set.of(EXTENDED), Set.of())).isZero();
        assertThat(CutTag.affinity(Set.of(EXTENDED), Set.of(DIRECTORS_CUT))).isZero();
        assertThat(CutTag.affinity(Set.of(SPECIAL_EDITION), Set.of(UNRATED))).isZero();
    }

    @Test
    void anEditionWithBothTagsCountsASharedTagButNeverTreatsItsOwnTagAsTheOpposite() {
        Set<CutTag> both = Set.of(EXTENDED, THEATRICAL);

        assertThat(CutTag.affinity(both, Set.of(EXTENDED))).isEqualTo(1);
        assertThat(CutTag.affinity(both, Set.of(THEATRICAL))).isEqualTo(1);
        assertThat(CutTag.affinity(both, Set.of(EXTENDED, THEATRICAL))).isEqualTo(2);
        // Unrated is the opposite of the edition's theatrical side and is not one of its own tags.
        assertThat(CutTag.affinity(both, Set.of(UNRATED))).isEqualTo(-1);
    }

    @Test
    void aResultWithBothTagsAgainstAnExtendedEditionIsOneSharedMinusOneOpposite() {
        assertThat(CutTag.affinity(Set.of(EXTENDED), Set.of(EXTENDED, THEATRICAL))).isZero();
    }
}
