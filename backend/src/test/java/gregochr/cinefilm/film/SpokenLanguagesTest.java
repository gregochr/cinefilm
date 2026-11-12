package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Language codes from TMDB are untrusted: kept only when valid, and stored within the column. */
class SpokenLanguagesTest {

    @Test
    void aCodeIsLowerCasedTrimmedAndKeptOnlyWhenTwoOrThreeLetters() {
        assertThat(SpokenLanguages.code("en")).isEqualTo("en");
        assertThat(SpokenLanguages.code(" FR ")).isEqualTo("fr");
        assertThat(SpokenLanguages.code("fil")).isEqualTo("fil");
        assertThat(SpokenLanguages.code("e")).isNull();
        assertThat(SpokenLanguages.code("engl")).isNull();
        assertThat(SpokenLanguages.code("e1")).isNull();
        assertThat(SpokenLanguages.code("en,fr")).isNull();
        assertThat(SpokenLanguages.code("")).isNull();
        assertThat(SpokenLanguages.code(null)).isNull();
    }

    @Test
    void codesDropInvalidOnesAndRepeatsKeepingTheFirstOrder() {
        assertThat(SpokenLanguages.codes(Arrays.asList("FR", "en", "fr", null, "<b>", "EN", "ru")))
            .containsExactly("fr", "en", "ru");
        assertThat(SpokenLanguages.codes(List.of())).isEmpty();
    }

    @Test
    void storeJoinsWithCommasAndAnEmptyListIsTheEmptyString() {
        assertThat(SpokenLanguages.store(List.of("en", "fr", "ru"))).isEqualTo("en,fr,ru");
        assertThat(SpokenLanguages.store(List.of("en"))).isEqualTo("en");
        assertThat(SpokenLanguages.store(List.of())).isEmpty();
        assertThat(SpokenLanguages.store(List.of("!!", "x"))).isEmpty();
    }

    private static List<String> distinctCodes(int count) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; codes.size() < count; i++) {
            codes.add("" + (char) ('a' + i / 26 / 26 % 26) + (char) ('a' + i / 26 % 26) + (char) ('a' + i % 26));
        }
        return codes;
    }

    private static List<String> pairs(int count) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            codes.add("" + (char) ('a' + i) + (char) ('a' + i));
        }
        return codes;
    }

    private static List<String> join(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @Test
    void storeCutsAtAWholeCodeToFitTwoHundredCharacters() {
        // 50 three-letter codes joined by commas are 199 characters; a 51st would make 203.
        List<String> fifty = distinctCodes(50);
        assertThat(SpokenLanguages.store(fifty)).hasSize(199).isEqualTo(String.join(",", fifty));

        assertThat(SpokenLanguages.store(distinctCodes(60))).hasSize(199).isEqualTo(String.join(",", fifty));
    }

    @Test
    void storeKeepsAListOfExactlyTwoHundredCharacters() {
        // 48 three-letter codes are 191 characters; three more two-letter codes make 194, 197 and exactly 200.
        List<String> exact = join(distinctCodes(48), pairs(3));

        assertThat(SpokenLanguages.store(exact)).hasSize(200).isEqualTo(String.join(",", exact));
    }

    @Test
    void storeDropsTheCodeThatWouldMakeTwoHundredAndOne() {
        // 49 three-letter codes are 195 characters; two two-letter codes make 198 and then 201.
        List<String> codes = join(distinctCodes(49), pairs(2));

        assertThat(SpokenLanguages.store(codes)).hasSize(198)
            .isEqualTo(String.join(",", join(distinctCodes(49), pairs(1))));
    }

    @Test
    void parseReadsWhatStoreWroteAndNullOrEmptyAreNoCodes() {
        assertThat(SpokenLanguages.parse("en,fr,ru")).containsExactly("en", "fr", "ru");
        assertThat(SpokenLanguages.parse("en")).containsExactly("en");
        assertThat(SpokenLanguages.parse("")).isEmpty();
        assertThat(SpokenLanguages.parse("  ")).isEmpty();
        assertThat(SpokenLanguages.parse(null)).isEmpty();
    }
}
