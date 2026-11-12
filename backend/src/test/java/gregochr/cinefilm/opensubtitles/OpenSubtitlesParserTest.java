package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.opensubtitles.OsFixtures.TAKEN;
import static gregochr.cinefilm.opensubtitles.OsFixtures.item;
import static gregochr.cinefilm.opensubtitles.OsFixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Reading a search reply: the filters, the fields and every way the untrusted reply can be wrong. */
class OpenSubtitlesParserTest {

    private static OpenSubtitlesSearch parse(Object... items) {
        return OpenSubtitlesParser.parse(page(items), TAKEN);
    }

    private static int countOf(OsFixtures.Item item) {
        return parse(item).count();
    }

    private static OpenSubtitlesResult only(OsFixtures.Item item) {
        OpenSubtitlesSearch search = parse(item);
        assertThat(search.count()).isEqualTo(1);
        return search.best();
    }

    @Test
    void aGoodResultIsReadFieldByField() {
        OpenSubtitlesResult result = only(item(1234).set("fps", "24.0").set("download_count", "321")
            .set("from_trusted", "true").set("hearing_impaired", "true"));

        assertThat(result).isEqualTo(new OpenSubtitlesResult(1234, 6234, "Taken.2008.BluRay.x264", 24.0, 321, true,
            true, "2020-01-02T03:04:05Z", "Gold member", 1, "a.srt"));
    }

    @Test
    void theCountIsHowManyResultsRemainAndTheBestIsTheTopRankedOfThem() {
        OpenSubtitlesSearch search = parse(item(3).set("fps", "25.0"), item(2).set("fps", "23.976"),
            item(1).set("ai_translated", "true"), item(4).set("fps", "23.976").set("from_trusted", "true"));

        assertThat(search.count()).isEqualTo(3);
        assertThat(search.best().id()).isEqualTo(4);
    }

    @Test
    void noDataMeansNoResultsAndNoBest() {
        OpenSubtitlesSearch search = parse();

        assertThat(search.count()).isZero();
        assertThat(search.best()).isNull();
    }

    @Test
    void anAiTranslatedResultIsDropped() {
        assertThat(countOf(item(1).set("ai_translated", "true"))).isZero();
    }

    @Test
    void aMachineTranslatedResultIsDropped() {
        assertThat(countOf(item(1).set("machine_translated", "true"))).isZero();
    }

    @Test
    void aResultThatIsNotForeignPartsOnlyIsDroppedWhetherFalseMissingOrNotABoolean() {
        assertThat(countOf(item(1).set("foreign_parts_only", "false"))).isZero();
        assertThat(countOf(item(1).remove("foreign_parts_only"))).isZero();
        assertThat(countOf(item(1).set("foreign_parts_only", "\"true\""))).isZero();
        assertThat(countOf(item(1).set("foreign_parts_only", "1"))).isZero();
    }

    @Test
    void aFlagThatIsMissingOrNotABooleanIsNotTrue() {
        assertThat(countOf(item(1).remove("ai_translated").remove("machine_translated"))).isEqualTo(1);
        assertThat(countOf(item(1).set("ai_translated", "\"yes\""))).isEqualTo(1);
        OpenSubtitlesResult result = only(item(1).remove("from_trusted").set("hearing_impaired", "null"));
        assertThat(result.fromTrusted()).isFalse();
        assertThat(result.hearingImpaired()).isFalse();
    }

    @Test
    void aResultForAnotherFilmIsDropped() {
        assertThat(countOf(item(1).imdb(String.valueOf(TAKEN + 1)))).isZero();
        assertThat(countOf(item(1).imdb(String.valueOf(TAKEN - 1)))).isZero();
        assertThat(countOf(item(1).imdb(String.valueOf(TAKEN)))).isEqualTo(1);
    }

