package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.CreditsMarkerSource;
import org.junit.jupiter.api.Test;

class CreditsFilterTest {

    @Test
    void parseKnowsTheFourLowerCaseNamesAndNothingElse() {
        assertThat(CreditsFilter.parse("none")).isEqualTo(CreditsFilter.NONE);
        assertThat(CreditsFilter.parse("plex")).isEqualTo(CreditsFilter.PLEX);
        assertThat(CreditsFilter.parse("owner")).isEqualTo(CreditsFilter.OWNER);
        assertThat(CreditsFilter.parse("all")).isEqualTo(CreditsFilter.ALL);
        assertThat(CreditsFilter.parse("ALL")).isNull();
        assertThat(CreditsFilter.parse("auto")).isNull();
        assertThat(CreditsFilter.parse("")).isNull();
        assertThat(CreditsFilter.parse(null)).isNull();
    }

    @Test
    void eachFilterSaysWhichSourcesItAccepts() {
        assertThat(CreditsFilter.ALL.anySource()).isTrue();
        assertThat(CreditsFilter.NONE.anySource()).isFalse();
        assertThat(CreditsFilter.NONE.unsettled()).isTrue();
        assertThat(CreditsFilter.PLEX.unsettled()).isFalse();
        assertThat(CreditsFilter.PLEX.source()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(CreditsFilter.OWNER.source()).isEqualTo(CreditsMarkerSource.OWNER);
    }
}
