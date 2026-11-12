package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.EmbeddedStreams.Choice;
import gregochr.cinefilm.subtitle.EmbeddedStreams.Stream;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Which subtitle track inside a film file is the full English one: candidates, and the choice among them. */
class EmbeddedStreamsTest {

    private static Stream text(int index, String language, boolean forced, boolean isDefault, String title) {
        return new Stream(index, "subrip", language, forced, isDefault, title);
    }

    private static Stream picture(int index, String language, boolean forced, boolean isDefault, String title) {
        return new Stream(index, "hdmv_pgs_subtitle", language, forced, isDefault, title);
    }

    private static List<Long> cues(int count) {
        return LongStream.range(0, count).map(i -> i * 5_000).boxed().toList();
    }

    @Test
    void onlyEnglishTracksThatAreNotForcedAreCandidates() {
        List<Stream> streams = List.of(
            text(2, "eng", false, false, null),
            text(3, "en", false, false, "English"),
            text(4, "en-US", false, false, null),
            text(5, "fre", false, false, null),
            text(6, null, false, false, null),
            text(7, "eng", true, false, null),
            text(8, "eng", false, false, "English (Forced)"),
            text(9, "eng", false, false, "Foreign parts only"),
            text(10, "eng", false, false, "Signs & Songs"),
            text(11, "eng", false, false, "Design notes"));

        assertThat(EmbeddedStreams.candidates(streams)).extracting(Stream::index).containsExactly(2, 3, 4, 11);
    }

    @Test
    void aTextTrackBeatsAPictureTrackEvenWithFewerCues() {
        List<Stream> streams = List.of(picture(2, "eng", false, true, "Full"), text(3, "eng", false, false, null));

        Choice choice = EmbeddedStreams.choose(streams, Map.of(2, cues(900), 3, cues(300)));

        assertThat(choice.stream().index()).isEqualTo(3);
        assertThat(choice.starts()).hasSize(300);
        assertThat(choice.reason()).isNull();
    }

    @Test
    void aDefaultTrackBeatsOneWithMoreCues() {
        List<Stream> streams = List.of(text(2, "eng", false, false, null), text(3, "eng", false, true, null));

        assertThat(EmbeddedStreams.choose(streams, Map.of(2, cues(900), 3, cues(300))).stream().index()).isEqualTo(3);
    }

    @Test
    void aFullOrSdhTitledTrackBeatsOneWithMoreCues() {
        List<Stream> streams = List.of(text(2, "eng", false, false, "English"), text(4, "eng", false, false, "SDH"));

        assertThat(EmbeddedStreams.choose(streams, Map.of(2, cues(900), 4, cues(400))).stream().index()).isEqualTo(4);
    }

    @Test
    void amongEqualsTheTrackWithMostCuesWinsThenTheLowestIndex() {
        List<Stream> streams = List.of(text(2, "eng", false, false, null), text(3, "eng", false, false, null),
            text(4, "eng", false, false, null));

        assertThat(EmbeddedStreams.choose(streams, Map.of(2, cues(200), 3, cues(500), 4, cues(500))).stream().index())
            .isEqualTo(3);
    }

    @Test
    void aTrackWithFewerThanAHundredCuesIsNotAFullTrack() {
        List<Stream> streams = List.of(text(2, "eng", false, true, "Full"), text(3, "eng", false, false, null));

        Choice choice = EmbeddedStreams.choose(streams, Map.of(2, cues(99), 3, cues(100)));

        assertThat(choice.stream().index()).isEqualTo(3);
        assertThat(EmbeddedStreams.choose(streams, Map.of(2, cues(99), 3, cues(40))).stream()).isNull();
    }

    @Test
    void whenNoTrackIsFullTheReasonSaysSo() {
        Choice choice = EmbeddedStreams.choose(List.of(picture(2, "eng", false, false, null)), Map.of(2, cues(30)));

        assertThat(choice.stream()).isNull();
        assertThat(choice.starts()).isEmpty();
        assertThat(choice.reason()).isEqualTo("The English subtitle track has too few lines to be a full track");
    }

    @Test
    void aTrackWithNoReadStartsCountsAsEmpty() {
        Choice choice = EmbeddedStreams.choose(List.of(text(2, "eng", false, false, null)), Map.of());

        assertThat(choice.stream()).isNull();
    }

    @Test
    void aFileWithNoSubtitleTracksSaysSo() {
        assertThat(EmbeddedStreams.choose(List.of(), Map.of()).reason()).isEqualTo("The file has no subtitle track");
    }

    @Test
    void aFileWhoseTracksAreNotFullEnglishOnesSaysHowManyItHas() {
        Choice one = EmbeddedStreams.choose(List.of(text(2, "fre", false, false, null)), Map.of());
        Choice two = EmbeddedStreams.choose(List.of(text(2, "fre", false, false, null),
            text(3, "eng", true, false, null)), Map.of());

        assertThat(one.reason()).isEqualTo("The file has no full English subtitle track (1 subtitle track, not a full "
            + "English one)");
        assertThat(two.reason()).isEqualTo("The file has no full English subtitle track (2 subtitle tracks, none a "
            + "full English one)");
    }

    @Test
    void aStreamWithNoCodecIsAPicture() {
        assertThat(new Stream(2, null, "eng", false, false, null).picture()).isTrue();
        assertThat(new Stream(2, "dvd_subtitle", "eng", false, false, null).picture()).isTrue();
        assertThat(new Stream(2, "DVB_SUBTITLE", "eng", false, false, null).picture()).isTrue();
        assertThat(new Stream(2, "ass", "eng", false, false, null).picture()).isFalse();
        assertThat(new Stream(2, "webvtt", "eng", false, false, null).picture()).isFalse();
    }
}
