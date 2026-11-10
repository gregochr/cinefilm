package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchTextTest {

    @Test
    void wordsFoldCaseAccentsApostrophesAndPunctuation() {
        assertThat(SearchText.words("Amélie")).containsExactly("amelie");
        assertThat(SearchText.words("Gavin O'Connor")).containsExactly("gavin", "oconnor");
        assertThat(SearchText.words("Gavin O’Connor ‘x‘")).containsExactly("gavin", "oconnor", "x");
        assertThat(SearchText.words("WALL·E")).containsExactly("wall", "e");
        assertThat(SearchText.words("  Spider-Man:  Homecoming ")).containsExactly("spider", "man", "homecoming");
        assertThat(SearchText.words("2001: A Space Odyssey")).startsWith("2001", "a");
    }

    @Test
    void wordsOfNothingAreEmpty() {
        assertThat(SearchText.words(null)).isEmpty();
        assertThat(SearchText.words("")).isEmpty();
        assertThat(SearchText.words(" ... ")).isEmpty();
    }

    @Test
    void aWordCanBeUsedByOneTermOnly() {
        assertThat(SearchText.matches("ha ha", "Hancock")).isFalse();
        assertThat(SearchText.matches("ha ha", "Ha Ha Hancock")).isTrue();
        // Backtracking: "h" first takes "hang", which "hang" then needs.
        assertThat(SearchText.matches("h hang", "Hang Hero")).isTrue();
        assertThat(SearchText.matches("hang h", "Hang")).isFalse();
    }

    @Test
    void aQueryWithNoWordsMatchesNothing() {
        assertThat(SearchText.matches(null, "Heat")).isFalse();
        assertThat(SearchText.matches("  ", "Heat")).isFalse();
        assertThat(SearchText.allPrefix(List.of("a"), List.of())).isFalse();
    }

    static Stream<Object[]> vectors() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docs/design/search/vectors.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root with docs/design/search/vectors.json").isNotNull();
        String json = Files.readString(dir.resolve("docs/design/search/vectors.json"));
        JsonNode cases = JsonMapper.builder().build().readTree(json);
        return Stream.iterate(0, i -> i + 1).limit(cases.size()).map(i -> new Object[] {
            cases.get(i).get("query").asText(), cases.get(i).get("name").asText(),
            cases.get(i).get("matches").asBoolean()});
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @MethodSource("vectors")
    void sharedVectors(String query, String name, boolean matches) {
        assertThat(SearchText.matches(query, name)).isEqualTo(matches);
    }
}
