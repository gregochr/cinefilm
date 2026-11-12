package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** A movie's original and spoken languages: read from TMDB's response and validated, as TMDB text is untrusted. */
class TmdbClientLanguagesTest {

    private final AtomicLong ticks = new AtomicLong();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbClient client = new TmdbClient(builder.build(), TmdbFixtures.TOKEN, d -> { },
        () -> ticks.addAndGet(1_000_000_000L));

    private MovieDetails movieWith(long id, String languageFields) {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/" + id + "?append_to_response=credits"))
            .andRespond(withSuccess("{\"id\":" + id + languageFields + "}", MediaType.APPLICATION_JSON));
        return client.movie(id).orElseThrow();
    }

    @Test
    void aRecordedMovieCarriesItsOriginalAndSpokenLanguages() {
        server.expect(once(), requestTo("https://api.themoviedb.org/3/movie/949?append_to_response=credits"))
            .andRespond(withSuccess(TmdbFixtures.recorded("movie-949.json"), MediaType.APPLICATION_JSON));

        MovieDetails heat = client.movie(949).orElseThrow();

        assertThat(heat.originalLanguage()).isEqualTo("en");
        assertThat(heat.spokenLanguages()).containsExactly("en", "es");
    }

    @Test
    void spokenLanguagesKeepTmdbsOrderLowerCaseTheCodesAndDropRepeats() {
        MovieDetails movie = movieWith(1, ",\"original_language\":\"FR\",\"spoken_languages\":["
            + "{\"iso_639_1\":\"EN\",\"name\":\"English\"},{\"iso_639_1\":\"fr\"},{\"iso_639_1\":\"en\"},"
            + "{\"iso_639_1\":\"xx\"}]");

        assertThat(movie.originalLanguage()).isEqualTo("fr");
        assertThat(movie.spokenLanguages()).containsExactly("en", "fr", "xx");
    }

    @Test
    void missingLanguageFieldsGiveNoOriginalLanguageAndNoSpokenLanguages() {
        MovieDetails movie = movieWith(2, "");

        assertThat(movie.originalLanguage()).isNull();
        assertThat(movie.spokenLanguages()).isEmpty();
    }

    @Test
    void emptyLanguageFieldsGiveNothing() {
        MovieDetails empty = movieWith(3, ",\"original_language\":\"\",\"spoken_languages\":[]");

        assertThat(empty.originalLanguage()).isNull();
        assertThat(empty.spokenLanguages()).isEmpty();
    }

    @Test
    void nullLanguageFieldsGiveNothing() {
        MovieDetails nulls = movieWith(4, ",\"original_language\":null,\"spoken_languages\":null");

        assertThat(nulls.originalLanguage()).isNull();
        assertThat(nulls.spokenLanguages()).isEmpty();
    }

    @Test
    void languageFieldsOfTheWrongTypeAreIgnored() {
        MovieDetails wrongTypes = movieWith(5, ",\"original_language\":42,\"spoken_languages\":\"en\"");

        assertThat(wrongTypes.originalLanguage()).isNull();
        assertThat(wrongTypes.spokenLanguages()).isEmpty();
    }

    @Test
    void malformedLanguageCodesAreDroppedAndOnlyValidOnesKept() {
        MovieDetails badEntries = movieWith(6, ",\"original_language\":\"english\",\"spoken_languages\":["
            + "{\"iso_639_1\":\"<script>\"},{\"iso_639_1\":7},{\"name\":\"English\"},\"en\",{\"iso_639_1\":\"e\"},"
            + "{\"iso_639_1\":\"en,fr\"},{\"iso_639_1\":\"de\"}]");

        assertThat(badEntries.originalLanguage()).isNull();
        assertThat(badEntries.spokenLanguages()).containsExactly("de");
    }
}
