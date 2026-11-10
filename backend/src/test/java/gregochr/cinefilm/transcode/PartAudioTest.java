package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which audio stream of a later part is the one chosen in part 1: language, then rank, then the default. */
class PartAudioTest {

    private static final AudioChoice ENGLISH_51 = new AudioChoice(1, 0, true, "English");
    private static final AudioChoice FRENCH_STEREO = new AudioChoice(2, 1, false, "French");

    @Test
    void theSameLanguageWins() {
        List<AudioChoice> other = List.of(new AudioChoice(10, 0, true, "French"),
            new AudioChoice(11, 1, false, "English"));

        assertThat(PartAudio.match(ENGLISH_51, other).id()).isEqualTo(11);
        assertThat(PartAudio.match(FRENCH_STEREO, other).id()).isEqualTo(10);
    }

    @Test
    void theLanguageIsComparedIgnoringCase() {
        assertThat(PartAudio.match(ENGLISH_51, List.of(new AudioChoice(10, 0, false, "ENGLISH"))).id()).isEqualTo(10);
    }

    @Test
    void whenSeveralShareTheLanguageTheOneAtTheSameRankIsChosen() {
        List<AudioChoice> other = List.of(new AudioChoice(10, 0, false, "English"),
            new AudioChoice(11, 1, true, "English"));

        assertThat(PartAudio.match(new AudioChoice(2, 1, false, "English"), other).id()).isEqualTo(11);
        assertThat(PartAudio.match(ENGLISH_51, other).id()).isEqualTo(10);
    }

    @Test
    void whenSeveralShareTheLanguageButNoneIsAtTheSameRankTheFirstIsChosen() {
        AudioChoice third = new AudioChoice(3, 2, false, "English");
        List<AudioChoice> other = List.of(new AudioChoice(10, 0, false, "English"),
            new AudioChoice(11, 1, true, "English"));

        assertThat(PartAudio.match(third, other).id()).isEqualTo(10);
    }

    @Test
    void withNoStreamInTheSameLanguageTheStreamAtTheSameRankIsChosen() {
        List<AudioChoice> other = List.of(new AudioChoice(10, 0, true, "German"),
            new AudioChoice(11, 1, false, "Spanish"));

        assertThat(PartAudio.match(FRENCH_STEREO, other).id()).isEqualTo(11);
    }

    @Test
    void withNoSameLanguageAndNoSameRankTheDefaultIsChosenElseTheFirst() {
        AudioChoice fifth = new AudioChoice(5, 4, false, "French");

        assertThat(PartAudio.match(fifth, List.of(new AudioChoice(10, 0, false, "German"),
            new AudioChoice(11, 1, true, "Spanish"))).id()).isEqualTo(11);
        assertThat(PartAudio.match(fifth, List.of(new AudioChoice(10, 0, false, "German"),
            new AudioChoice(11, 1, false, "Spanish"))).id()).isEqualTo(10);
    }

    @Test
    void aStreamWithNoLanguageInTheLaterPartIsNotTakenForTheChosenLanguage() {
        List<AudioChoice> other = List.of(new AudioChoice(10, 0, false), new AudioChoice(11, 1, true, "English"));

        assertThat(PartAudio.match(ENGLISH_51, other).id()).isEqualTo(11);
        assertThat(PartAudio.match(new AudioChoice(3, 0, false, "French"), other).id()).isEqualTo(10);
        assertThat(PartAudio.match(new AudioChoice(7, 1, false), List.of(new AudioChoice(10, 0, false, "English"),
            new AudioChoice(11, 1, true, "English"))).id()).isEqualTo(11);
    }

    @Test
    void aStreamWithNoLanguageMatchesByRankNotByLanguage() {
        AudioChoice unknown = new AudioChoice(7, 1, false);
        List<AudioChoice> other = List.of(new AudioChoice(10, 0, true), new AudioChoice(11, 1, false));

        assertThat(PartAudio.match(unknown, other).id()).isEqualTo(11);
        assertThat(PartAudio.match(ENGLISH_51, List.of(new AudioChoice(10, 0, false))).id()).isEqualTo(10);
    }
}
