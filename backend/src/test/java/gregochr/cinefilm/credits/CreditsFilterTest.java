package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.CreditsMarkerSource;
import org.junit.jupiter.api.Test;

class CreditsFilterTest {

    @Test
    void parseKnowsTheFiveLowerCaseNamesAndNothingElse() {
        assertThat(CreditsFilter.parse("none")).isEqualTo(CreditsFilter.NONE);
        assertThat(CreditsFilter.parse("plex")).isEqualTo(CreditsFilter.PLEX);
        assertThat(CreditsFilter.parse("auto")).isEqualTo(CreditsFilter.AUTO);
        assertThat(CreditsFilter.parse("owner")).isEqualTo(CreditsFilter.OWNER);
        assertThat(CreditsFilter.parse("all")).isEqualTo(CreditsFilter.ALL);
        assertThat(CreditsFilter.parse("ALL")).isNull();
        assertThat(CreditsFilter.parse("Auto")).isNull();
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
        assertThat(CreditsFilter.AUTO.source()).isEqualTo(CreditsMarkerSource.AUTO);
        assertThat(CreditsFilter.AUTO.anySource()).isFalse();
        assertThat(CreditsFilter.AUTO.unsettled()).isFalse();
        assertThat(CreditsFilter.OWNER.source()).isEqualTo(CreditsMarkerSource.OWNER);
    }
}
