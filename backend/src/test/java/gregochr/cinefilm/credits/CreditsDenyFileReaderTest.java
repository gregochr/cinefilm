package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The deny file's reader: a good file parses, and everything else (including no path at all) is unreadable. */
class CreditsDenyFileReaderTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        return Files.writeString(dir.resolve("deny.txt"), content);
    }

    @Test
    void aWellFormedFileIsParsed() throws IOException {
        Path file = write("# cinefilm-deny 2026-11-13 2\nfilm 12\nunit ctmdb:263\n");

        Parsed parsed = new CreditsDenyFileReader(file.toString()).read().orElseThrow();

        assertThat(parsed.manifestDate()).isEqualTo("2026-11-13");
        assertThat(parsed.count()).isEqualTo(2);
        assertThat(parsed.list().films()).containsExactly(12L);
        assertThat(parsed.list().units()).containsExactly("ctmdb:263");
    }

    @Test
    void anEmptyPathCountsAsUnreadable() {
        assertThat(new CreditsDenyFileReader("").read()).isEmpty();
        assertThat(new CreditsDenyFileReader("   ").read()).isEmpty();
    }

    @Test
    void aMissingFileAndADirectoryAreUnreadable() {
        assertThat(new CreditsDenyFileReader(dir.resolve("nope.txt").toString()).read()).isEmpty();
        assertThat(new CreditsDenyFileReader(dir.toString()).read()).isEmpty();
    }

    @Test
    void aMalformedFileIsUnreadable() throws IOException {
        Path file = write("# cinefilm-deny 2026-11-13 2\nfilm 12\n");

        assertThat(new CreditsDenyFileReader(file.toString()).read()).isEmpty();
    }

    @Test
    void aFileThatIsNotUtf8IsUnreadable() throws IOException {
        Path file = dir.resolve("deny.txt");
        Files.write(file, new byte[] {'#', ' ', (byte) 0xC3, 0x28});

        assertThat(new CreditsDenyFileReader(file.toString()).read()).isEmpty();
    }

    @Test
    void aPathTheSystemCannotParseIsUnreadable() {
        assertThat(new CreditsDenyFileReader("bad\0path").read()).isEqualTo(Optional.empty());
    }

    @Test
    void theFileIsReadAgainOnEveryCall() throws IOException {
        Path file = write("# cinefilm-deny 2026-11-13 1\nfilm 1\n");
        CreditsDenyFileReader reader = new CreditsDenyFileReader(file.toString());

        assertThat(reader.read().orElseThrow().list().films()).containsExactly(1L);
        Files.writeString(file, "# cinefilm-deny 2026-11-14 1\nfilm 2\n", StandardCharsets.UTF_8);

        assertThat(reader.read().orElseThrow().list().films()).containsExactly(2L);
    }
}
