package gregochr.cinefilm.plex;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.film.FilmRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/art/{name}}: strict names, the right headers, and the session. */
@WebMvcTest(ArtworkController.class)
@Import({SecurityConfig.class, ArtworkStore.class})
class ArtworkControllerTest {

    private static Path dir;

    @DynamicPropertySource
    static void artworkDir(DynamicPropertyRegistry registry) throws IOException {
        dir = Files.createTempDirectory("art-controller-test");
        registry.add("cinefilm.artwork.dir", dir::toString);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FilmRepository films;

    @Autowired
    private ArtworkStore store;

    @MockitoBean
    private ResizedArtwork resized;

    private String jpg;
    private String png;

    @BeforeEach
    void stored() {
        jpg = store.store(new byte[] {(byte) 0xff, (byte) 0xd8, 1}, "image/jpeg").substring(9);
        png = store.store(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png").substring(9);
        when(films.existsByPosterPathOrBackdropPath("/api/art/" + jpg, "/api/art/" + jpg)).thenReturn(true);
        when(films.existsByPosterPathOrBackdropPath("/api/art/" + png, "/api/art/" + png)).thenReturn(true);
    }

    @Test
    void aStoredJpegIsServedWithItsTypeAndAnImmutablePrivateCacheHeader() throws Exception {
        mvc.perform(get("/api/art/" + jpg).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andExpect(content().bytes(new byte[] {(byte) 0xff, (byte) 0xd8, 1}));
    }

    @Test
    void aStoredPngIsServedAsPng() throws Exception {
        mvc.perform(get("/api/art/" + png).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
    }

    @Test
    void namesThatAreNotExactlyAHashAndAnAllowedExtensionAre404() throws Exception {
        String hash = jpg.substring(0, 64);
        String[] bad = {hash.toUpperCase() + ".jpg", hash + ".gif", hash + ".jpeg", hash, hash + ".jpg.png",
            "not-a-hash.jpg", "a".repeat(64) + ".jpg"};
        for (String name : bad) {
            mvc.perform(get("/api/art/" + name).with(user("owner")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No such artwork"));
        }
    }

    @Test
    void aSixtyThreeOrSixtyFiveCharacterOrEmptyStemReachesTheControllerAnd404sWithTheNormalBody() throws Exception {
        String hash = jpg.substring(0, 64);
        for (String name : new String[] {hash.substring(1) + ".jpg", hash + "0.jpg", ".jpg", "x.jpg"}) {
            mvc.perform(get("/api/art/" + name).with(user("owner")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No such artwork"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"..%2Fx", "..%2Fx.jpg", "%2Fetc%2Fpasswd"})
    void encodedSlashesAreRejectedByTheFirewallBeforeTheController(String name) throws Exception {
        mvc.perform(get("/api/art/" + name).with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""));
    }

    @Test
    void anAbsolutePathAfterThePrefixMatchesNoRouteSoNoControllerRuns() throws Exception {
        var result = mvc.perform(get("/api/art//etc/passwd").with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(content().string(""))
            .andReturn();

        // Only the static-resource fallback saw it; no controller method was mapped.
        org.assertj.core.api.Assertions.assertThat(result.getHandler())
            .isNotInstanceOf(org.springframework.web.method.HandlerMethod.class);
    }

    @Test
    void traversalAndEncodedSlashesAreNeverServed() throws Exception {
        String[] bad = {"..", "..%2f" + jpg, "%2e%2e%2f" + jpg, "x%2f" + jpg, "x/" + jpg, "../" + jpg};
        for (String name : bad) {
            mvc.perform(get("/api/art/" + name).with(user("owner")))
                .andExpect(status().is4xxClientError())
                .andExpect(result -> org.assertj.core.api.Assertions
                    .assertThat(String.valueOf(result.getResponse().getContentType())).doesNotStartWith("image/"));
        }
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/art/" + jpg))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void aFileNoFilmReferencesIsNotServedEvenThoughItIsInTheStore() throws Exception {
        String stray = store.store(new byte[] {9, 9, 9, 9}, "image/jpeg").substring(9);

        mvc.perform(get("/api/art/" + stray).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such artwork"));
    }

    private static final byte[] STORED_JPG = {(byte) 0xff, (byte) 0xd8, 1};

    private void outcome(String name, int width, ResizedArtwork.Outcome outcome, byte[] bytes) {
        when(resized.resized(eq(name), any(Path.class), eq(width)))
            .thenReturn(new ResizedArtwork.Result(outcome, bytes));
    }

    @Test
    void aResizedCopyIsServedWithTheStoredTypeAndTheImmutableHeader() throws Exception {
        outcome(jpg, 342, ResizedArtwork.Outcome.RESIZED, new byte[] {7, 8, 9});

        mvc.perform(get("/api/art/" + jpg).param("w", "342").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andExpect(content().bytes(new byte[] {7, 8, 9}));
    }

    @Test
    void aResizedPngIsServedAsPng() throws Exception {
        outcome(png, 500, ResizedArtwork.Outcome.RESIZED, new byte[] {1, 2});

        mvc.perform(get("/api/art/" + png).param("w", "500").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(new byte[] {1, 2}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"342", "500", "780", "1280"})
    void everyAllowedWidthIsPassedAsItsConstant(String w) throws Exception {
        outcome(jpg, Integer.parseInt(w), ResizedArtwork.Outcome.RESIZED, new byte[] {5});

        mvc.perform(get("/api/art/" + jpg).param("w", w).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().bytes(new byte[] {5}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_WIDER", "NOT_SMALLER"})
    void anImageNotWiderOrNotSmallerIsTheStoredFileWithTheImmutableHeader(String kind) throws Exception {
        outcome(jpg, 780, ResizedArtwork.Outcome.valueOf(kind), null);

        mvc.perform(get("/api/art/" + jpg).param("w", "780").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andExpect(content().bytes(STORED_JPG));
    }

    @Test
    void anImageThatCannotBeResizedIsTheStoredFileCachedForADay() throws Exception {
        outcome(jpg, 342, ResizedArtwork.Outcome.UNREADABLE, null);

        mvc.perform(get("/api/art/" + jpg).param("w", "342").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("Cache-Control", "max-age=86400, private"))
            .andExpect(content().bytes(STORED_JPG));
    }

    @Test
    void aBusyResizerGivesTheStoredFileAndNoStore() throws Exception {
        outcome(png, 1280, ResizedArtwork.Outcome.BUSY, null);

        mvc.perform(get("/api/art/" + png).param("w", "1280").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().bytes(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "341", "343", "0342", "342.0", " 342", "abc", "342,342", "-1", "999999999999"})
    void aBadWidthIs400WithTheExactMessage(String w) throws Exception {
        mvc.perform(get("/api/art/" + jpg).param("w", w).with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Width must be 342, 500, 780 or 1280"));
    }

    @Test
    void anEmptyWidthInTheQueryStringIs400() throws Exception {
        mvc.perform(get("/api/art/" + jpg + "?w=").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Width must be 342, 500, 780 or 1280"));
    }

    @Test
    void twoWidthParametersAre400() throws Exception {
        mvc.perform(get("/api/art/" + jpg + "?w=342&w=342").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Width must be 342, 500, 780 or 1280"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"342", "bad", ""})
    void anUnknownOrUnreferencedNameIs404WhateverTheWidth(String w) throws Exception {
        String stray = store.store(new byte[] {9, 9, 9, 9, 9}, "image/jpeg").substring(9);
        String unknown = "b".repeat(64) + ".jpg";

        for (String name : new String[] {stray, unknown}) {
            mvc.perform(get("/api/art/" + name).param("w", w).with(user("owner")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No such artwork"));
        }
    }

    @Test
    void withoutAWidthTheResizerIsNeverAsked() throws Exception {
        mvc.perform(get("/api/art/" + jpg).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, max-age=31536000, immutable"))
            .andExpect(content().bytes(STORED_JPG));
        org.mockito.Mockito.verifyNoInteractions(resized);
    }
}
