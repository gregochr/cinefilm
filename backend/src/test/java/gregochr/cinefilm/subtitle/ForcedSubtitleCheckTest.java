package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import org.junit.jupiter.api.Test;

/** When a stored check still describes what is served: version, reference and IMDb id must all agree. */
class ForcedSubtitleCheckTest {

    private static OpenSubtitlesReference reference(Long id, String imdbId) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(id);
        reference.setImdbId(imdbId);
        reference.setOsFileId(9);
        return reference;
    }

    private static ForcedSubtitleCheck check() {
        ForcedSubtitleCheck check = new ForcedSubtitleCheck();
        check.setServedVersion("v1");
        check.setReferenceId(5L);
        return check;
    }

    @Test
    void itIsCurrentOnlyWhenTheVersionTheReferenceAndTheImdbIdAllMatch() {
        ForcedSubtitleCheck check = check();

        assertThat(check.isCurrent("v1", "tt1", reference(5L, "tt1"), 9)).isTrue();
        assertThat(check.isCurrent("v2", "tt1", reference(5L, "tt1"), 9)).isFalse();
        assertThat(check.isCurrent("v1", "tt1", reference(6L, "tt1"), 9)).isFalse();
        assertThat(check.isCurrent("v1", "tt2", reference(5L, "tt1"), 9)).isFalse();
        assertThat(check.isCurrent("v1", "tt1", null, 9)).isFalse();
        // The film's best file is another one now.
        assertThat(check.isCurrent("v1", "tt1", reference(5L, "tt1"), 10)).isFalse();
        assertThat(check.isCurrent(null, "tt1", reference(5L, "tt1"), 9)).isFalse();
    }

    @Test
    void reasonsAreSplitOnNewlinesAndNoneIsAnEmptyList() {
        ForcedSubtitleCheck check = check();

        assertThat(check.reasonList()).isEmpty();
        check.setReasons("one\ntwo");
        assertThat(check.reasonList()).containsExactly("one", "two");
        check.setReasons("only");
        assertThat(check.reasonList()).containsExactly("only");
    }
}
