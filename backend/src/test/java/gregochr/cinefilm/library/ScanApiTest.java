package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

/**
 * The whole scan on a temporary library: a real scanner, ingest, database, Admin API and stream endpoint, with
 * ffprobe and TMDB faked (their own tests cover them).
 */
class ScanApiTest extends ApiTestBase {

    private static final Instant T1 = Instant.parse("2024-01-01T10:00:00Z");

    @Autowired
    private LibraryRoot libraryRoot;

    @Autowired
    private MediaFileRepository mediaFiles;

    @Autowired
    private EntityManager em;

    @MockitoBean(name = "scanExecutor")
    private Executor executor;

    @MockitoBean
    private FfprobeService ffprobe;

    @MockitoBean
    private TmdbMatcher matcher;

    @MockitoBean
    private TmdbApi tmdb;

    private final List<Runnable> queued = new ArrayList<>();
    private Path library;

    @BeforeEach
    void setUp() throws IOException {
        library = libraryRoot.root();
        Files.createDirectories(library);
        empty();
        when(tmdb.enabled()).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
        when(ffprobe.probe(any())).thenAnswer(invocation -> {
            Path file = invocation.getArgument(0);
            String name = file.getFileName().toString();
            if (name.startsWith("Corrupt")) {
                throw new ProbeException("ffprobe exited with status 1", null);
            }
            return new ProbeResult(5_400_000L, "mp4", "H.264", 1920, 800, 9_000_000L,
                List.of(new ProbeResult.Audio(1, "English", "Stereo", "AAC", null, true, true)),
                name.startsWith("Subbed")
                    ? List.of(new ProbeResult.Subtitle(2, "English", false, null, "mov_text")) : List.of(),
                new ProbeResult.Tags(null, null, null, null, null), name.startsWith("Drm"));
        });
        when(matcher.match(any(), anyLong())).thenAnswer(invocation -> {
            TitleGuess guess = invocation.getArgument(0);
            if (guess.title().startsWith("Mystery")) {
                return new MatchOutcome(Status.NEEDS_MATCH, null,
                    List.of(new Candidate(11, "Mystery Film", 1990, 95, "/m.jpg", 40),
                        new Candidate(12, "Mystery Film", 2010, 101, null, 40)));
            }
            String base = guess.title().replace(" Directors Cut", "");
            return new MatchOutcome(Status.MATCHED, new MovieDetails(base.hashCode(), base, base, 2000, "About " + base,
                90, List.of("Drama"), "/p.jpg", "/b.jpg", 1, List.of(), List.of(), null), List.of());
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        empty();
    }

    private void empty() throws IOException {
        try (Stream<Path> children = Files.list(library)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    FileSystemUtils.deleteRecursively(child);
                } else {
                    Files.delete(child);
                }
            }
        }
    }

