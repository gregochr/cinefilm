package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void readsPlainRowsWithLfOrCrlfAndNoFinalNewline() {
        assertThat(CsvReader.parse("a,b\nc,d\r\ne,f")).containsExactly(List.of("a", "b"), List.of("c", "d"),
            List.of("e", "f"));
    }

    @Test
    void aTrailingNewlineAndBlankLinesAddNoRows() {
        assertThat(CsvReader.parse("a,b\n\nc,d\n\n")).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void aLoneCarriageReturnEndsARowAndOneAtTheEndOfTheTextIsNotARow() {
        assertThat(CsvReader.parse("a,b\rc,d\r")).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void singleColumnRowsAreKeptWhenNotEmpty() {
        assertThat(CsvReader.parse("a\nb\n")).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void aQuotedFieldMayEndTheTextAndCloseOnItsLastCharacter() {
        assertThat(CsvReader.parse("a,\"b\"")).containsExactly(List.of("a", "b"));
        assertThat(CsvReader.parse("a,\"b\"\"\"")).containsExactly(List.of("a", "b\""));
    }

    @Test
    void emptyTextHasNoRows() {
        assertThat(CsvReader.parse("")).isEmpty();
    }

    @Test
    void emptyFieldsAreKeptAsEmptyStrings() {
        assertThat(CsvReader.parse("a,,c\n,,\n")).containsExactly(List.of("a", "", "c"), List.of("", "", ""));
    }

    @Test
    void aQuotedFieldKeepsCommasDoubledQuotesAndLineBreaks() {
        assertThat(CsvReader.parse("1,\"Beta, The \"\"Second\"\"\",\"two\nlines\r\nhere\",x\n")).containsExactly(
            List.of("1", "Beta, The \"Second\"", "two\nlines\r\nhere", "x"));
    }

    @Test
    void aQuotedEmptyFieldBetweenOthersIsEmpty() {
        assertThat(CsvReader.parse("a,\"\",c")).containsExactly(List.of("a", "", "c"));
    }

    @Test
    void anUnclosedQuoteIsAnError() {
        assertThatThrownBy(() -> CsvReader.parse("a,\"b"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("CSV ends inside a quoted field");
    }

    @Test
    void aQuoteAfterTextIsAnError() {
        assertThatThrownBy(() -> CsvReader.parse("ab\"c\""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("CSV has a quote in the middle of a field");
    }

    @Test
    void textAfterAClosingQuoteIsAnError() {
        assertThatThrownBy(() -> CsvReader.parse("\"ab\"\"c\"\"x\"y\""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("CSV has a quote in the middle of a field");
    }
}
