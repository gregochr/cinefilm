package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Films named by an id in their file name: TMDB's find endpoint, and the matcher that fetches them directly. */
class TmdbTaggedLookupTest {

    private static final String FIND = "https://api.themoviedb.org/3/find/tt0113277?external_source=imdb_id";

    private final AtomicLong ticks = new AtomicLong();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TmdbClient client = new TmdbClient(builder.build(), TmdbFixtures.TOKEN, d -> { },
        () -> ticks.addAndGet(1_000_000_000L));
    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final TmdbMatcher matcher = new TmdbMatcher(tmdb);

    private static MovieDetails movie(long id) {
        return new MovieDetails(id, "Heat", "Heat", 1995, null, 170, List.of(), null, null, 0, List.of(), List.of(),
            null);
    }

    // ---- the client

    @Test
    void theFindEndpointGivesTheMovieIdUsingTheBearerToken() {
        server.expect(once(), requestTo(FIND))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TmdbFixtures.TOKEN))
            .andRespond(withSuccess("{\"movie_results\":[{\"id\":949,\"title\":\"Heat\"}],\"tv_results\":[]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.findMovieByImdbId("tt0113277")).contains(949L);
        server.verify();
    }

    @Test
    void aFindWithNoMovieResultIsEmpty() {
        server.expect(once(), requestTo(FIND)).andRespond(withSuccess("{\"movie_results\":[],\"tv_results\":[]}",
            MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FIND)).andRespond(withSuccess("{\"movie_results\":[{\"id\":\"x\"}]}",
            MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FIND)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.findMovieByImdbId("tt0113277")).isEmpty();
        assertThat(client.findMovieByImdbId("tt0113277")).isEmpty();
        assertThat(client.findMovieByImdbId("tt0113277")).isEmpty();
    }

    @Test
    void aFindThatTmdbAnswers404ForIsEmptyAndAnOutageIsUnavailable() {
        server.expect(once(), requestTo(FIND)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(FIND)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.findMovieByImdbId("tt0113277")).isEmpty();
        assertThatThrownBy(() -> client.findMovieByImdbId("tt0113277")).isInstanceOf(TmdbUnavailableException.class);
    }

    // ---- the matcher

    @Test
    void aTmdbTagFetchesThatMovieDirectlyWithoutSearchingOrScoring() {
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949)));

        MatchOutcome outcome = matcher.matchTagged(949L, null).orElseThrow();

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(949);
        assertThat(outcome.candidates()).isEmpty();
        verify(tmdb, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anImdbTagIsResolvedThroughFindThenFetched() {
        when(tmdb.findMovieByImdbId("tt0113277")).thenReturn(Optional.of(949L));
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949)));

        assertThat(matcher.matchTagged(null, "tt0113277").orElseThrow().movie().id()).isEqualTo(949);
    }

    @Test
    void aTmdbTagTmdbDoesNotKnowFallsBackToTheImdbTagThenToNothing() {
        when(tmdb.movie(1)).thenReturn(Optional.empty());
        when(tmdb.findMovieByImdbId("tt0113277")).thenReturn(Optional.of(949L));
        when(tmdb.movie(949)).thenReturn(Optional.of(movie(949)));

        assertThat(matcher.matchTagged(1L, "tt0113277").orElseThrow().movie().id()).isEqualTo(949);
        assertThat(matcher.matchTagged(1L, null)).isEmpty();
        when(tmdb.findMovieByImdbId("tt9999999")).thenReturn(Optional.empty());
        assertThat(matcher.matchTagged(null, "tt9999999")).isEmpty();
    }

    @Test
    void noTagMeansNoLookup() {
        assertThat(matcher.matchTagged(null, null)).isEmpty();
    }

    @Test
    void anOutageOnATaggedLookupIsAnUnavailableOutcomeNotAnException() {
        when(tmdb.movie(949)).thenThrow(new TmdbUnavailableException("down", null));

        MatchOutcome outcome = matcher.matchTagged(949L, null).orElseThrow();

        assertThat(outcome.status()).isEqualTo(Status.UNAVAILABLE);
    }
}
