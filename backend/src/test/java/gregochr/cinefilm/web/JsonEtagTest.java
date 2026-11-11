package gregochr.cinefilm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class JsonEtagTest {

    private static final String OPAQUE = "\"0123456789abcdef\"";
    private static final String ETAG = "W/" + OPAQUE;
    private static final String NO_CACHE_PRIVATE = "no-cache, private";

    private record Point(int x, String name) {
    }

    private static MockHttpServletRequest request(String... ifNoneMatch) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/films");
        for (String value : ifNoneMatch) {
            request.addHeader(HttpHeaders.IF_NONE_MATCH, value);
        }
        return request;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theEtagIsTheQuotedFirstSixteenHexCharactersOfTheSha256WithAWeakPrefix() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(JsonEtag.etag(bytes("abc"))).isEqualTo("W/\"ba7816bf8f01cfea\"");
        // sha256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertThat(JsonEtag.etag(new byte[0])).isEqualTo("W/\"e3b0c44298fc1c14\"");
    }

    @Test
    void anUnavailableDigestAlgorithmIsAnError() {
        assertThatThrownBy(() -> JsonEtag.etag("NOPE-1", new byte[0]))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("NOPE-1 is not available");
    }

    @Test
    void matchesAnExactWeakTag() {
        assertThat(JsonEtag.matches(ETAG, ETAG)).isTrue();
    }

    @Test
    void matchesTheStrongFormOfTheSameValue() {
        assertThat(JsonEtag.matches(OPAQUE, ETAG)).isTrue();
    }

    @Test
    void matchesAnUnquotedValue() {
        assertThat(JsonEtag.matches("0123456789abcdef", ETAG)).isTrue();
    }

    @Test
    void aStarMatchesAnything() {
        assertThat(JsonEtag.matches("*", ETAG)).isTrue();
    }

    @Test
    void matchesWithinACommaListWithSpaces() {
        assertThat(JsonEtag.matches("\"x\" , W/\"y\",  " + ETAG + " , \"z\"", ETAG)).isTrue();
    }

    @Test
    void aDifferentTagAnEmptyHeaderAndAHalfQuotedValueDoNotMatch() {
        assertThat(JsonEtag.matches("\"0123456789abcdee\"", ETAG)).isFalse();
        assertThat(JsonEtag.matches("\"aaaaaaaaaaaaaaaa\", \"bbbbbbbbbbbbbbbb\"", ETAG)).isFalse();
        assertThat(JsonEtag.matches("", ETAG)).isFalse();
        assertThat(JsonEtag.matches("\"0123456789abcdef", ETAG)).isFalse();
        assertThat(JsonEtag.matches("0123456789abcdef\"", ETAG)).isFalse();
        assertThat(JsonEtag.matches("\"", ETAG)).isFalse();
    }

    @Test
    void aFirstRequestGetsA200WithTheJsonTheEtagAndNoCachePrivate() {
        ResponseEntity<byte[]> response = JsonEtag.respond(request(), bytes("{}"), ETAG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(bytes("{}"));
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(NO_CACHE_PRIVATE);
    }

    @Test
    void aMatchingIfNoneMatchGetsA304WithNoBodyTheEtagAndNoCachePrivate() {
        ResponseEntity<byte[]> response = JsonEtag.respond(request(ETAG), bytes("{}"), ETAG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getContentType()).isNull();
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(NO_CACHE_PRIVATE);
    }

    @Test
    void aDifferentIfNoneMatchGetsTheFullBody() {
        ResponseEntity<byte[]> response = JsonEtag.respond(request("\"fedcba9876543210\""), bytes("{}"), ETAG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(bytes("{}"));
    }

    @Test
    void twoIfNoneMatchHeadersAreBothConsidered() {
        ResponseEntity<byte[]> response = JsonEtag.respond(request("\"aaaaaaaaaaaaaaaa\"", ETAG), bytes("{}"), ETAG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void serialisingGivesTheExactJsonBytesAndTheTagOfThoseBytes() {
        JsonEtag etags = new JsonEtag(JsonMapper.builder().build());

        ResponseEntity<byte[]> response = etags.respond(request(), new Point(3, "origin"));

        byte[] expected = bytes("{\"x\":3,\"name\":\"origin\"}");
        assertThat(response.getBody()).isEqualTo(expected);
        assertThat(response.getHeaders().getETag()).isEqualTo(JsonEtag.etag(expected));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo(NO_CACHE_PRIVATE);
    }

    @Test
    void serialisingAnswers304ToTheTagOfItsOwnBytes() {
        JsonEtag etags = new JsonEtag(JsonMapper.builder().build());
        String tag = JsonEtag.etag(bytes("{\"x\":3,\"name\":\"origin\"}"));

        ResponseEntity<byte[]> response = etags.respond(request(tag), new Point(3, "origin"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
    }
}
