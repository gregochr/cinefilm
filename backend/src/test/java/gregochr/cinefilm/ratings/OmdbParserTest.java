package gregochr.cinefilm.ratings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ratings.OmdbResult.Outcome;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OmdbParserTest {

    private static final String HEAT = """
        {"Title":"Heat","imdbRating":"8.3","imdbVotes":"712,345","imdbID":"tt0113277",
         "Ratings":[{"Source":"Internet Movie Database","Value":"8.3/10"},
                    {"Source":"Rotten Tomatoes","Value":"84%"},
                    {"Source":"Metacritic","Value":"76/100"}],
         "tomatoURL":"https://www.rottentomatoes.com/m/heat_1995","Response":"True"}
        """;

    private static OmdbRatings ratings(String json) {
        OmdbResult result = OmdbParser.parse(json);
        assertThat(result.outcome()).isEqualTo(Outcome.FOUND);
        return result.ratings();
    }

    @Test
    void aFullReplyGivesTheScoreTheVotesWithoutCommasAndTheRottenTomatoesScoreAndPage() {
        assertThat(ratings(HEAT)).isEqualTo(new OmdbRatings(new BigDecimal("8.3"), 712345, 84,
            "https://www.rottentomatoes.com/m/heat_1995"));
    }

    @Test
    void votesWithSeveralCommasAreRead() {
        assertThat(ratings("{\"imdbVotes\":\"1,234,567\",\"Response\":\"True\"}").imdbVotes()).isEqualTo(1234567);
    }

    @Test
    void notAvailableValuesAreNullNotErrors() {
        OmdbRatings none = ratings("""
            {"imdbRating":"N/A","imdbVotes":"N/A",
             "Ratings":[{"Source":"Rotten Tomatoes","Value":"N/A"}],"tomatoURL":"N/A","Response":"True"}
            """);

        assertThat(none).isEqualTo(new OmdbRatings(null, null, null, null));
    }

    @Test
    void aMissingRottenTomatoesEntryMeansNoScoreAndAMissingKeyMeansNoValue() {
        OmdbRatings onlyImdb = ratings("""
            {"imdbRating":"7.0","Ratings":[{"Source":"Internet Movie Database","Value":"7.0/10"}],
             "Response":"True"}
            """);

        assertThat(onlyImdb.rtScore()).isNull();
        assertThat(onlyImdb.rtUrl()).isNull();
        assertThat(onlyImdb.imdbVotes()).isNull();
        assertThat(onlyImdb.imdbRating()).isEqualByComparingTo("7.0");
        assertThat(ratings("{\"Response\":\"True\"}")).isEqualTo(new OmdbRatings(null, null, null, null));
    }

    @Test
    void blankAndNonStringValuesAreNull() {
        OmdbRatings none = ratings("""
            {"imdbRating":"  ","imdbVotes":5,"tomatoURL":"","Ratings":[{"Source":"Rotten Tomatoes"}],
             "Response":"True"}
            """);

        assertThat(none).isEqualTo(new OmdbRatings(null, null, null, null));
    }

    @Test
    void unparseableOrOutOfRangeNumbersAreNull() {
        assertThat(ratings("{\"imdbRating\":\"abc\",\"imdbVotes\":\"12x\"}"))
            .isEqualTo(new OmdbRatings(null, null, null, null));
        assertThat(ratings("{\"imdbRating\":\"10.5\"}").imdbRating()).isNull();
        assertThat(ratings("{\"imdbRating\":\"-1\"}").imdbRating()).isNull();
        assertThat(ratings("{\"imdbVotes\":\"-5\"}").imdbVotes()).isNull();
        assertThat(ratings("{\"imdbVotes\":\"99,999,999,999\"}").imdbVotes()).isNull();
    }

    @Test
    void theScoreIsKeptToOneDecimalAndTheEdgesOfTheRangeAreValid() {
        assertThat(ratings("{\"imdbRating\":\"8\"}").imdbRating()).isEqualTo(new BigDecimal("8.0"));
        assertThat(ratings("{\"imdbRating\":\"10.0\"}").imdbRating()).isEqualByComparingTo("10.0");
        assertThat(ratings("{\"imdbRating\":\"0.0\"}").imdbRating()).isEqualByComparingTo("0.0");
        assertThat(ratings("{\"imdbRating\":\"8.25\"}").imdbRating()).isEqualTo(new BigDecimal("8.3"));
    }

    @Test
    void aRottenTomatoesPercentageMustBeZeroToOneHundredWithAPercentSign() {
        assertThat(ratings(rt("0%")).rtScore()).isEqualTo(0);
        assertThat(ratings(rt("100%")).rtScore()).isEqualTo(100);
        assertThat(ratings(rt("101%")).rtScore()).isNull();
        assertThat(ratings(rt("-1%")).rtScore()).isNull();
        assertThat(ratings(rt("84")).rtScore()).isNull();
        assertThat(ratings(rt("eighty%")).rtScore()).isNull();
    }

    private static String rt(String value) {
        return "{\"Ratings\":[{\"Source\":\"Rotten Tomatoes\",\"Value\":\"" + value + "\"}],\"Response\":\"True\"}";
    }

    @Test
    void theRottenTomatoesEntryIsFoundWhateverItsPositionInTheList() {
        OmdbRatings late = ratings("""
            {"Ratings":[{"Source":"Metacritic","Value":"76/100"},{"Source":"Rotten Tomatoes","Value":"91%"}]}
            """);

        assertThat(late.rtScore()).isEqualTo(91);
    }

    @Test
    void onlyAnHttpOrHttpsPageIsKept() {
        assertThat(url("http://www.rottentomatoes.com/m/heat_1995"))
            .isEqualTo("http://www.rottentomatoes.com/m/heat_1995");
        assertThat(url("https://www.rottentomatoes.com/m/heat_1995"))
            .isEqualTo("https://www.rottentomatoes.com/m/heat_1995");
        assertThat(url("HTTPS://www.rottentomatoes.com/m/x")).isEqualTo("HTTPS://www.rottentomatoes.com/m/x");
        assertThat(url("javascript:alert(1)")).isNull();
        assertThat(url("ftp://example.com/x")).isNull();
        assertThat(url("//example.com/x")).isNull();
        assertThat(url("https:///nohost")).isNull();
        assertThat(url("not a url")).isNull();
        assertThat(url("N/A")).isNull();
    }

    @Test
    void aPageLongerThanTheColumnIsDropped() {
        String atLimit = "https://example.com/" + "a".repeat(OmdbParser.URL_MAX - "https://example.com/".length());

        assertThat(url(atLimit)).isEqualTo(atLimit);
        assertThat(url(atLimit + "a")).isNull();
    }

    private static String url(String value) {
        return ratings("{\"tomatoURL\":\"" + value + "\"}").rtUrl();
    }

    @Test
    void responseFalseMeansNoRatings() {
        OmdbResult result = OmdbParser.parse("{\"Response\":\"False\",\"Error\":\"Incorrect IMDb ID.\"}");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(result.ratings()).isNull();
        assertThat(OmdbParser.parse("{\"Response\":\"False\"}").outcome()).isEqualTo(Outcome.NOT_FOUND);
    }

    @Test
    void theRequestLimitErrorMeansStopForToday() {
        OmdbResult result = OmdbParser.parse("{\"Response\":\"False\",\"Error\":\"Request limit reached!\"}");

        assertThat(result.outcome()).isEqualTo(Outcome.LIMIT_REACHED);
        assertThat(result.ratings()).isNull();
    }

    @Test
    void anUnreadableBodyIsAnOutageNotAnEmptyAnswer() {
        assertThatThrownBy(() -> OmdbParser.parse("<html>")).isInstanceOf(OmdbUnavailableException.class)
            .hasMessage("OMDb returned unreadable JSON");
        assertThatThrownBy(() -> OmdbParser.parse("[1]")).isInstanceOf(OmdbUnavailableException.class)
            .hasMessage("OMDb returned an unexpected reply");
    }
}