    private Path film(String relative, String content, Instant modified) throws IOException {
        Path path = library.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FileTime.from(modified));
        return path;
    }

    private void scan() throws Exception {
        mvc.perform(post("/api/admin/scan")).andExpect(status().isAccepted());
    }

    @Test
    void scanningAddsTheFilmsAndTheyBrowseAndPlayEndToEnd() throws Exception {
        film("Heat.m4v", "0123456789", T1);
        film("Sub/Subbed Film.m4v", "frames", T1);

        mvc.perform(post("/api/admin/scan"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.folder").value(library.toString()))
            .andExpect(jsonPath("$.lastScan.trigger").value("admin"))
            .andExpect(jsonPath("$.lastScan.filesSeen").value(2))
            .andExpect(jsonPath("$.lastScan.newFiles").value(2))
            .andExpect(jsonPath("$.lastScan.missingFiles").value(0))
            .andExpect(jsonPath("$.lastScan.error").doesNotExist())
            .andExpect(jsonPath("$.counts.films").value(2))
            .andExpect(jsonPath("$.counts.newFiles").value(2))
            .andExpect(jsonPath("$.counts.missing").value(0))
            .andExpect(jsonPath("$.counts.protectedFiles").value(0))
            .andExpect(jsonPath("$.counts.needsConversion").value(0))
            .andExpect(jsonPath("$.counts.needsMatch").value(0))
            .andExpect(jsonPath("$.progress").doesNotExist());

        mvc.perform(get("/api/films"))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.films[*].title").value(org.hamcrest.Matchers.containsInAnyOrder("Heat",
                "Subbed Film")));
        Film heat = films.findAll().stream().filter(f -> f.getTitle().equals("Heat")).findFirst().orElseThrow();
        MediaFile file = heat.getEditions().get(0).originalFile();
        mvc.perform(get("/api/films/{id}", heat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].name").value("Original"))
            .andExpect(jsonPath("$.editions[0].runtimeMs").value(5_400_000))
            .andExpect(jsonPath("$.editions[0].file.id").value(file.getId()))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].channels").value("Stereo"));
        mvc.perform(get("/api/editions/{id}/stream", heat.getEditions().get(0).getId())
                .header(HttpHeaders.RANGE, "bytes=2-5"))
            .andExpect(status().isPartialContent())
            .andExpect(content().string("2345"));
    }

    @Test
    void aRescanChangesNothingWhenNothingChanged() throws Exception {
        film("Heat.m4v", "0123456789", T1);
        scan();
        long filmCount = films.count();
        long fileCount = mediaFiles.count();

        mvc.perform(post("/api/admin/scan")).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.lastScan.newFiles").value(0))
            .andExpect(jsonPath("$.lastScan.filesSeen").value(1))
            .andExpect(jsonPath("$.counts.films").value(1));

        assertThat(films.count()).isEqualTo(filmCount);
        assertThat(mediaFiles.count()).isEqualTo(fileCount);
    }

    @Test
    void aRescanPicksUpAChangedFileAndAddsANewOne() throws Exception {
        Path heat = film("Heat.m4v", "0123456789", T1);
        scan();
        Files.writeString(heat, "0123456789ABCDEF");
        film("Alien.m4v", "xx", T1);

        mvc.perform(post("/api/admin/scan")).andExpect(jsonPath("$.lastScan.newFiles").value(1))
            .andExpect(jsonPath("$.counts.films").value(2));

        em.flush();
        em.clear();
        assertThat(mediaFiles.findAll()).extracting(MediaFile::getSizeBytes).containsExactlyInAnyOrder(16L, 2L);
    }

    @Test
    void aRenamedFileIsTheSameEditionWithItsWatchHistoryNotANewFilm() throws Exception {
        Path old = film("Heat.m4v", "0123456789", T1);
        scan();
        Film heat = films.findAll().get(0);
        Long editionId = heat.getEditions().get(0).getId();
        PlaybackState state = new PlaybackState(editionId);
        state.setPositionMs(3_000_000L);
        state.setWatched(true);
        states.save(state);
        Files.move(old, library.resolve("Heat (Remastered).m4v"));

        mvc.perform(post("/api/admin/scan")).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.lastScan.newFiles").value(0))
            .andExpect(jsonPath("$.lastScan.missingFiles").value(0))
            .andExpect(jsonPath("$.counts.films").value(1));

        em.flush();
        em.clear();
        assertThat(films.count()).isEqualTo(1);
        assertThat(mediaFiles.findAll()).singleElement().satisfies(file -> {
            assertThat(file.getPath()).isEqualTo("Heat (Remastered).m4v");
            assertThat(file.getEdition().getId()).isEqualTo(editionId);
        });
        assertThat(states.findById(editionId).orElseThrow().getPositionMs()).isEqualTo(3_000_000L);
    }

    @Test
    void aVanishedFileIsMissingAndHiddenFromBrowseButTheFilmAndHistoryStayAndReturnWithTheFile()
        throws Exception {
        Path gone = film("Heat.m4v", "0123456789", T1);
        film("Alien.m4v", "xx", T1);
        scan();
        Film heat = films.findAll().stream().filter(f -> f.getTitle().equals("Heat")).findFirst().orElseThrow();
        Long editionId = heat.getEditions().get(0).getId();
        PlaybackState state = new PlaybackState(editionId);
        state.setPositionMs(99);
        states.save(state);
        byte[] bytes = Files.readAllBytes(gone);
        Files.delete(gone);

        mvc.perform(post("/api/admin/scan"))
            .andExpect(jsonPath("$.lastScan.missingFiles").value(1))
            .andExpect(jsonPath("$.counts.films").value(1))
            .andExpect(jsonPath("$.counts.missing").value(1));
        mvc.perform(get("/api/films")).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.films[0].title").value("Alien"));
        mvc.perform(get("/api/films/{id}", heat.getId())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such film"));
        mvc.perform(get("/api/editions/{id}/stream", editionId)).andExpect(status().isNotFound());
        assertThat(films.findById(heat.getId())).isPresent();
        assertThat(states.findById(editionId).orElseThrow().getPositionMs()).isEqualTo(99);

        Files.write(gone, bytes);
        Files.setLastModifiedTime(gone, FileTime.from(T1));

        mvc.perform(post("/api/admin/scan"))
            .andExpect(jsonPath("$.counts.films").value(2))
            .andExpect(jsonPath("$.counts.missing").value(0));
        mvc.perform(get("/api/films/{id}", heat.getId())).andExpect(status().isOk());
    }

    @Test
    void twoFilesForOneFilmAreTwoEditionsAndAreListedAsSeveralFilesMatchingOne() throws Exception {
        film("Heat.m4v", "0123456789", T1);
        film("Heat Directors Cut.m4v", "0123456789AB", T1);

        mvc.perform(post("/api/admin/scan")).andExpect(jsonPath("$.counts.films").value(1));

        assertThat(films.count()).isEqualTo(1);
        assertThat(films.findAll().get(0).getEditions()).hasSize(2);
        mvc.perform(get("/api/admin/attention"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].kind").value("multiple-files"))
            .andExpect(jsonPath("$.items[0].title").value("Heat"))
            .andExpect(jsonPath("$.items[0].detail").value("2 files match one film"))
            .andExpect(jsonPath("$.items[0].files[0]").value("Heat Directors Cut.m4v"))
            .andExpect(jsonPath("$.items[0].files[1]").value("Heat.m4v"));
    }

    @Test
    void protectedConversionUnreadableAndUnmatchedFilesGoToTheAttentionListNotBrowse() throws Exception {
        film("Drm Film.m4v", "a", T1);
        film("Old.avi", "bb", T1);
        film("Corrupt.m4v", "ccc", T1);
        film("Mystery Film.m4v", "dddd", T1);
        film("Fine.m4v", "eeeee", T1);

        mvc.perform(post("/api/admin/scan"))
            .andExpect(jsonPath("$.counts.films").value(2))
            .andExpect(jsonPath("$.counts.protectedFiles").value(1))
            .andExpect(jsonPath("$.counts.needsConversion").value(1))
            .andExpect(jsonPath("$.counts.needsMatch").value(1))
            .andExpect(jsonPath("$.lastScan.newFiles").value(5));

        mvc.perform(get("/api/films")).andExpect(jsonPath("$.films[*].title")
            .value(org.hamcrest.Matchers.containsInAnyOrder("Fine", "Mystery Film")));
        mvc.perform(get("/api/admin/attention"))
            .andExpect(jsonPath("$.items.length()").value(4))
            .andExpect(jsonPath("$.items[0].kind").value("needs-match"))
            .andExpect(jsonPath("$.items[0].title").value("Mystery Film"))
            .andExpect(jsonPath("$.items[0].detail").value("2 possible matches"))
            .andExpect(jsonPath("$.items[0].files[0]").value("Mystery Film.m4v"))
            .andExpect(jsonPath("$.items[0].candidates[0].tmdbId").value(11))
            .andExpect(jsonPath("$.items[0].candidates[0].runtimeMinutes").value(95))
            .andExpect(jsonPath("$.items[0].candidates[1].year").value(2010))
            .andExpect(jsonPath("$.items[1].kind").value("protected"))
            .andExpect(jsonPath("$.items[1].files[0]").value("Drm Film.m4v"))
            .andExpect(jsonPath("$.items[2].kind").value("needs-conversion"))
            .andExpect(jsonPath("$.items[2].files[0]").value("Old.avi"))
            .andExpect(jsonPath("$.items[3].kind").value("unreadable"))
            .andExpect(jsonPath("$.items[3].files[0]").value("Corrupt.m4v"));
        Film drm = films.findAll().stream().filter(f -> f.getTitle().equals("Drm Film")).findFirst().orElseThrow();
        mvc.perform(get("/api/editions/{id}/stream", drm.getEditions().get(0).getId()))
            .andExpect(status().isNotFound());
        assertThat(drm.getEditions().get(0).originalFile().getPlayability()).isEqualTo(Playability.PROTECTED);
    }

    @Test
    void aSymlinkOutOfTheLibraryIsRefusedAndReportedNeverAdded() throws Exception {
        Path outside = Files.createTempFile("cinefilm-secret", ".m4v");
        Path link = library.resolve("Escape.m4v");
        try {
            Files.createSymbolicLink(link, outside);
            film("Heat.m4v", "0123456789", T1);

            mvc.perform(post("/api/admin/scan"))
                .andExpect(jsonPath("$.lastScan.filesSeen").value(1))
                .andExpect(jsonPath("$.lastScan.error").value("1 files outside the library folder were refused"));

            assertThat(films.count()).isEqualTo(1);
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void anEmbeddedSubtitleStreamIsOnTheFilmDetailForThePlayersTrackElement() throws Exception {
        film("Subbed Film.m4v", "frames", T1);
        scan();
        Film film = films.findAll().get(0);
        Long fileId = film.getEditions().get(0).originalFile().getId();

        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(jsonPath("$.editions[0].file.id").value(fileId))
            .andExpect(jsonPath("$.editions[0].subtitleStreams[0].index").value(2))
            .andExpect(jsonPath("$.editions[0].subtitleStreams[0].language").value("English"))
            .andExpect(jsonPath("$.editions[0].subtitleStreams[0].format").value("mov_text"));
    }

    @Test
    void aScanWhileAnotherRunsIsRefusedWith409AndTheStatusShowsItRunning() throws Exception {
        film("Heat.m4v", "0123456789", T1);
        doAnswer(invocation -> {
            queued.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());

        mvc.perform(post("/api/admin/scan")).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.progress.processed").value(0))
            .andExpect(jsonPath("$.progress.total").value(0));
        mvc.perform(post("/api/admin/scan")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is already running"));
        mvc.perform(get("/api/admin/scan")).andExpect(jsonPath("$.running").value(true))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"));

        queued.forEach(Runnable::run);

        mvc.perform(get("/api/admin/scan")).andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.lastScan.newFiles").value(1));
    }

    @Test
    void anUnreadableLibraryFolderIsReportedInTheLastScan() throws Exception {
        FileSystemUtils.deleteRecursively(library);
        try {
            mvc.perform(post("/api/admin/scan")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.lastScan.error").value("Cannot read the library folder"));
        } finally {
            Files.createDirectories(library);
        }
    }

    @Test
    void beforeAnyScanThereIsNoLastScanAndZeroCounts() throws Exception {
        mvc.perform(get("/api/admin/scan"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.lastScan").doesNotExist())
            .andExpect(jsonPath("$.counts.films").value(0))
            .andExpect(jsonPath("$.counts.newFiles").value(0));
        mvc.perform(get("/api/admin/attention")).andExpect(jsonPath("$.items.length()").value(0));
    }
}