    @Test
    void aResultWhoseImdbIdIsMissingOrNotAWholeNumberIsDropped() {
        assertThat(countOf(item(1).set("feature_details", "{}"))).isZero();
        assertThat(countOf(item(1).remove("feature_details"))).isZero();
        assertThat(countOf(item(1).imdb("\"" + TAKEN + "\""))).isZero();
        assertThat(countOf(item(1).imdb(TAKEN + ".0"))).isZero();
        assertThat(countOf(item(1).imdb("null"))).isZero();
    }

    @Test
    void theIdMayBeANumberOrADigitStringAndAnythingElseDropsTheResult() {
        assertThat(only(item(1).id("7")).id()).isEqualTo(7);
        assertThat(only(item(1).id("\" 8 \"")).id()).isEqualTo(8);
        assertThat(countOf(item(1).id("\"abc\""))).isZero();
        assertThat(countOf(item(1).id("\"\""))).isZero();
        assertThat(countOf(item(1).id("0"))).isZero();
        assertThat(countOf(item(1).id("-3"))).isZero();
        assertThat(countOf(item(1).id("7.5"))).isZero();
        assertThat(countOf(item(1).id("[7]"))).isZero();
        assertThat(countOf(item(1).id("null"))).isZero();
        assertThat(countOf(item(1).noId())).isZero();
    }

    @Test
    void theFileIdMayBeANumberOrADigitStringAndAMissingOrBadOneDropsTheResult() {
        assertThat(only(item(1).fileId("55")).fileId()).isEqualTo(55);
        assertThat(only(item(1).fileId("\"56\"")).fileId()).isEqualTo(56);
        assertThat(countOf(item(1).fileId("0"))).isZero();
        assertThat(countOf(item(1).fileId("\"x\""))).isZero();
        assertThat(countOf(item(1).fileId("null"))).isZero();
        assertThat(countOf(item(1).set("files", "[]"))).isZero();
        assertThat(countOf(item(1).set("files", "[{}]"))).isZero();
        assertThat(countOf(item(1).set("files", "{}"))).isZero();
        assertThat(countOf(item(1).remove("files"))).isZero();
    }

    @Test
    void onlyTheFirstFileIsUsed() {
        OpenSubtitlesResult result = only(item(1).set("files", "[{\"file_id\":11},{\"file_id\":22}]"));

        assertThat(result.fileId()).isEqualTo(11);
    }

    @Test
    void aResultWithNoAttributesOrNotAnObjectIsDropped() {
        assertThat(parse("{}", "{\"id\":\"1\",\"attributes\":[]}", "5", "null", "\"text\"", "[]").count()).isZero();
    }

    @Test
    void theFrameRateIsKeptOnlyWhenItIsAPositiveFiniteNumber() {
        assertThat(only(item(1).set("fps", "25")).fps()).isEqualTo(25.0);
        assertThat(only(item(1).set("fps", "0.5")).fps()).isEqualTo(0.5);
        assertThat(only(item(1).remove("fps")).fps()).isNull();
        assertThat(only(item(1).set("fps", "\"23.976\"")).fps()).isNull();
        assertThat(only(item(1).set("fps", "0")).fps()).isNull();
        assertThat(only(item(1).set("fps", "-24")).fps()).isNull();
        assertThat(only(item(1).set("fps", "1e999")).fps()).isNull();
        assertThat(only(item(1).set("fps", "null")).fps()).isNull();
    }

