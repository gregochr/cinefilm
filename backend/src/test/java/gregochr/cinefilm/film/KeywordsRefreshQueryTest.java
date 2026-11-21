package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The keywords refresh against the real queries: which films are due, and the refetch replacing keywords whole. */
class KeywordsRefreshQueryTest extends ApiTestBase {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    @Autowired
    private FilmKeywords keywords;

    @Autowired
    private EntityManager entityManager;

    private Film saved(String title, Long tmdbId, Instant fetchedAt, String... tags) {
        Film f = film(null, title, 1980, NOW, "Comedy");
        f.setTmdbId(tmdbId);
        f.setKeywordsFetchedAt(fetchedAt);
        f.getKeywords().addAll(List.of(tags));
        return films.saveAndFlush(f);
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void onlyMatchedFilmsFetchedBeforeTheCutoffAreDueStalestFirst() {
        Film justBefore = saved("One millisecond before the cutoff", 1L, CUTOFF.minusMillis(1));
        Film stalest = saved("Stalest", 2L, CUTOFF.minus(Duration.ofDays(200)));
        Film tieFirst = saved("Tie first", 3L, CUTOFF.minus(Duration.ofDays(5)));
        Film tieSecond = saved("Tie second", 4L, CUTOFF.minus(Duration.ofDays(5)));
        Film justAfter = saved("One millisecond after the cutoff", 5L, CUTOFF.plusMillis(1));
        Film atCutoff = saved("At the cutoff", 6L, CUTOFF);
        Film never = saved("Never fetched", 7L, null);
        Film unmatched = saved("Unmatched", null, CUTOFF.minus(Duration.ofDays(400)));

        List<TmdbRef> due = films.findWithStaleKeywords(CUTOFF);

        assertThat(due).extracting(TmdbRef::id)
            .containsExactly(stalest.getId(), tieFirst.getId(), tieSecond.getId(), justBefore.getId())
            .doesNotContain(justAfter.getId(), atCutoff.getId(), never.getId(), unmatched.getId());
    }

    @Test
    void aRefetchReplacesTheKeywordsWholeAndRestampsOnlyThatFilm() {
        Instant old = CUTOFF.minus(Duration.ofDays(10));
        Film refetched = saved("Refetched", 11L, old, "christmas", "snow");
        Film other = saved("Other", 12L, old, "christmas");
        reload();

        boolean saved = keywords.save(refetched.getId(), 11L, List.of("snow", "winter"), NOW);
        reload();

        assertThat(saved).isTrue();
        Film after = films.findById(refetched.getId()).orElseThrow();
        assertThat(after.getKeywords()).containsExactlyInAnyOrder("snow", "winter");
        assertThat(after.getKeywordsFetchedAt()).isEqualTo(NOW);
        Film untouched = films.findById(other.getId()).orElseThrow();
        assertThat(untouched.getKeywords()).containsExactly("christmas");
        assertThat(untouched.getKeywordsFetchedAt()).isEqualTo(old);
        assertThat(films.findWithStaleKeywords(CUTOFF)).extracting(TmdbRef::id).containsExactly(other.getId());
    }

    @Test
    void aFilmFetchedLongAgoStillCountsAsFetchedForTheProgressStrip() {
        long before = films.countByTmdbIdIsNotNullAndKeywordsFetchedAtIsNotNull();
        saved("Stale", 21L, CUTOFF.minus(Duration.ofDays(90)));

        assertThat(films.countByTmdbIdIsNotNullAndKeywordsFetchedAtIsNotNull()).isEqualTo(before + 1);
    }
}
