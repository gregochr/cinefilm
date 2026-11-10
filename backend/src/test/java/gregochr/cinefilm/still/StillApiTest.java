package gregochr.cinefilm.still;

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
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

/** The whole stack over H2 with a fake ffmpeg: the real service, controller and exception mapping. */
class StillApiTest extends ApiTestBase {

    @Autowired
    private LibraryRoot libraryRoot;

    @MockitoBean
    private CommandRunner runner;

    private Path dir;
    private Edition edition;

    @BeforeEach
    void data() throws IOException {
        Files.createDirectories(libraryRoot.root());
        dir = Files.createTempDirectory(libraryRoot.root(), "still");
        Path video = Files.writeString(dir.resolve("film.mp4"), "not really a video " + System.nanoTime());
        Film film = film(null, "Heat", 1995, NOW);
        edition = edition(film, null, "Theatrical Cut", 100 * MIN, null);
        file(edition, libraryRoot.relativize(video));
        save(film);
    }

    @AfterEach
    void cleanUp() throws IOException {
        FileSystemUtils.deleteRecursively(dir);
    }

    private String url(long atMs) {
        return "/api/editions/" + edition.getId() + "/still?atMs=" + atMs;
    }

    private void ffmpegWritesAJpeg() throws IOException {
        org.mockito.Mockito.when(runner.run(org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                List<String> command = invocation.getArgument(0);
                Files.write(Path.of(command.get(command.size() - 1)), new byte[] {(byte) 0xFF, (byte) 0xD8, 7});
                return new CommandRunner.Result(0, "", false);
            });
    }

    @Test
    void servesTheGrabbedFrameAsJpegWithAPrivateCache() throws Exception {
        ffmpegWritesAJpeg();

        MvcResult result = mvc.perform(get(url(12_300)))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=86400, private"))
            .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).startsWith((byte) 0xFF, (byte) 0xD8);
    }

    @Test
    void anAtMsPastTheEndIs400EvenWhenOnlyImageJpegIsAccepted() throws Exception {
        mvc.perform(get(url(101 * MIN)).header(HttpHeaders.ACCEPT, "image/jpeg"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("atMs must be between 0 and the film's length"));
    }

    @Test
    void anUnknownEditionIs404EvenWhenOnlyImageJpegIsAccepted() throws Exception {
        mvc.perform(get("/api/editions/999999/still?atMs=1").header(HttpHeaders.ACCEPT, "image/jpeg"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void ffmpegFailingIs503EvenWhenOnlyImageJpegIsAccepted() throws Exception {
        org.mockito.Mockito.when(runner.run(org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any())).thenThrow(new IOException("ffmpeg missing"));

        mvc.perform(get(url(5_000)).header(HttpHeaders.ACCEPT, "image/jpeg"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("Frame is not available right now"));
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url(1_000)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
