package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;

class AutoCheckCandidateTest {

    @TempDir
    Path temp;

    @Test
    void theListIsReadFieldForFieldInFileOrder() throws IOException {
        Path file = temp.resolve("c.json");
        Files.writeString(file, """
            [{"editionId":7,"filmId":3,"mediaFileId":11,"path":"a/b.mkv","offsetMs":5000,"kind":"CRAWL",
              "startMs":65000,"markerMs":85000,"owner":true},
             {"editionId":8,"filmId":4,"mediaFileId":12,"path":"c.mkv","offsetMs":1,"kind":"CARD",
              "startMs":2,"markerMs":3,"owner":false}]
            """);

        List<AutoCheckCandidate> read = AutoCheckCandidate.read(file);

        assertThat(read).containsExactly(
            new AutoCheckCandidate(7, 3, 11, "a/b.mkv", 5_000, "CRAWL", 65_000, 85_000, true),
            new AutoCheckCandidate(8, 4, 12, "c.mkv", 1, "CARD", 2, 3, false));
    }

    @Test
    void anEmptyListIsEmpty() throws IOException {
        Path file = temp.resolve("c.json");
        Files.writeString(file, "[]");

        assertThat(AutoCheckCandidate.read(file)).isEmpty();
    }

    @Test
    void aFileThatIsNotAListIsRefused() throws IOException {
        Path file = temp.resolve("c.json");
        Files.writeString(file, "{\"error\":\"No deny list\"}");

        assertThatThrownBy(() -> AutoCheckCandidate.read(file)).isInstanceOf(JacksonException.class);
    }

    @Test
    void aMissingFileIsRefused() {
        assertThatThrownBy(() -> AutoCheckCandidate.read(temp.resolve("none.json")))
            .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void theFileOriginIsTheEditionTimeOfTheLastPartsStart() {
        AutoCheckCandidate two = new AutoCheckCandidate(1, 1, 1, "p", 5_000, "CRAWL", 65_000, 85_000, false);

        assertThat(two.fileOrigin()).isEqualTo(60_000);
    }
}
