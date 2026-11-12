package gregochr.cinefilm.opensubtitles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The JSON kept on the film. */
class OpenSubtitlesResultTest {

    @Test
    void theJsonHasTheEightKeptFieldsInOrder() {
        OpenSubtitlesResult result = new OpenSubtitlesResult(42, 99, "Taken.2008", 23.976, 300, true, false,
            "2020-01-02T03:04:05Z", "Gold member");

        assertThat(result.toJson()).isEqualTo("{\"id\":42,\"fileId\":99,\"release\":\"Taken.2008\",\"fps\":23.976,"
            + "\"downloadCount\":300,\"fromTrusted\":true,\"hearingImpaired\":false,\"fileCount\":1}");
    }

    @Test
    void aMissingReleaseAndFrameRateAreNullAndQuotesAreEscaped() {
        assertThat(new OpenSubtitlesResult(1, 2, null, null, 0, false, true, null, null).toJson())
            .isEqualTo("{\"id\":1,\"fileId\":2,\"release\":null,\"fps\":null,\"downloadCount\":0,"
                + "\"fromTrusted\":false,\"hearingImpaired\":true,\"fileCount\":1}");
        assertThat(new OpenSubtitlesResult(1, 2, "say \"hi\"", 25.0, 0, false, false, null, null).toJson())
            .contains("\"release\":\"say \\\"hi\\\"\"");
    }

    @Test
    void theFileCountIsKeptAndReadBack() {
        OpenSubtitlesResult two = new OpenSubtitlesResult(42, 99, "LOTR.Extended", 23.976, 300, true, false, null,
            null, 2);

        assertThat(two.toJson()).endsWith("\"fileCount\":2}");
        assertThat(two.isMultiFile()).isTrue();
        OpenSubtitlesResult back = OpenSubtitlesResult.fromJson(two.toJson());
        assertThat(back.fileCount()).isEqualTo(2);
        assertThat(back.isMultiFile()).isTrue();
        assertThat(back.fileId()).isEqualTo(99);
        assertThat(back.release()).isEqualTo("LOTR.Extended");
        assertThat(back.fps()).isEqualTo(23.976);
        assertThat(back.downloadCount()).isEqualTo(300);
        assertThat(back.fromTrusted()).isTrue();
    }

    @Test
    void aSingleFileResultIsNotMultiFile() {
        assertThat(new OpenSubtitlesResult(1, 2, null, null, 0, false, false, null, null).isMultiFile()).isFalse();
        assertThat(new OpenSubtitlesResult(1, 2, null, null, 0, false, false, null, null, 1).isMultiFile())
            .isFalse();
    }

    @Test
    void storedJsonFromBeforeTheFileCountExistedReadsAsSingleFile() {
        OpenSubtitlesResult back = OpenSubtitlesResult.fromJson("{\"id\":1,\"fileId\":2,\"release\":null,"
            + "\"fps\":null,\"downloadCount\":0,\"fromTrusted\":false,\"hearingImpaired\":false}");

        assertThat(back.fileCount()).isEqualTo(1);
        assertThat(back.release()).isNull();
        assertThat(back.fps()).isNull();
    }

    @Test
    void anOddFileCountInStoredJsonIsClamped() {
        for (String count : new String[] {"0", "-3", "\"x\"", "5000", "null"}) {
            OpenSubtitlesResult back = OpenSubtitlesResult.fromJson(
                "{\"id\":1,\"fileId\":2,\"fileCount\":" + count + "}");
            assertThat(back.fileCount()).as(count).isEqualTo("5000".equals(count) ? 1000 : 1);
        }
    }

    @Test
    void storedJsonThatIsNotUsableReadsAsNull() {
        for (String bad : new String[] {"{not json", "{}", "{\"id\":0,\"fileId\":2}", "{\"id\":1,\"fileId\":\"2\"}",
            "{\"id\":1}", "{\"id\":1,\"fileId\":-1}"}) {
            assertThat(OpenSubtitlesResult.fromJson(bad)).as(bad).isNull();
        }
    }

    @Test
    void aWorstCaseReleaseStillFitsTheColumn() {
        String release = "\u0001".repeat(OpenSubtitlesParser.RELEASE_MAX);

        String json = new OpenSubtitlesResult(Long.MAX_VALUE, Long.MAX_VALUE, release, 23.976, Integer.MAX_VALUE,
            true, true, null, null).toJson();

        assertThat(json.length()).isLessThan(2000);
    }
}
