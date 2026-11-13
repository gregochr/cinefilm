package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The day's limit: the account's allowance, capped by the owner's setting, 20 while the allowance is unknown. */
@ExtendWith(MockitoExtension.class)
class DailyDownloadLimitTest {

    @Mock
    private OpenSubtitlesApi api;

    @Test
    void anAllowanceBelowTheCapIsTheLimit() {
        when(api.allowedDownloads()).thenReturn(OptionalInt.of(20));

        assertThat(new DailyDownloadLimit(api, 1000).today()).isEqualTo(20);
    }

    @Test
    void anAllowanceAboveTheCapIsHeldToTheCap() {
        when(api.allowedDownloads()).thenReturn(OptionalInt.of(1000));

        assertThat(new DailyDownloadLimit(api, 50).today()).isEqualTo(50);
    }

    @Test
    void anAllowanceEqualToTheCapIsTheLimit() {
        when(api.allowedDownloads()).thenReturn(OptionalInt.of(50));

        assertThat(new DailyDownloadLimit(api, 50).today()).isEqualTo(50);
    }

    @Test
    void anUnknownAllowanceIsAFreeAccountsTwentyWhenTheCapIsHigher() {
        when(api.allowedDownloads()).thenReturn(OptionalInt.empty());

        assertThat(new DailyDownloadLimit(api, 1000).today()).isEqualTo(DailyDownloadLimit.FREE_ALLOWANCE)
            .isEqualTo(20);
    }

    @Test
    void anUnknownAllowanceStillRespectsALowerCap() {
        when(api.allowedDownloads()).thenReturn(OptionalInt.empty());

        assertThat(new DailyDownloadLimit(api, 5).today()).isEqualTo(5);
    }

    @Test
    void theAllowanceIsAskedAgainEachTimeSoAnUpgradeIsFollowed() {
        when(api.allowedDownloads()).thenReturn(OptionalInt.of(20), OptionalInt.of(1000));
        DailyDownloadLimit limit = new DailyDownloadLimit(api, 1000);

        assertThat(limit.today()).isEqualTo(20);
        assertThat(limit.today()).isEqualTo(1000);
    }
}
