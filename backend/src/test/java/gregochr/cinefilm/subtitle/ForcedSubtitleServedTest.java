package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What playback serves for an edition as cues on its timeline ({@link ForcedSubtitleService#served}), with the version
 * string that names it, against the real repositories on H2.
 */
class ForcedSubtitleServedTest extends SubtitleTestBase {

    private static final long HOUR = 3_600_000;

    private Map<Long, ServedSubtitle> served(Film film) {
        return forced.served(film.getEditions());
    }

    private static SubtitleCue cue(long start, long end, String text) {
        return new SubtitleCue(start, end, text);
    }

    @Test
    void aSingleFileEditionServesItsCuesAndAVersionThatNamesTheFilesAndPartsAndStarts() {
        Film film = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        assertThat(served.cues()).containsExactly(cue(10_000, 12_000, "Hello"), cue(20_000, 22_500, "World"));
        assertThat(served.ownerVerified()).isFalse();
        assertThat(served.version()).isEqualTo("v" + NOW.toEpochMilli() + "|" + fileId(film, 1) + "|" + row.getId()
            + "@0");
    }

    @Test
    void theOwnersOffsetMovesTheCuesTheSameWayThePlaybackRouteDoes() {
        Film film = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false).setOffsetMs(-10_500);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        // The first cue ended at 12 s: it is shown from 0 to 1.5 s; the second moves to 9.5 s.
        assertThat(served.cues()).containsExactly(cue(0, 1_500, "Hello"), cue(9_500, 12_000, "World"));
    }

    @Test
    void aNegativeOffsetThatPushesACueBeforeTheStartDropsIt() {
        Film film = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(film, 1), false).setOffsetMs(-12_000);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        assertThat(served.cues()).containsExactly(cue(8_000, 10_500, "World"));
    }

    @Test
    void perPartSidecarsAreStitchedOntoTheEditionTimelineWithTheirOffsetsApplied() {
        Film film = filmWithParts("Towers", 2, 60);
        SubtitleSidecar first = sidecar("Towers - pt1.en.forced.srt", fileId(film, 1), false);
        SubtitleSidecar second = sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        second.setOffsetMs(500);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        assertThat(served.cues()).containsExactly(cue(10_000, 12_000, "Hello"), cue(20_000, 22_500, "World"),
            cue(HOUR + 10_500, HOUR + 12_500, "Hello"), cue(HOUR + 20_500, HOUR + 23_000, "World"));
        assertThat(served.version()).isEqualTo("v" + NOW.toEpochMilli() + "|" + fileId(film, 1) + ","
            + fileId(film, 2) + "|" + first.getId() + "@0," + second.getId() + "@" + HOUR);
    }

    @Test
    void aPartWithNoSidecarContributesNothingAndTheOtherStaysWhereItIs() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        assertThat(served.cues()).containsExactly(cue(HOUR + 10_000, HOUR + 12_000, "Hello"),
            cue(HOUR + 20_000, HOUR + 22_500, "World"));
    }

    @Test
    void aWholeEditionSidecarOfASplitFilmCountsAndIsNotMovedByAnyPartStart() {
        Film film = filmWithParts("Towers", 2, 60);
        SubtitleSidecar whole = sidecar("Towers.en.forced.srt", fileId(film, 1), true);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        assertThat(served.cues()).containsExactly(cue(10_000, 12_000, "Hello"), cue(20_000, 22_500, "World"));
        assertThat(served.version()).endsWith("|" + whole.getId() + "@0");
    }

    @Test
    void theVersionChangesWhenASidecarChangesIsNudgedOrMovesToAnotherPart() {
        Film film = filmWithParts("Towers", 2, 60);
        SubtitleSidecar row = sidecar("Towers.en.forced.srt", fileId(film, 2), false);
        Long editionId = film.getEditions().get(0).getId();
        String original = served(film).get(editionId).version();

        row.setUpdatedAt(NOW.plus(Duration.ofMinutes(1)));
        String touched = served(film).get(editionId).version();
        row.setMediaFileId(fileId(film, 1));
        String moved = served(film).get(editionId).version();

        assertThat(List.of(original, touched, moved)).doesNotHaveDuplicates();
    }

    @Test
    void theVersionChangesWhenAPartsDurationMovesWhereALaterPartStarts() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        Edition edition = film.getEditions().get(0);
        String before = served(film).get(edition.getId()).version();

        edition.originalParts().get(0).setDurationMs(61 * 60_000L);

        assertThat(served(film).get(edition.getId()).version()).isNotEqualTo(before);
    }

    @Test
    void theOwnersCheckIsReportedWhenEveryFileServedHasIt() {
        Film film = filmWithParts("Taken", 1, 90);
        SubtitleSidecar row = sidecar("Taken.en.forced.srt", fileId(film, 1), false);
        row.setOwnerVerifiedAt(NOW);
        row.setVerifiedParts(String.valueOf(fileId(film, 1)));

        assertThat(served(film).get(film.getEditions().get(0).getId()).ownerVerified()).isTrue();
    }

    @Test
    void aFileThePlaybackWouldNotServeIsNotServed() {
        Film missing = filmWithParts("Missing", 1, 90);
        sidecar("Missing.en.forced.srt", fileId(missing, 1), false).setPresence(Presence.MISSING);
        Film empty = filmWithParts("Empty", 1, 90);
        SubtitleSidecar none = sidecar("Empty.en.forced.srt", fileId(empty, 1), false);
        none.setCueCount(0);
        Film conflict = filmWithParts("Conflict", 1, 90);
        sidecar("Conflict.en.forced.srt", fileId(conflict, 1), false);
        sidecar("Conflict.forced.en.srt", fileId(conflict, 1), false);
        Film unmatched = filmWithParts("Unmatched", 1, 90);
        sidecar("Unmatched.en.forced.srt", null, false);

        assertThat(served(missing)).isEmpty();
        assertThat(served(empty)).isEmpty();
        assertThat(served(conflict)).isEmpty();
        assertThat(served(unmatched)).isEmpty();
    }

    @Test
    void anEditionWhoseLaterPartHasNoDurationHasNoServedSubtitleBecauseTheTimelineIsUnknown() {
        Film film = filmWithParts("Towers", 2, 60);
        sidecar("Towers - pt2.en.forced.srt", fileId(film, 2), false);
        film.getEditions().get(0).originalParts().get(0).setDurationMs(null);

        assertThat(served(film)).isEmpty();
    }

    @Test
    void severalEditionsAreServedInOneCall() {
        Film one = filmWithParts("One", 1, 90);
        Film two = filmWithParts("Two", 1, 90);
        sidecar("One.en.forced.srt", fileId(one, 1), false);

        Map<Long, ServedSubtitle> served = forced.served(
            List.of(one.getEditions().get(0), two.getEditions().get(0)));

        assertThat(served).containsOnlyKeys(one.getEditions().get(0).getId());
    }

    @Test
    void noEditionsServeNothing() {
        assertThat(forced.served(List.of())).isEmpty();
    }

    @Test
    void sidecarsOnTwoLaterPartsAreBothMovedByTheirOwnPartStarts() {
        Film film = filmWithParts("Trilogy", 3, 60);
        sidecar("Trilogy - pt2.en.forced.srt", fileId(film, 2), false);
        sidecar("Trilogy - pt3.en.forced.srt", fileId(film, 3), false);

        ServedSubtitle served = served(film).get(film.getEditions().get(0).getId());

        assertThat(served.cues()).extracting(SubtitleCue::startMs).containsExactly(HOUR + 10_000, HOUR + 20_000,
            2 * HOUR + 10_000, 2 * HOUR + 20_000);
    }
}
