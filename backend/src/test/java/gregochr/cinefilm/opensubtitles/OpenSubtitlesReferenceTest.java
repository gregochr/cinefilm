package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The cue version a reference keeps, so a check or fit that read older cues can tell. */
class OpenSubtitlesReferenceTest {

    @Test
    void aNewReferenceStartsAtVersionZero() {
        assertThat(new OpenSubtitlesReference().getCuesVersion()).isZero();
    }

    @Test
    void replacingTheCuesWithDifferentOnesRaisesTheVersionEachTime() {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();

        reference.replaceCues("one");
        reference.replaceCues("two");

        assertThat(reference.getCuesText()).isEqualTo("two");
        assertThat(reference.getCuesVersion()).isEqualTo(2);
    }

    @Test
    void replacingTheCuesWithTheSameOnesLeavesTheVersionAlone() {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.replaceCues("one");

        reference.replaceCues("one");

        assertThat(reference.getCuesVersion()).isEqualTo(1);
    }

    @Test
    void clearingTheCuesOfAUnusableDownloadIsNoChangeOnANewReference() {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();

        reference.replaceCues("");

        assertThat(reference.getCuesVersion()).isZero();
    }
}
