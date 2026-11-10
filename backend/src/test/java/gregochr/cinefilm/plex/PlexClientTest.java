package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexImage;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The Plex client against synthetic JSON (never the owner's library), with a fake sleeper. */
class PlexClientTest {

    private static final String BASE = "http://plex.test:32400";
    private static final String TOKEN = "tok-SECRET-123";

    private final List<Duration> sleeps = new ArrayList<>();
    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final PlexClient client = new PlexClient(builder.build(), BASE, TOKEN, sleeps::add, 4, 1000, 100);
    private final PlexClient big = new PlexClient(builder.build(), BASE, TOKEN, sleeps::add, 4, 1_000_000, 100);

    private static final String SECTIONS = "{\"MediaContainer\":{\"Directory\":["
        + "{\"key\":\"1\",\"type\":\"movie\",\"title\":\"Films\"},"
        + "{\"key\":\"2\",\"type\":\"show\",\"title\":\"TV\"},"
        + "{\"key\":\"3\",\"type\":\"movie\",\"title\":\"More\"},"
        + "{\"type\":\"movie\",\"title\":\"No key\"}]}}";

    private static String page(int total, int from, int count) {
        StringBuilder items = new StringBuilder();
        for (int i = from; i < from + count; i++) {
            items.append(i == from ? "" : ",").append("{\"ratingKey\":\"").append(i).append("\",\"title\":\"Film ")
                .append(i).append("\"}");
        }
        return "{\"MediaContainer\":{\"totalSize\":" + total + ",\"Metadata\":[" + items + "]}}";
    }

    @Test
    void configuredNeedsBothAnAddressAndAToken() {
        assertThat(client.configured()).isTrue();
        assertThat(new PlexClient(builder.build(), "", TOKEN, sleeps::add, 4, 1, 1).configured()).isFalse();
        assertThat(new PlexClient(builder.build(), "  ", TOKEN, sleeps::add, 4, 1, 1).configured()).isFalse();
        assertThat(new PlexClient(builder.build(), BASE, "", sleeps::add, 4, 1, 1).configured()).isFalse();
        assertThat(new PlexClient(builder.build(), BASE, "  ", sleeps::add, 4, 1, 1).configured()).isFalse();
        assertThat(new PlexClient(builder.build(), BASE, null, sleeps::add, 4, 1, 1).configured()).isFalse();
        assertThat(new PlexClient(builder.build(), null, TOKEN, sleeps::add, 4, 1, 1).configured()).isFalse();
    }

    @Test
    void sectionsAreReadWithTheTokenInTheHeaderOnlyAndOnlyMovieSectionsWithAKeyKept() {
        server.expect(once(), requestTo(BASE + "/library/sections")).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Plex-Token", TOKEN))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
            .andRespond(request -> {
                assertThat(request.getURI().toString()).doesNotContain(TOKEN).doesNotContain("X-Plex-Token");
                assertThat(request.getURI().getHost()).isEqualTo("plex.test");
                return withSuccess(SECTIONS, MediaType.APPLICATION_JSON).createResponse(request);
            });

        assertThat(client.movieSections()).containsExactly("1", "3");
        server.verify();
    }

    @Test
    void filmsArePagedUntilTotalSizeIsReachedWithTheContainerHeaders() {
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andExpect(method(HttpMethod.GET)).andExpect(header("X-Plex-Container-Start", "0"))
            .andExpect(header("X-Plex-Container-Size", "200")).andExpect(header("X-Plex-Token", TOKEN))
            .andRespond(withSuccess(page(450, 0, 200), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andExpect(header("X-Plex-Container-Start", "200")).andExpect(header("X-Plex-Container-Size", "200"))
            .andRespond(withSuccess(page(450, 200, 200), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andExpect(header("X-Plex-Container-Start", "400"))
            .andRespond(withSuccess(page(450, 400, 50), MediaType.APPLICATION_JSON));

        List<PlexFilm> films = big.films("1");

        assertThat(films).hasSize(450);
        assertThat(films.get(0).title()).isEqualTo("Film 0");
        assertThat(films.get(449).title()).isEqualTo("Film 449");
        server.verify();
    }

    @Test
    void pagingStopsWhenTotalSizeIsReachedEvenAfterAFullPage() {
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(page(200, 0, 200), MediaType.APPLICATION_JSON));

        assertThat(big.films("1")).hasSize(200);
        server.verify();
    }

    @Test
    void pagingStopsOnAnEmptyPageEvenIfTotalSizeClaimsMore() {
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(page(500, 0, 2), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andExpect(header("X-Plex-Container-Start", "2"))
            .andRespond(withSuccess(page(500, 2, 0), MediaType.APPLICATION_JSON));

        assertThat(client.films("1")).hasSize(2);
        server.verify();
    }

    @Test
    void withoutATotalSizeAShortPageEndsTheListAndAFullPageContinues() {
        String noTotalFull = page(0, 0, 200).replace("\"totalSize\":0,", "");
        String noTotalShort = page(0, 200, 3).replace("\"totalSize\":0,", "");
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(noTotalFull, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(noTotalShort, MediaType.APPLICATION_JSON));

        assertThat(big.films("1")).hasSize(203);
        server.verify();
    }

    @Test
    void aFilmIsReadWithGuidsEditionsTimesAndEveryPartInOrder() {
        String body = "{\"MediaContainer\":{\"totalSize\":1,\"Metadata\":[{\"ratingKey\":\"88\",\"title\":\"Heat\","
            + "\"titleSort\":\"Heat\",\"year\":1995,\"summary\":\"A crew.\",\"editionTitle\":\"Director's Cut\","
            + "\"addedAt\":1700000000,\"updatedAt\":1700000100,\"thumb\":\"/library/metadata/88/thumb/1\","
            + "\"art\":\"/library/metadata/88/art/2\",\"viewCount\":2,\"viewOffset\":90000,"
            + "\"lastViewedAt\":1700000200,\"Guid\":[{\"id\":\"imdb://tt0113277\"},{\"id\":\"tmdb://949\"},"
            + "{\"x\":1}],\"Media\":[{\"Part\":[{\"file\":\"/m/Heat.m4v\"},{\"size\":1}]},"
            + "{\"Part\":[{\"file\":\"/m/Heat 2.m4v\"}]}]}]}}";
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        PlexFilm film = client.films("1").get(0);

        assertThat(film).isEqualTo(new PlexFilm("88", "Heat", "Heat", 1995, "A crew.", "Director's Cut",
            Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_100L),
            "/library/metadata/88/thumb/1", "/library/metadata/88/art/2", 949L, 2, 90_000L,
            Instant.ofEpochSecond(1_700_000_200L),
            List.of(new PlexModels.PlexMedia(List.of("/m/Heat.m4v")),
                new PlexModels.PlexMedia(List.of("/m/Heat 2.m4v")))));
    }

    @Test
    void everyPartOfEveryMediaIsKeptInPlexsOrderAndMediaWithoutAFileAreDropped() {
        String body = "{\"MediaContainer\":{\"totalSize\":1,\"Metadata\":[{\"title\":\"Two Towers\",\"Media\":["
            + "{\"Part\":[{\"file\":\"/m/B-pt2.m4v\"},{\"file\":\"/m/A-pt1.m4v\"}]},"
            + "{\"Part\":[{\"size\":1}]},{\"Part\":[{\"file\":\"/m/Theatrical.m4v\"}]}]}]}}";
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        PlexFilm film = client.films("1").get(0);

        assertThat(film.media()).containsExactly(
            new PlexModels.PlexMedia(List.of("/m/B-pt2.m4v", "/m/A-pt1.m4v")),
            new PlexModels.PlexMedia(List.of("/m/Theatrical.m4v")));
        assertThat(film.files()).containsExactly("/m/B-pt2.m4v", "/m/A-pt1.m4v", "/m/Theatrical.m4v");
    }

    @Test
    void aFilmWithMissingFieldsGetsNullsAndZerosNotErrors() {
        String body = "{\"MediaContainer\":{\"totalSize\":1,\"Metadata\":[{\"title\":\"  \",\"year\":0,"
            + "\"addedAt\":0,\"lastViewedAt\":-5}]}}";
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        PlexFilm film = client.films("1").get(0);

        assertThat(film).isEqualTo(new PlexFilm(null, null, null, null, null, null, null, null, null, null, null,
            0, 0, null, List.of()));
    }

    @Test
    void aSectionWithNoMetadataIsAnEmptyList() {
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess("{\"MediaContainer\":{\"totalSize\":0}}", MediaType.APPLICATION_JSON));

        assertThat(client.films("1")).isEmpty();
    }

    @Test
    void collectionTitlesArePagedAndBlankOnesSkipped() {
        server.expect(once(), requestTo(BASE + "/library/sections/1/collections"))
            .andExpect(header("X-Plex-Container-Start", "0"))
            .andRespond(withSuccess("{\"MediaContainer\":{\"totalSize\":3,\"Metadata\":[{\"title\":\"Bond\"},"
                + "{\"title\":\"\"},{\"title\":\"Heat\"}]}}", MediaType.APPLICATION_JSON));

        assertThat(client.collections("1")).containsExactly("Bond", "Heat");
        server.verify();
    }

    @Test
    void anImageIsAskedForThroughTheResizerWithTheSourcePathEncodedAndAnImageAccept() {
        byte[] jpeg = {1, 2, 3};
        String url = BASE + "/photo/:/transcode?width=600&height=900&minSize=1&upscale=0"
            + "&url=%2Flibrary%2Fmetadata%2F88%2Fthumb%2F1";
        server.expect(once(), requestTo(url)).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Plex-Token", TOKEN))
            .andExpect(header(HttpHeaders.ACCEPT, "image/jpeg, image/png"))
            .andRespond(request -> {
                assertThat(request.getURI().toString()).doesNotContain(TOKEN);
                return withSuccess(jpeg, MediaType.IMAGE_JPEG).createResponse(request);
            });

        PlexImage image = client.image("/library/metadata/88/thumb/1", 600, 900);

        assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        server.verify();
    }

    @Test
    void aBackdropAsksForTheBackdropSize() {
        server.expect(once(), requestTo(BASE + "/photo/:/transcode?width=1920&height=1080&minSize=1&upscale=0"
                + "&url=%2Flibrary%2Fmetadata%2F88%2Fart%2F2"))
            .andRespond(withSuccess(new byte[] {9}, MediaType.IMAGE_PNG));

        assertThat(client.image("/library/metadata/88/art/2", 1920, 1080).contentType()).isEqualTo("image/png");
        server.verify();
    }

    @Test
    void everyRequestIsAGetToTheConfiguredHostOnly() {
        List<URI> seen = new ArrayList<>();
        List<HttpMethod> methods = new ArrayList<>();
        server.expect(times(3), request -> {
            seen.add(request.getURI());
            methods.add(request.getMethod());
        }).andRespond(request -> withSuccess(SECTIONS, MediaType.APPLICATION_JSON).createResponse(request));

        client.movieSections();
        client.reachable();
        client.movieSections();

        assertThat(methods).containsOnly(HttpMethod.GET);
        assertThat(seen).allSatisfy(uri -> {
            assertThat(uri.getScheme()).isEqualTo("http");
            assertThat(uri.getHost()).isEqualTo("plex.test");
            assertThat(uri.getPort()).isEqualTo(32400);
        });
    }

    @Test
    void aRedirectIsNotFollowedAndIsAnError() {
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "http://evil.example/steal"));

        assertThatThrownBy(client::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Plex answered status 302 to plex sections");
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aClientErrorIsNotRetried() {
        server.expect(once(), requestTo(BASE + "/library/sections")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(client::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Plex answered status 401 to plex sections");
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aServerErrorIsRetriedWithBackoffThenSucceeds() {
        server.expect(once(), requestTo(BASE + "/library/sections")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withSuccess(SECTIONS, MediaType.APPLICATION_JSON));

        assertThat(client.movieSections()).containsExactly("1", "3");
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
        server.verify();
    }

    @Test
    void threeRetriesThenItGivesUpNamingTheCallAndTheStatus() {
        server.expect(times(4), requestTo(BASE + "/library/sections"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(client::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Plex answered status 500 to plex sections");
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000),
            Duration.ofMillis(2000));
        server.verify();
    }

    @Test
    void anUnreachablePlexIsRetriedThenReportedAsNotReachable() {
        server.expect(times(4), requestTo(BASE + "/library/sections"))
            .andRespond(request -> {
                throw new IOException("connection refused to " + BASE + " with " + TOKEN);
            });

        assertThatThrownBy(client::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Plex is not reachable").satisfies(e -> assertThat(e.getCause()).isNull());
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void anInterruptedBackoffStopsTheCallAndKeepsTheInterrupt() {
        PlexClient interrupted = new PlexClient(builder.build(), BASE, TOKEN, d -> {
            throw new InterruptedException();
        }, 4, 1000, 100);
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(interrupted::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Interrupted while waiting to retry plex sections");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void reachableIsOneAttemptWithNoRetries() {
        server.expect(once(), requestTo(BASE + "/library/sections")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.reachable()).isFalse();
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void reachableIsTrueWhenPlexAnswers() {
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withSuccess(SECTIONS, MediaType.APPLICATION_JSON));

        assertThat(client.reachable()).isTrue();
    }

    @Test
    void notConfiguredIsNotReachableAndSendsNothing() {
        PlexClient off = new PlexClient(builder.build(), "", "", sleeps::add, 4, 1000, 100);

        assertThat(off.reachable()).isFalse();
        server.verify();
    }

    @Test
    void aJsonAnswerOfExactlyTheCapIsReadAndOneByteMoreIsRefused() {
        String exact = "{\"MediaContainer\":{\"Directory\":[]}}";
        PlexClient capped = new PlexClient(builder.build(), BASE, TOKEN, sleeps::add, 4, exact.length(), 100);
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withSuccess(exact, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withSuccess(exact + " ", MediaType.APPLICATION_JSON));

        assertThat(capped.movieSections()).isEmpty();
        assertThatThrownBy(capped::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Plex sent more than " + exact.length() + " bytes for plex sections");
    }

    @Test
    void anImageOfExactlyTheCapIsReadAndOneByteMoreIsRefused() {
        server.expect(once(), request -> { })
            .andRespond(withSuccess(new byte[100], MediaType.IMAGE_JPEG));
        server.expect(once(), request -> { })
            .andRespond(withSuccess(new byte[101], MediaType.IMAGE_JPEG));

        assertThat(client.image("/library/metadata/1/thumb/2", 600, 900).bytes()).hasSize(100);
        assertThatThrownBy(() -> client.image("/library/metadata/1/thumb/2", 600, 900))
            .isInstanceOf(PlexException.class).hasMessage("Plex sent more than 100 bytes for plex image");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void theProductionCapsAreFiftyAndTenMegabytes() {
        assertThat(PlexClient.JSON_CAP).isEqualTo(50 * 1024 * 1024);
        assertThat(PlexClient.IMAGE_CAP).isEqualTo(10 * 1024 * 1024);
    }

    @Test
    void unreadableJsonIsAnErrorNamingTheCall() {
        server.expect(once(), requestTo(BASE + "/library/sections"))
            .andRespond(withSuccess("<html>", MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Plex returned unreadable JSON for plex sections");
    }

    @Test
    void errorsAndLogsNameTheCallNeverTheTokenOrTheAddress() {
        server.expect(times(4), requestTo(BASE + "/library/sections"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
            .getLogger(PlexClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(client::movieSections).satisfies(e -> {
                assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain("plex.test");
            });
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TOKEN).doesNotContain("plex.test")
                .doesNotContain("http");
            assertThat(event.getFormattedMessage()).startsWith("plex sections failed");
        });
    }

    @Test
    void theInFlightLimitIsReleasedAfterEveryAttemptSoALimitOfOneStillWorksForManyCalls() {
        PlexClient single = new PlexClient(builder.build(), BASE, TOKEN, sleeps::add, 1, 1000, 100);
        AtomicInteger calls = new AtomicInteger();
        server.expect(times(6), requestTo(BASE + "/library/sections")).andRespond(request -> {
            if (calls.incrementAndGet() % 2 == 1) {
                return withStatus(HttpStatus.BAD_GATEWAY).createResponse(request);
            }
            return withSuccess(SECTIONS, MediaType.APPLICATION_JSON).createResponse(request);
        });

        for (int i = 0; i < 3; i++) {
            assertThat(single.movieSections()).containsExactly("1", "3");
        }
    }

    @Test
    void aSecondCallCannotStartWhileTheOnlyPermitIsHeldAndTheInterruptIsReported() throws Exception {
        PlexClient single = new PlexClient(builder.build(), BASE, TOKEN, sleeps::add, 1, 1000, 100);
        java.lang.reflect.Field field = PlexClient.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        ((java.util.concurrent.Semaphore) field.get(single)).acquire();
        Thread.currentThread().interrupt();

        assertThatThrownBy(single::movieSections).isInstanceOf(PlexException.class)
            .hasMessage("Interrupted while waiting to call Plex for plex sections");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void aTotalSizeOfZeroEndsTheListEvenWhenAFullPageCameBack() {
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(page(0, 0, 200), MediaType.APPLICATION_JSON));

        assertThat(big.films("1")).hasSize(200);
        server.verify();
    }

    @Test
    void fieldsThatAreNotStringsAreTreatedAsMissing() {
        String body = "{\"MediaContainer\":{\"totalSize\":1,\"Metadata\":[{\"title\":12,\"titleSort\":true,"
            + "\"Guid\":[{\"id\":5}],\"Media\":[{\"Part\":[{\"file\":7}]}]}]}}";
        server.expect(once(), requestTo(BASE + "/library/sections/1/all?includeGuids=1"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        PlexFilm film = client.films("1").get(0);

        assertThat(film.title()).isNull();
        assertThat(film.titleSort()).isNull();
        assertThat(film.tmdbId()).isNull();
        assertThat(film.media()).isEmpty();
    }
}
