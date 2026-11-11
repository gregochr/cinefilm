package gregochr.cinefilm.collection;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.collection.CollectionDtos.CollectionCard;
import gregochr.cinefilm.collection.CollectionDtos.CollectionList;
import gregochr.cinefilm.collection.CollectionDtos.CollectionPage;
import gregochr.cinefilm.collection.CollectionDtos.PartDto;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.web.JsonEtag;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CollectionController.class)
@Import({SecurityConfig.class, JsonEtag.class})
class CollectionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CollectionService service;

    private void alienPage() {
        when(service.get(4L)).thenReturn(new CollectionPage(4L, "Alien", "/saga.jpg",
            List.of(new PartDto(false, null, "Aliens", 1986, "/aliens.jpg"))));
    }

    private void aList() {
        when(service.list()).thenReturn(new CollectionList(24, List.of(new CollectionCard(4L, "Alien", "/saga.jpg",
            2, 4, List.of("/a.jpg", "/b.jpg"), Instant.parse("2026-11-01T10:00:00Z")))));
    }

    @Test
    void aMissingPartIsShownWithItsTitleYearAndPosterAndNoFilm() throws Exception {
        alienPage();

        mvc.perform(get("/api/collections/{id}", 4).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Alien"))
            .andExpect(jsonPath("$.parts[0].owned").value(false))
            .andExpect(jsonPath("$.parts[0].title").value("Aliens"))
            .andExpect(jsonPath("$.parts[0].year").value(1986))
            .andExpect(jsonPath("$.parts[0].film").doesNotExist());
    }

    @Test
    void theListCarriesTheFilmCountAndTheCards() throws Exception {
        aList();

        mvc.perform(get("/api/collections").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmCount").value(24))
            .andExpect(jsonPath("$.collections[0].name").value("Alien"))
            .andExpect(jsonPath("$.collections[0].ownedCount").value(2))
            .andExpect(jsonPath("$.collections[0].totalCount").value(4))
            .andExpect(jsonPath("$.collections[0].posterPaths[1]").value("/b.jpg"))
            .andExpect(jsonPath("$.collections[0].lastAddedAt").value("2026-11-01T10:00:00Z"));
    }

    @Test
    void theListCarriesAWeakEtagAndExactlyNoCachePrivate() throws Exception {
        aList();

        mvc.perform(get("/api/collections").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", matchesPattern("W/\"[0-9a-f]{16}\"")))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void theListIsA304ForItsOwnEtagWithNoBodyAndTheSameHeaders() throws Exception {
        aList();
        String etag = mvc.perform(get("/api/collections").with(user("owner")))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/collections").with(user("owner")).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void theListGetsTheBodyForADifferentEtag() throws Exception {
        aList();

        mvc.perform(get("/api/collections").with(user("owner")).header("If-None-Match", "W/\"fedcba9876543210\""))
            .andExpect(status().isOk())
            .andExpect(content().string(not(emptyString())));
    }

    @Test
    void aPageCarriesAWeakEtagAndExactlyNoCachePrivate() throws Exception {
        alienPage();

        mvc.perform(get("/api/collections/{id}", 4).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", matchesPattern("W/\"[0-9a-f]{16}\"")))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void aPageIsA304ForItsOwnEtagWithNoBodyAndTheSameHeaders() throws Exception {
        alienPage();
        String etag = mvc.perform(get("/api/collections/{id}", 4).with(user("owner")))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/collections/{id}", 4).with(user("owner")).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void aPageGetsTheBodyForADifferentEtag() throws Exception {
        alienPage();

        mvc.perform(get("/api/collections/{id}", 4).with(user("owner"))
                .header("If-None-Match", "W/\"fedcba9876543210\""))
            .andExpect(status().isOk())
            .andExpect(content().string(not(emptyString())));
    }

    @Test
    void theListIsA401WhenSignedOut() throws Exception {
        mvc.perform(get("/api/collections")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownCollectionIsA404WithTheServiceMessageNoEtagAndNoStore() throws Exception {
        when(service.get(31337L)).thenThrow(new NotFoundException("No such collection"));

        mvc.perform(get("/api/collections/{id}", 31337).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.error").value("No such collection"));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(get("/api/collections/{id}", 4))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
