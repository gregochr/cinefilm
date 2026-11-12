package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The JSON kept on the film. */
class OpenSubtitlesResultTest {

    @Test
    void theJsonHasTheSevenKeptFieldsInOrder() {
        OpenSubtitlesResult result = new OpenSubtitlesResult(42, 99, "Taken.2008", 23.976, 300, true, false,
            "2020-01-02T03:04:05Z", "Gold member");

        assertThat(result.toJson()).isEqualTo("{\"id\":42,\"fileId\":99,\"release\":\"Taken.2008\",\"fps\":23.976,"
            + "\"downloadCount\":300,\"fromTrusted\":true,\"hearingImpaired\":false}");
    }

    @Test
    void aMissingReleaseAndFrameRateAreNullAndQuotesAreEscaped() {
        assertThat(new OpenSubtitlesResult(1, 2, null, null, 0, false, true, null, null).toJson())
            .isEqualTo("{\"id\":1,\"fileId\":2,\"release\":null,\"fps\":null,\"downloadCount\":0,"
                + "\"fromTrusted\":false,\"hearingImpaired\":true}");
        assertThat(new OpenSubtitlesResult(1, 2, "say \"hi\"", 25.0, 0, false, false, null, null).toJson())
            .contains("\"release\":\"say \\\"hi\\\"\"");
    }

    @Test
    void aWorstCaseReleaseStillFitsTheColumn() {
        String release = "\u0001".repeat(OpenSubtitlesParser.RELEASE_MAX);

        String json = new OpenSubtitlesResult(Long.MAX_VALUE, Long.MAX_VALUE, release, 23.976, Integer.MAX_VALUE,
            true, true, null, null).toJson();

        assertThat(json.length()).isLessThan(2000);
    }
}
