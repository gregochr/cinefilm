package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The facts and the one sentence the Admin shows for a download's frame rate. */
class RateLineTest {

    private static final double K_UP = 23.976 / 25;
    private static final double K_DOWN = 25.0 / 23.976;

    private static SubtitleSidecar download(Double timed, RetimeChoice choice, Double retimedBy) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setTimedFps(timed);
        row.setRetime(choice);
        row.setRetimedBy(retimedBy);
        return row;
    }

    @Test
    void anOwnersFileHasNoFactsAtAll() {
        SubtitleSidecar file = new SubtitleSidecar();
        file.setTimedFps(25.0);
        file.setRetime(RetimeChoice.AUTO);

        assertThat(RateLine.of(file, 25.0)).isEqualTo(RateLine.NONE);
        assertThat(RateLine.NONE.line()).isNull();
    }

    @Test
    void aRetimedFileStatedForFilmSpeedOnAPalFilmIsSpedUp() {
        RateLine rate = RateLine.of(download(23.976, RetimeChoice.AUTO, K_UP), 25.0);

        assertThat(rate.line()).isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
        assertThat(rate.timedFps()).isEqualTo(23.976);
        assertThat(rate.filmFps()).isEqualTo(25.0);
        assertThat(rate.retime()).isEqualTo("AUTO");
        assertThat(rate.retimedBy()).isEqualTo(K_UP);
    }

    @Test
    void aRetimedFileStatedForPalOnAFilmSpeedFilmIsSlowedDown() {
        RateLine rate = RateLine.of(download(25.0, RetimeChoice.AUTO, K_DOWN), 23.976);

        assertThat(rate.line()).isEqualTo("Timed for 25 fps; this film is 23.976 fps, so it is slowed down 4%");
    }

    @Test
    void aFilmRateIsShownToThreeDecimalsAtMost() {
        RateLine rate = RateLine.of(download(23.976, RetimeChoice.AUTO, K_UP), 25.000_000_4);

        assertThat(rate.line()).startsWith("Timed for 23.976 fps; this film is 25 fps,");
    }

    @Test
    void matchingRatesAreTimedForThisFilmWhateverTheChoice() {
        assertThat(RateLine.of(download(25.0, null, null), 25.0).line())
            .isEqualTo("Timed for this film's frame rate");
        assertThat(RateLine.of(download(25.0, RetimeChoice.AUTO, null), 25.0).line())
            .isEqualTo("Timed for this film's frame rate");
        assertThat(RateLine.of(download(25.0, RetimeChoice.OFF, null), 25.0).line())
            .isEqualTo("Timed for this film's frame rate");
    }

    @Test
    void anUnknownRateOnEitherSideIsSaidSo() {
        assertThat(RateLine.of(download(null, null, null), 25.0).line()).isEqualTo("Frame rate unknown");
        assertThat(RateLine.of(download(23.976, null, null), null).line()).isEqualTo("Frame rate unknown");
        assertThat(RateLine.of(download(23.976, RetimeChoice.OFF, null), null).line())
            .isEqualTo("Frame rate unknown");
    }

    @Test
    void aFileTheOwnerChoseNotToRetimeWarnsItMayDrift() {
        RateLine rate = RateLine.of(download(23.976, RetimeChoice.OFF, null), 25.0);

        assertThat(rate.line()).isEqualTo("Not retimed; it may drift");
        assertThat(rate.retime()).isEqualTo("OFF");
        assertThat(rate.retimedBy()).isNull();
    }

    @Test
    void aFileThatFitsAsItIsSaysSoWhateverTheRates() {
        assertThat(RateLine.of(download(23.976, RetimeChoice.OFF_FITS, null), 25.0).line())
            .isEqualTo("Fits this copy as it is");
        assertThat(RateLine.of(download(null, RetimeChoice.OFF_FITS, null), null).line())
            .isEqualTo("Fits this copy as it is");
    }

    @Test
    void ratesOutsideTheSpeedUpAreNotCalledMatchingAndSayWhatIsRetimed() {
        assertThat(RateLine.of(download(29.97, null, null), 25.0).line())
            .isEqualTo("Timed for 29.97 fps; this film is 25 fps; only the 24 to 25 fps speed-up is retimed");
        assertThat(RateLine.of(download(23.976, RetimeChoice.AUTO, null), 24.0).line())
            .isEqualTo("Timed for 23.976 fps; this film is 24 fps; only the 24 to 25 fps speed-up is retimed");
        assertThat(RateLine.of(download(23.976, RetimeChoice.OFF, null), 24.0).line())
            .isEqualTo("Timed for 23.976 fps; this film is 24 fps; only the 24 to 25 fps speed-up is retimed");
    }

    @Test
    void aRetimedFileIsDescribedFromWhatWasDoneWhateverTheRatesAreNow() {
        SubtitleSidecar noFilmRate = download(23.976, RetimeChoice.AUTO, K_UP);
        noFilmRate.setRetimedForFps(25.0);
        SubtitleSidecar nowMatching = download(25.0, RetimeChoice.AUTO, K_UP);
        SubtitleSidecar notRestoredYet = download(23.976, RetimeChoice.OFF, K_UP);

        assertThat(RateLine.of(noFilmRate, null).line())
            .isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
        assertThat(RateLine.of(nowMatching, 25.0).line())
            .isEqualTo("Timed for 25 fps; this film is 25 fps, so it is sped up 4%");
        assertThat(RateLine.of(notRestoredYet, 25.0).line())
            .isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
    }

    @Test
    void aRetimedFileIsWordedFromTheRateItWasRetimedForNotTheCopyItHasNow() {
        SubtitleSidecar retimed = download(23.976, RetimeChoice.AUTO, K_UP);
        retimed.setRetimedForFps(25.0);

        assertThat(RateLine.of(retimed, 23.976).line())
            .isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is sped up 4%");
    }

    @Test
    void aFileWithTheOwnersTimingIsNeverRetimedWhetherOffOrUndecided() {
        SubtitleSidecar nudgedOff = download(23.976, RetimeChoice.OFF, null);
        nudgedOff.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        SubtitleSidecar verifiedUndecided = download(23.976, null, null);
        verifiedUndecided.setOwnerVerifiedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        SubtitleSidecar nudgedUndecided = download(23.976, null, null);
        nudgedUndecided.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);

        for (SubtitleSidecar row : java.util.List.of(nudgedOff, verifiedUndecided, nudgedUndecided)) {
            assertThat(RateLine.of(row, 25.0).line())
                .isEqualTo("Not retimed: its timing was set by hand; it may drift");
        }
    }

    @Test
    void anAutoTimedOffsetDoesNotCountAsSetByHandAndAnAutoRowIsStillDue() {
        SubtitleSidecar autoOffset = download(23.976, null, null);
        autoOffset.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        SubtitleSidecar autoChoice = download(23.976, RetimeChoice.AUTO, null);
        autoChoice.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);

        assertThat(RateLine.of(autoOffset, 25.0).line())
            .isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is not retimed");
        assertThat(RateLine.of(autoChoice, 25.0).line())
            .isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is not retimed");
    }

    @Test
    void aRetimedFileWithNoKnownRateAtAllStillSaysWhatWasDone() {
        assertThat(RateLine.of(download(null, RetimeChoice.AUTO, K_UP), null).line())
            .isEqualTo("Timed for an unknown frame rate; this film's frame rate is unknown, so it is sped up 4%");
    }

    @Test
    void aFileWithADifferentRateThatIsNotRetimedYetSaysSo() {
        assertThat(RateLine.of(download(23.976, null, null), 25.0).line())
            .isEqualTo("Timed for 23.976 fps; this film is 25 fps, so it is not retimed");
        assertThat(RateLine.of(download(23.976, RetimeChoice.AUTO, null), 25.0).retime()).isEqualTo("AUTO");
    }
}
