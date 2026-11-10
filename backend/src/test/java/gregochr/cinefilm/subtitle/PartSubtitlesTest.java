package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.SubtitleStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which subtitle stream of a later part is the one chosen in part 1: language and forced flag, then rank. */
class PartSubtitlesTest {

    private static SubtitleStream stream(int index, String language, boolean forced) {
        SubtitleStream stream = new SubtitleStream();
        stream.setId(100L + index);
        stream.setIndex(index);
        stream.setLanguage(language);
        stream.setForced(forced);
        return stream;
    }

    private final SubtitleStream english = stream(3, "English", false);
    private final SubtitleStream englishForced = stream(4, "English", true);
    private final SubtitleStream french = stream(5, "French", false);
    private final List<SubtitleStream> first = List.of(english, englishForced, french);

    @Test
    void theSameLanguageAndForcedFlagWins() {
        List<SubtitleStream> other = List.of(stream(7, "French", false), stream(8, "English", true),
            stream(9, "English", false));

        assertThat(PartSubtitles.match(english, first, other)).get().extracting(SubtitleStream::getIndex)
            .isEqualTo(9);
        assertThat(PartSubtitles.match(englishForced, first, other)).get().extracting(SubtitleStream::getIndex)
            .isEqualTo(8);
        assertThat(PartSubtitles.match(french, first, other)).get().extracting(SubtitleStream::getIndex)
            .isEqualTo(7);
    }

    @Test
    void whenSeveralQualifyTheOneAtTheSameRankIsChosenElseTheFirst() {
        List<SubtitleStream> other = List.of(stream(7, "English", false), stream(8, "English", false));

        assertThat(PartSubtitles.match(english, first, other)).get().extracting(SubtitleStream::getIndex)
            .isEqualTo(7);
        SubtitleStream secondEnglish = stream(6, "english", false);
        assertThat(PartSubtitles.match(secondEnglish, List.of(english, secondEnglish), other)).get()
            .extracting(SubtitleStream::getIndex).isEqualTo(8);
        SubtitleStream third = stream(11, "English", false);
        assertThat(PartSubtitles.match(third, List.of(english, englishForced, third), other)).get()
            .extracting(SubtitleStream::getIndex).isEqualTo(7);
    }

    @Test
    void withNoMatchingLanguageTheStreamAtTheSameRankIsChosenWhenThereIsOne() {
        List<SubtitleStream> other = List.of(stream(7, "German", false), stream(8, "Spanish", false));

        assertThat(PartSubtitles.match(englishForced, first, other)).get().extracting(SubtitleStream::getIndex)
            .isEqualTo(8);
    }

    @Test
    void withNoMatchingLanguageAndNoStreamAtTheRankThereIsNoMatch() {
        assertThat(PartSubtitles.match(french, first, List.of(stream(7, "German", false)))).isEmpty();
        assertThat(PartSubtitles.match(english, first, List.of())).isEmpty();
    }

    @Test
    void aStreamThatIsNotAmongPartOnesStreamsMatchesNothingByRank() {
        SubtitleStream stranger = stream(9, "Danish", false);

        assertThat(PartSubtitles.match(stranger, first, List.of(stream(7, "German", false)))).isEmpty();
    }

    @Test
    void theForcedFlagMustAgreeEvenWhenTheLanguageDoes() {
        List<SubtitleStream> other = List.of(stream(7, "English", false));

        assertThat(PartSubtitles.match(englishForced, first, other)).isEmpty();
    }

    @Test
    void aMissingLanguageMatchesOnlyAMissingLanguage() {
        SubtitleStream unknown = stream(3, null, false);
        List<SubtitleStream> other = List.of(stream(7, "English", false), stream(8, null, false));

        assertThat(PartSubtitles.match(unknown, List.of(unknown), other)).get()
            .extracting(SubtitleStream::getIndex).isEqualTo(8);
        assertThat(PartSubtitles.match(french, first, List.of(stream(8, null, false)))).isEmpty();
    }

    @Test
    void streamsAreRankedByTheirFileIndexNotTheirListOrder() {
        List<SubtitleStream> shuffled = List.of(french, english);
        List<SubtitleStream> other = List.of(stream(9, "German", false), stream(7, "Spanish", false));

        assertThat(PartSubtitles.match(english, shuffled, other)).get().extracting(SubtitleStream::getIndex)
            .isEqualTo(7);
    }
}
