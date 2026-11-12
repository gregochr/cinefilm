package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SidecarNamesTest {

    @Test
    void bothSuffixesInAnyCaseNameAForcedSubtitle() {
        assertThat(SidecarNames.matches("Taken.en.forced.srt")).isTrue();
        assertThat(SidecarNames.matches("Taken.forced.en.srt")).isTrue();
        assertThat(SidecarNames.matches("TAKEN.EN.FORCED.SRT")).isTrue();
        assertThat(SidecarNames.matches("Taken.Forced.EN.Srt")).isTrue();
    }

    @Test
    void otherSubtitleNamesAreNotForcedSubtitles() {
        assertThat(SidecarNames.matches("Taken.en.srt")).isFalse();
        assertThat(SidecarNames.matches("Taken.forced.srt")).isFalse();
        assertThat(SidecarNames.matches("Taken.en.forced.srt.bak")).isFalse();
        assertThat(SidecarNames.matches("Taken.m4v")).isFalse();
    }

    @Test
    void aBareSuffixWithNoStemIsNotAForcedSubtitle() {
        assertThat(SidecarNames.matches(".en.forced.srt")).isFalse();
        assertThat(SidecarNames.matches("x.en.forced.srt")).isTrue();
    }

    @Test
    void theStemIsThePathWithoutTheSuffixKeepingItsCase() {
        assertThat(SidecarNames.stem("Taken (2008)/Taken.en.forced.srt")).isEqualTo("Taken (2008)/Taken");
        assertThat(SidecarNames.stem("A/B.forced.en.srt")).isEqualTo("A/B");
        assertThat(SidecarNames.stem("A/No Time to Die.EN.FORCED.SRT")).isEqualTo("A/No Time to Die");
    }

    @Test
    void theStemOfAnotherNameIsAnError() {
        assertThatThrownBy(() -> SidecarNames.stem("Taken.srt")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Not a forced subtitle name: Taken.srt");
    }
}
