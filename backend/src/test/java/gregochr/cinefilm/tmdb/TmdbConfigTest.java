package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TmdbConfigTest {

    private final TmdbConfig config = new TmdbConfig();

    @Test
    void theRestClientIsBuiltForTheConfiguredBaseUrl() {
        assertThat(config.tmdbRestClient("https://api.themoviedb.org")).isNotNull();
    }

    @Test
    void theRealSleeperReallyWaitsWithoutFailing() {
        assertThat(config.sleeper()).isSameAs(Sleeper.THREAD);
        assertThatCode(() -> Sleeper.THREAD.sleep(Duration.ofMillis(1))).doesNotThrowAnyException();
    }
}
