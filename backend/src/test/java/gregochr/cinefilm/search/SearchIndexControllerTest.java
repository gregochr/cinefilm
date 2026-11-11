package gregochr.cinefilm.search;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SearchIndexController.class)
@Import(SecurityConfig.class)
class SearchIndexControllerTest {

    private static final String OPAQUE = "\"0123456789abcdef\"";
    private static final String ETAG = "W/" + OPAQUE;
    private static final String BODY = "{\"films\":[],\"collections\":[],\"people\":[]}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SearchIndexCache cache;

    @BeforeEach
    void index() {
        when(cache.get()).thenReturn(new SearchIndexCache.Snapshot(BODY.getBytes(StandardCharsets.UTF_8), ETAG));
    }

    private static MockHttpServletRequestBuilder signedIn() {
        return get("/api/search-index").with(user("owner"));
    }

    @Test
    void answers200WithTheJsonTheEtagAndNoCache() throws Exception {
        mvc.perform(signedIn())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("ETag", ETAG))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(BODY));
    }

    @Test
    void aMatchingIfNoneMatchIs304WithAnEmptyBodyAndTheEtag() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", ETAG))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", ETAG))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void aDifferentEtagGetsTheFullBody() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "\"fedcba9876543210\""))
            .andExpect(status().isOk())
            .andExpect(content().string(BODY));
    }

    @Test
    void anUnquotedEtagMatches() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "0123456789abcdef")).andExpect(status().isNotModified());
    }

    @Test
    void aStrongFormOfTheSameTagMatches() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", OPAQUE)).andExpect(status().isNotModified());
    }

    @Test
    void anEtagInACommaListMatches() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "\"aaaaaaaaaaaaaaaa\", W/" + ETAG + " ,\"bbbbbbbbbbbbbbbb\""))
            .andExpect(status().isNotModified());
    }

    @Test
    void repeatedIfNoneMatchHeadersAreAllConsidered() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "\"aaaaaaaaaaaaaaaa\"").header("If-None-Match", ETAG))
            .andExpect(status().isNotModified());
    }

    @Test
    void aStarMatchesAnyCurrentRepresentation() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "*")).andExpect(status().isNotModified());
    }

    @Test
    void aCommaListOfOtherEtagsDoesNotMatch() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "\"aaaaaaaaaaaaaaaa\", \"bbbbbbbbbbbbbbbb\""))
            .andExpect(status().isOk());
    }

    @Test
    void aBareQuoteIsNotAnEtagAndDoesNotMatch() throws Exception {
        mvc.perform(signedIn().header("If-None-Match", "\"")).andExpect(status().isOk());
    }

    @Test
    void isA401WhenSignedOut() throws Exception {
        mvc.perform(get("/api/search-index"))
            .andExpect(status().isUnauthorized());
    }
}
