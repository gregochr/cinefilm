package gregochr.cinefilm.stream;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FileSystemUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class StreamApiTest extends ApiTestBase {

    private static final String CONTENT = "0123456789abcdefghijklmnopqrstuvwxyz";

    @Autowired
    private LibraryRoot libraryRoot;

    private Path dir;

    private Path video;
    private Edition edition;

    @BeforeEach
    void data() throws IOException {
        Files.createDirectories(libraryRoot.root());
        dir = Files.createTempDirectory(libraryRoot.root(), "stream");
        video = dir.resolve("film.mp4");
        Files.writeString(video, CONTENT, StandardCharsets.UTF_8);
        Film film = film(null, "Heat", 1995, NOW);
        edition = edition(film, null, "Theatrical Cut", 100 * MIN, null);
        file(edition, rel(video));
        save(film);
    }

    @AfterEach
    void cleanUp() throws IOException {
        FileSystemUtils.deleteRecursively(dir);
    }

    private String rel(Path file) {
        return libraryRoot.relativize(file);
    }

    private String url() {
        return "/api/editions/" + edition.getId() + "/stream";
    }

    @Test
    void servesTheWholeFileWithContentTypeAndAcceptRanges() throws Exception {
        MvcResult result = mvc.perform(get(url()))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length()))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo(CONTENT);
    }

    @Test
    void rangeRequestGets206WithContentRange() throws Exception {
        MvcResult result = mvc.perform(get(url()).header(HttpHeaders.RANGE, "bytes=5-9"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 5-9/" + CONTENT.length()))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("56789");
    }

    @Test
    void openEndedAndSuffixRangesWork() throws Exception {
        MvcResult open = mvc.perform(get(url()).header(HttpHeaders.RANGE, "bytes=30-"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 30-35/" + CONTENT.length()))
            .andReturn();
        assertThat(open.getResponse().getContentAsString()).isEqualTo("uvwxyz");

        MvcResult suffix = mvc.perform(get(url()).header(HttpHeaders.RANGE, "bytes=-4"))
            .andExpect(status().isPartialContent())
            .andReturn();
        assertThat(suffix.getResponse().getContentAsString()).isEqualTo("wxyz");
    }

    @Test
    void unsatisfiableRangeIs416() throws Exception {
        mvc.perform(get(url()).header(HttpHeaders.RANGE, "bytes=500-600"))
            .andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(get(url()).header(HttpHeaders.RANGE, "bytes=abc"))
            .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    void anEditionSplitAcrossSeveralFilesIs409BecauseItPlaysOnlyAsOneAdaptiveSession() throws Exception {
        Film film = film(null, "Towers", 2002, NOW);
        Edition split = edition(film, null, "Extended", 200 * MIN, null);
        file(split, rel(video));
        MediaFile second = file(split, rel(video));
        second.setPartNumber(2);
        save(film);

        mvc.perform(get("/api/editions/{id}/stream", split.getId())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error")
                .value("This edition is split across several files and cannot be direct-played"));
    }

    @Test
    void unknownEditionIs404() throws Exception {
        mvc.perform(get("/api/editions/{id}/stream", 555555)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void editionWithoutAnOriginalFileIs404() throws Exception {
        Film film = film(null, "No File", 2000, NOW);
        Edition bare = edition(film, null, "Theatrical Cut", 90 * MIN, null);
        MediaFile premade = file(bare, rel(video));
        premade.setKind(MediaKind.PREMADE_720);
        save(film);
        mvc.perform(get("/api/editions/{id}/stream", bare.getId())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Edition has no original file"));
    }

    @Test
    void databasePathThatIsMissingOnDiskIs404() throws Exception {
        Film film = film(null, "Gone", 2000, NOW);
        Edition gone = edition(film, null, "Theatrical Cut", 90 * MIN, null);
        file(gone, rel(dir.resolve("deleted.mp4")));
        save(film);
        mvc.perform(get("/api/editions/{id}/stream", gone.getId())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Media file is not available"));
    }

    @Test
    void aDirectoryOrInvalidPathInTheDatabaseIs404() throws Exception {
        Film film = film(null, "Weird", 2000, NOW);
        Edition directory = edition(film, null, "Theatrical Cut", 90 * MIN, null);
        file(directory, rel(dir));
        Edition invalid = edition(film, null, "Other Cut", 90 * MIN, null);
        file(invalid, "bad\u0000path.mp4");
        save(film);
        mvc.perform(get("/api/editions/{id}/stream", directory.getId())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Media file is not available"));
        mvc.perform(get("/api/editions/{id}/stream", invalid.getId())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Media file is not available"));
    }

    @Test
    void neverServesAFileThatIsNotInTheDatabase() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "do not serve");
        Path other = Files.writeString(dir.resolve("other.mp4"), "unregistered");

        // The only input is a numeric id: path-like ids, traversal and query parameters never reach the disk.
        mvc.perform(get("/api/editions/{id}/stream", secret.toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/editions/{id}/stream", "../../etc/passwd")).andExpect(status().is4xxClientError());
        mvc.perform(get(url()).param("path", secret.toString()).param("file", other.toString()))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(CONTENT));
        mvc.perform(get(url()).param("start", "0").param("dim", "0")).andExpect(status().isOk());
        mvc.perform(get(url()).header("X-Path", secret.toString()))
            .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(CONTENT));
    }

    @Test
    void servesTheDatabasePathOfEachEdition() throws Exception {
        Path second = Files.writeString(dir.resolve("second.mkv"), "second edition");
        Film film = film(null, "Two Cuts", 2000, NOW);
        Edition one = edition(film, null, "A", 90 * MIN, null);
        file(one, rel(video));
        Edition two = edition(film, null, "B", 90 * MIN, null);
        file(two, rel(second));
        save(film);

        assertThat(mvc.perform(get("/api/editions/{id}/stream", one.getId())).andReturn().getResponse()
            .getContentAsString()).isEqualTo(CONTENT);
        MvcResult result = mvc.perform(get("/api/editions/{id}/stream", two.getId()))
            .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("second edition");
        assertThat(result.getResponse().getContentType()).startsWith("video/");
    }

    @Test
    void unknownExtensionFallsBackToOctetStream() throws Exception {
        Path blob = Files.writeString(dir.resolve("movie.zzzunknown"), "bytes");
        Film film = film(null, "Blob", 2000, NOW);
        Edition blobEdition = edition(film, null, "A", 90 * MIN, null);
        file(blobEdition, rel(blob));
        save(film);
        mvc.perform(get("/api/editions/{id}/stream", blobEdition.getId()))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"));
    }
}
