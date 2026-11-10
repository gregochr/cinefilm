package gregochr.cinefilm.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.search.SearchIndexDtos.CollectionEntry;
import gregochr.cinefilm.search.SearchIndexDtos.FilmEntry;
import gregochr.cinefilm.search.SearchIndexDtos.PersonEntry;
import gregochr.cinefilm.search.SearchIndexDtos.SearchIndex;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SearchIndexCacheTest {

    private static final SearchIndex WARRIOR = new SearchIndex(
        List.of(new FilmEntry(1L, "Warrior", 2011, List.of("Gavin O'Connor"), "/abc.jpg", 7_380_000L,
            "2026-11-01T20:00:00Z", "warrior")),
        List.of(new CollectionEntry(4L, "Star Wars Collection", 2, 9, List.of("/a.jpg", "/b.jpg"))),
        List.of(new PersonEntry(9L, "Tom Hanks", 9, 0)));

    private static final SearchIndex EMPTY = new SearchIndex(List.of(), List.of(), List.of());

    @Mock
    private SearchIndexService service;

    private SearchIndexCache cache;

    @BeforeEach
    void setUp() {
        cache = new SearchIndexCache(service, JsonMapper.builder().build());
    }

    private String json(SearchIndexCache.Snapshot snapshot) {
        return new String(snapshot.body(), StandardCharsets.UTF_8);
    }

    @Test
    void theBodyIsTheCompactJsonWithShortKeysAndNoNulls() {
        when(service.build()).thenReturn(WARRIOR);

        assertThat(json(cache.get())).isEqualTo("{\"films\":[{\"id\":1,\"t\":\"Warrior\",\"y\":2011,"
            + "\"d\":[\"Gavin O'Connor\"],\"p\":\"/abc.jpg\",\"left\":7380000,\"at\":\"2026-11-01T20:00:00Z\","
            + "\"s\":\"warrior\"}],\"collections\":[{\"id\":4,\"n\":\"Star Wars Collection\",\"o\":2,\"of\":9,"
            + "\"p\":[\"/a.jpg\",\"/b.jpg\"]}],\"people\":[{\"id\":9,\"n\":\"Tom Hanks\",\"a\":9,\"d\":0}]}");
    }

    @Test
    void aFilmWithoutProgressOmitsLeftAtAndPosterRatherThanWritingNulls() {
        when(service.build()).thenReturn(new SearchIndex(
            List.of(new FilmEntry(2L, "Heat", 1995, List.of(), null, null, null, "heat")), List.of(), List.of()));

        assertThat(json(cache.get())).isEqualTo(
            "{\"films\":[{\"id\":2,\"t\":\"Heat\",\"y\":1995,\"d\":[],\"s\":\"heat\"}],\"collections\":[],"
                + "\"people\":[]}");
    }

    @Test
    void theEtagIsTheQuotedFirstSixteenHexCharactersOfTheSha256OfTheBody() {
        when(service.build()).thenReturn(EMPTY);

        SearchIndexCache.Snapshot snapshot = cache.get();

        // sha256('{"films":[],"collections":[],"people":[]}')
        assertThat(snapshot.etag()).matches("W/\"[0-9a-f]{16}\"");
        assertThat(snapshot.etag()).isEqualTo(SearchIndexCache.etag("SHA-256", snapshot.body()));
        assertThat(SearchIndexCache.etag("SHA-256", "abc".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("W/\"ba7816bf8f01cfea\"");
    }

    @Test
    void anUnknownDigestAlgorithmIsAnError() {
        assertThatThrownBy(() -> SearchIndexCache.etag("NOPE-1", new byte[0]))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("NOPE-1 is not available");
    }

    @Test
    void twoBuildsOfTheSameDataGiveTheSameEtagAndDifferentDataDoesNot() {
        when(service.build()).thenReturn(WARRIOR, WARRIOR, EMPTY);

        String first = cache.get().etag();
        cache.invalidate();
        String second = cache.get().etag();
        cache.invalidate();
        String third = cache.get().etag();

        assertThat(second).isEqualTo(first);
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void theIndexIsBuiltOnceUntilSomethingInvalidatesIt() {
        when(service.build()).thenReturn(WARRIOR);

        SearchIndexCache.Snapshot first = cache.get();
        SearchIndexCache.Snapshot second = cache.get();

        assertThat(second).isSameAs(first);
        verify(service, times(1)).build();
    }

    @Test
    void aScanFinishingDropsTheMemo() {
        when(service.build()).thenReturn(EMPTY, WARRIOR);
        String before = cache.get().etag();

        cache.onScanFinished(new ScanFinished("admin"));

        assertThat(cache.get().etag()).isNotEqualTo(before);
        verify(service, times(2)).build();
    }

    @Test
    void aPlexImportFinishingDropsTheMemo() {
        when(service.build()).thenReturn(EMPTY, WARRIOR);
        String before = cache.get().etag();

        cache.onPlexImportFinished(new PlexImportFinished());

        assertThat(cache.get().etag()).isNotEqualTo(before);
        verify(service, times(2)).build();
    }

    @Test
    void aLibraryChangeDropsTheMemo() {
        when(service.build()).thenReturn(EMPTY, WARRIOR);
        String before = cache.get().etag();

        cache.onLibraryChanged(new LibraryChanged());

        assertThat(cache.get().etag()).isNotEqualTo(before);
        verify(service, times(2)).build();
    }

    @Test
    void anInvalidationDuringABuildServesTheBytesButDoesNotKeepThem() {
        when(service.build()).thenAnswer(call -> {
            cache.invalidate();
            return EMPTY;
        }).thenReturn(WARRIOR);

        SearchIndexCache.Snapshot stale = cache.get();
        SearchIndexCache.Snapshot fresh = cache.get();

        assertThat(json(stale)).startsWith("{\"films\":[],");
        assertThat(json(fresh)).contains("Warrior");
        assertThat(cache.get()).as("the second build was kept").isSameAs(fresh);
        verify(service, times(2)).build();
    }
}
