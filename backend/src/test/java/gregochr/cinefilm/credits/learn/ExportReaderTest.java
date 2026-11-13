package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportReaderTest {

    private static final String HEADER = String.join(",", ExportReader.COLUMNS);

    private static String fixtureRow(String... overrides) {
        String[] fields = {"7", "A.m4v", "10:20", "6000000", "3", "t", "9", "1001", "Title", "2000", "5", "500",
            "4800000", "10:20", "PRESENT"};
        for (int i = 0; i < overrides.length; i += 2) {
            fields[Integer.parseInt(overrides[i])] = overrides[i + 1];
        }
        return String.join(",", fields);
    }

    @Test
    void theColumnsAreTheExportsInOrder() {
        assertThat(ExportReader.COLUMNS).containsExactly("media_file_id", "path", "fingerprint", "duration_ms",
            "edition_id", "is_last_original_part", "film_id", "film_tmdb_id", "film_title", "film_year",
            "collection_id", "collection_tmdb_id", "plex_start_ms", "plex_fingerprint", "presence");
    }

    @Test
    void readsEveryFieldOfARow() {
        assertThat(ExportReader.parse(HEADER + "\n" + fixtureRow())).containsExactly(new ExportRow(7, "A.m4v",
            "10:20", 6_000_000, 3, true, 9, 1001L, "Title", 2000, 5L, 500L, 4_800_000, "10:20", "PRESENT"));
    }

    @Test
    void emptyOptionalFieldsAreNull() {
        List<ExportRow> rows = ExportReader.parse(HEADER + "\n" + fixtureRow("5", "", "7", "", "9", "", "10", "",
            "11", ""));
        assertThat(rows.get(0)).isEqualTo(new ExportRow(7, "A.m4v", "10:20", 6_000_000, 3, null, 9, null, "Title",
            null, null, null, 4_800_000, "10:20", "PRESENT"));
    }

    @Test
    void fAndTAreTheBooleans() {
        assertThat(ExportReader.parse(HEADER + "\n" + fixtureRow("5", "f")).get(0).lastOriginalPart()).isFalse();
    }

    @Test
    void anythingElseInTheBooleanColumnIsAnError() {
        assertThatThrownBy(() -> ExportReader.parse(HEADER + "\n" + fixtureRow("5", "true")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("export row 1: is_last_original_part must be t or f");
    }

    @Test
    void aWrongHeaderIsAnError() {
        String wrong = HEADER.replace("path,fingerprint", "fingerprint,path");
        assertThatThrownBy(() -> ExportReader.parse(wrong + "\n" + fixtureRow()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("export header must be " + HEADER);
    }

    @Test
    void anEmptyFileIsAWrongHeader() {
        assertThatThrownBy(() -> ExportReader.parse(""))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("export header must be " + HEADER);
    }

    @Test
    void aHeaderOnlyFileHasNoRows() {
        assertThat(ExportReader.parse(HEADER + "\n")).isEmpty();
    }

    @Test
    void aRowWithTheWrongNumberOfFieldsIsAnError() {
        assertThatThrownBy(() -> ExportReader.parse(HEADER + "\n" + fixtureRow() + ",extra"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("export row 1 has 16 fields, not 15");
    }

    @Test
    void aRequiredFieldCannotBeEmpty() {
        String text = HEADER + "\n" + fixtureRow() + "\n" + fixtureRow("0", "8", "12", "");
        assertThatThrownBy(() -> ExportReader.parse(text))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("export row 2: plex_start_ms is empty");
    }

    @Test
    void aNumberFieldMustBeANumber() {
        assertThatThrownBy(() -> ExportReader.parse(HEADER + "\n" + fixtureRow("3", "6e6")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("export row 1: duration_ms is not a number");
    }

    @Test
    void aRepeatedFileIdIsAnError() {
        assertThatThrownBy(() -> ExportReader.parse(HEADER + "\n" + fixtureRow() + "\n" + fixtureRow()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("export row 2 repeats media_file_id 7");
    }

    @Test
    void readsTheFixtureExportWithCrlfQuotedTitlesAndDecomposedPaths() throws IOException {
        String text = Files.readString(LearnFixtures.dir().resolve("export.csv"), StandardCharsets.UTF_8);
        List<ExportRow> rows = ExportReader.parse(text);
        assertThat(rows).hasSize(19);
        assertThat(rows.get(0).mediaFileId()).isEqualTo(119);
        assertThat(rows.get(0).lastOriginalPart()).isNull();
        ExportRow beta = rows.stream().filter(r -> r.mediaFileId() == 102).findFirst().orElseThrow();
        assertThat(beta.filmTitle()).isEqualTo("Beta, The \"Second\"");
        ExportRow amelie = rows.stream().filter(r -> r.mediaFileId() == 118).findFirst().orElseThrow();
        assertThat(amelie.path()).isEqualTo("Amélie.m4v");
        assertThat(amelie.filmTmdbId()).isNull();
    }
}
