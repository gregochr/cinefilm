package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void plainFieldsAreJoinedByCommasAndEndInALineFeed() {
        assertThat(CsvWriter.row(List.of("a", "b c", "1.50"))).isEqualTo("a,b c,1.50\n");
    }

    @Test
    void aNullAndAnEmptyStringAreBothEmptyFields() {
        assertThat(CsvWriter.row(Arrays.asList("a", null, "", "b"))).isEqualTo("a,,,b\n");
    }

    @Test
    void aFieldWithACommaIsQuoted() {
        assertThat(CsvWriter.row(List.of("a,b", "c"))).isEqualTo("\"a,b\",c\n");
    }

    @Test
    void aFieldWithAQuoteIsQuotedAndTheQuoteDoubled() {
        assertThat(CsvWriter.row(List.of("say \"hi\""))).isEqualTo("\"say \"\"hi\"\"\"\n");
    }

    @Test
    void aFieldWithALineFeedOrACarriageReturnIsQuoted() {
        assertThat(CsvWriter.row(List.of("a\nb", "c\rd"))).isEqualTo("\"a\nb\",\"c\rd\"\n");
    }

    @Test
    void aSpecialCharacterAtTheStartOfAFieldQuotesIt() {
        assertThat(CsvWriter.row(List.of(",a", "\"b", "\nc", "\rd"))).isEqualTo("\",a\",\"\"\"b\",\"\nc\",\"\rd\"\n");
    }

    @Test
    void whatItWritesIsReadBackByTheReader() {
        List<String> row = List.of("plain", "a,b", "q\"q", "line\nbreak", "cr\rhere", "end");

        assertThat(CsvReader.parse(CsvWriter.row(row))).containsExactly(new ArrayList<>(row));
    }
}