    @Test
    void theDownloadCountIsAWholeNumberFromZeroToIntMax() {
        assertThat(only(item(1).set("download_count", "0")).downloadCount()).isZero();
        assertThat(only(item(1).set("download_count", "2147483647")).downloadCount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(only(item(1).set("download_count", "2147483648")).downloadCount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(only(item(1).set("download_count", "99999999999999")).downloadCount())
            .isEqualTo(Integer.MAX_VALUE);
        assertThat(only(item(1).set("download_count", "-5")).downloadCount()).isZero();
        assertThat(only(item(1).set("download_count", "5.5")).downloadCount()).isZero();
        assertThat(only(item(1).set("download_count", "\"12\"")).downloadCount()).isZero();
        assertThat(only(item(1).remove("download_count")).downloadCount()).isZero();
    }

    @Test
    void theReleaseIsCutToTwoHundredCharactersAndCleanedOfControlCharacters() {
        assertThat(only(item(1).set("release", q("a".repeat(199)))).release()).hasSize(199);
        assertThat(only(item(1).set("release", q("a".repeat(200)))).release()).hasSize(200);
        assertThat(only(item(1).set("release", q("a".repeat(201)))).release()).isEqualTo("a".repeat(200));
        assertThat(only(item(1).set("release", q("a".repeat(500)))).release()).hasSize(200);
        assertThat(only(item(1).set("release", "\"  Taken\\u0000\\n2008\\t\\u001b[0m \"")).release())
            .isEqualTo("Taken  2008  [0m");
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String splitAtTheEdge = "a".repeat(199) + "😀" + "b";
        String fitsExactly = "a".repeat(198) + "😀" + "b";

        assertThat(only(item(1).set("release", q(splitAtTheEdge))).release()).isEqualTo("a".repeat(199));
        assertThat(only(item(1).set("release", q(fitsExactly))).release()).isEqualTo("a".repeat(198) + "😀");
    }

    @Test
    void aMissingBlankOrNonTextReleaseIsNull() {
        assertThat(only(item(1).remove("release")).release()).isNull();
        assertThat(only(item(1).set("release", "\"   \"")).release()).isNull();
        assertThat(only(item(1).set("release", "\"\\n\\t\"")).release()).isNull();
        assertThat(only(item(1).set("release", "5")).release()).isNull();
        assertThat(only(item(1).set("release", "null")).release()).isNull();
    }

    @Test
    void theUploadDateAndTheUploadersRankAreKeptAsTextCutToFortyCharacters() {
        OpenSubtitlesResult long40 = only(item(1).set("upload_date", q("d".repeat(60)))
            .set("uploader", "{\"rank\":\"" + "r".repeat(60) + "\"}"));
        OpenSubtitlesResult none = only(item(1).remove("upload_date").remove("uploader"));
        OpenSubtitlesResult odd = only(item(1).set("upload_date", "20200102").set("uploader", "{\"rank\":7}"));

        assertThat(long40.uploadDate()).isEqualTo("d".repeat(40));
        assertThat(long40.uploaderRank()).isEqualTo("r".repeat(40));
        assertThat(none.uploadDate()).isNull();
        assertThat(none.uploaderRank()).isNull();
        assertThat(odd.uploadDate()).isNull();
        assertThat(odd.uploaderRank()).isNull();
    }

    @Test
    void aBodyThatIsNotJsonIsAnOutageNotNoResults() {
        assertThatThrownBy(() -> OpenSubtitlesParser.parse("<html>Bad gateway</html>", TAKEN))
            .isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles returned unreadable JSON");
        assertThatThrownBy(() -> OpenSubtitlesParser.parse("{\"data\":[", TAKEN))
            .isInstanceOf(OpenSubtitlesUnavailableException.class)
            .hasMessage("OpenSubtitles returned unreadable JSON");
    }

    @Test
    void aReplyWithoutADataListIsAnOutageNotNoResults() {
        for (String body : new String[] {"[]", "5", "null", "\"data\"", "{}", "{\"data\":null}", "{\"data\":{}}",
            "{\"data\":\"x\"}", "{\"message\":\"You cannot consume this service\"}", ""}) {
            assertThatThrownBy(() -> OpenSubtitlesParser.parse(body, TAKEN))
                .as(body).isInstanceOf(OpenSubtitlesUnavailableException.class);
        }
        assertThatThrownBy(() -> OpenSubtitlesParser.parse("{}", TAKEN))
            .hasMessage("OpenSubtitles returned an unexpected reply");
    }

    @Test
    void anEmptyDataListIsGenuinelyNoResults() {
        OpenSubtitlesSearch search = OpenSubtitlesParser.parse("{\"data\":[]}", TAKEN);

        assertThat(search.count()).isZero();
        assertThat(search.best()).isNull();
    }

    private static String q(String text) {
        return "\"" + text + "\"";
    }

    @Test
    void aSingleFileResultHasFileCountOne() {
        assertThat(only(item(1)).fileCount()).isEqualTo(1);
        assertThat(only(item(1).set("nb_cd", "1")).fileCount()).isEqualTo(1);
    }

    @Test
    void theFileCountIsTheLargerOfTheDiscCountAndTheFilesListed() {
        String two = "[{\"file_id\":11},{\"file_id\":12}]";
        assertThat(only(item(1).set("nb_cd", "2")).fileCount()).isEqualTo(2);
        assertThat(only(item(1).set("files", two)).fileCount()).isEqualTo(2);
        assertThat(only(item(1).set("nb_cd", "3").set("files", two)).fileCount()).isEqualTo(3);
        assertThat(only(item(1).set("nb_cd", "1").set("files", two)).fileCount()).isEqualTo(2);
        // the file id is the first file's
        assertThat(only(item(1).set("files", two)).fileId()).isEqualTo(11);
    }

    @Test
    void aDiscCountThatIsNotAWholeNumberOrIsOutOfRangeIsOneOrClamped() {
        assertThat(only(item(1).set("nb_cd", "\"2\"")).fileCount()).isEqualTo(1);
        assertThat(only(item(1).set("nb_cd", "null")).fileCount()).isEqualTo(1);
        assertThat(only(item(1).set("nb_cd", "0")).fileCount()).isEqualTo(1);
        assertThat(only(item(1).set("nb_cd", "-4")).fileCount()).isEqualTo(1);
        assertThat(only(item(1).set("nb_cd", "99999")).fileCount()).isEqualTo(1000);
        assertThat(only(item(1).remove("nb_cd")).fileCount()).isEqualTo(1);
    }

    @Test
    void theFirstFilesNameIsKeptCleanedAndCutToTwoHundredCharactersOrIsNullWhenMissing() {
        assertThat(only(item(1)).fileName()).isEqualTo("a.srt");
        assertThat(only(item(1).set("files", "[{\"file_id\":11,\"file_name\":\"x\\ty\\n\"}]")).fileName())
            .isEqualTo("x y");
        assertThat(only(item(1).set("files", "[{\"file_id\":11,\"file_name\":\"" + "n".repeat(300) + "\"}]"))
            .fileName()).hasSize(OpenSubtitlesParser.RELEASE_MAX);
        assertThat(only(item(1).set("files", "[{\"file_id\":11}]")).fileName()).isNull();
        assertThat(only(item(1).set("files", "[{\"file_id\":11,\"file_name\":5}]")).fileName()).isNull();
    }

    @Test
    void theSearchCarriesTheCandidatesInRankedOrderSingleFileOnlyWithTheBestFirst() {
        String two = "[{\"file_id\":11},{\"file_id\":22}]";
        OpenSubtitlesSearch search = parse(item(1).set("download_count", "10"), item(2).set("download_count", "500"),
            item(3).set("files", two).set("download_count", "9999"), item(4).set("ai_translated", "true"),
            item(5).set("release", "\"Taken.Extended.2008\"").set("download_count", "5"));

        assertThat(search.count()).isEqualTo(4);
        assertThat(search.best().id()).isEqualTo(2);
        assertThat(search.candidates()).extracting(OpenSubtitlesResult::id).containsExactly(2L, 1L, 5L);
    }

    @Test
    void aSearchWithNoResultsHasNoCandidates() {
        assertThat(parse().candidates()).isEmpty();
        assertThat(parse(item(1).set("files", "[{\"file_id\":11},{\"file_id\":22}]")).candidates()).isEmpty();
    }
}
