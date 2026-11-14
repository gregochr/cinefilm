package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.NOW;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.FieldError;
import gregochr.cinefilm.config.FieldErrorsException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.library.EditionDtos.ArrangedEdition;
import gregochr.cinefilm.library.EditionDtos.ArrangedFile;
import gregochr.cinefilm.library.EditionDtos.EditionFileDto;
import gregochr.cinefilm.library.EditionDtos.EditionRefDto;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.EditionsView;
import gregochr.cinefilm.library.EditionDtos.FileRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import gregochr.cinefilm.library.EditionDtos.SaveResult;
import gregochr.cinefilm.library.EditionDtos.SuggestionDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EditEditionsController.class)
@Import(SecurityConfig.class)
class EditEditionsControllerTest {

    private static final String BODY = "{\"editions\":[{\"id\":5,\"name\":\"Extended Edition\",\"default\":true,"
        + "\"files\":[{\"fileId\":1,\"part\":1},{\"fileId\":2,\"part\":2}]},{\"id\":null,\"name\":\"Theatrical\","
        + "\"default\":false,\"files\":[{\"fileId\":3,\"part\":null}]}],\"notThisFilm\":[4]}";

    private static final Instant MODIFIED = NOW.minus(Duration.ofDays(30));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EditionArranger arranger;

    @Test
    void theViewIsReturnedWithItsFieldNames() throws Exception {
        when(arranger.view(7L)).thenReturn(new EditionsView(
            List.of(new EditionFileDto(1L, "Heat.m4v", 6_000_000L, 1920, 800, "HEVC", 9_000_000_000L, "MISSING", 5L,
                2, MODIFIED),
                new EditionFileDto(2L, "Heat-copy.m4v", null, null, null, null, null, "PRESENT", 5L, 1, null)),
            List.of(new EditionRefDto(5L, "Original", true)),
            new SuggestionDto("Runtimes match", List.of(new ArrangedEdition(5L, "Original",
                List.of(new ArrangedFile(1L, null)))), List.of(9L), "Original")));

        mvc.perform(get("/api/admin/films/7/editions").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files[0].id").value(1))
            .andExpect(jsonPath("$.files[0].fileName").value("Heat.m4v"))
            .andExpect(jsonPath("$.files[0].durationMs").value(6_000_000))
            .andExpect(jsonPath("$.files[0].width").value(1920))
            .andExpect(jsonPath("$.files[0].height").value(800))
            .andExpect(jsonPath("$.files[0].videoCodec").value("HEVC"))
            .andExpect(jsonPath("$.files[0].sizeBytes").value(9_000_000_000L))
            .andExpect(jsonPath("$.files[0].presence").value("MISSING"))
            .andExpect(jsonPath("$.files[0].editionId").value(5))
            .andExpect(jsonPath("$.files[0].partNumber").value(2))
            .andExpect(jsonPath("$.files[0].modifiedAt").value(MODIFIED.toString()))
            .andExpect(jsonPath("$.files[1].modifiedAt").value(nullValue()))
            .andExpect(jsonPath("$.editions[0].id").value(5))
            .andExpect(jsonPath("$.editions[0].name").value("Original"))
            .andExpect(jsonPath("$.editions[0].default").value(true))
            .andExpect(jsonPath("$.suggestion.reason").value("Runtimes match"))
            .andExpect(jsonPath("$.suggestion.editions[0].id").value(5))
            .andExpect(jsonPath("$.suggestion.editions[0].files[0].fileId").value(1))
            .andExpect(jsonPath("$.suggestion.editions[0].files[0].part").doesNotExist())
            .andExpect(jsonPath("$.suggestion.notThisFilm[0]").value(9))
            .andExpect(jsonPath("$.suggestion.defaultName").value("Original"));
    }

    @Test
    void anUnknownFilmIs404() throws Exception {
        when(arranger.view(7L)).thenThrow(new NotFoundException("No film 7"));

        mvc.perform(get("/api/admin/films/7/editions").with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No film 7"));
    }

    @Test
    void saveReadsTheBodyIncludingTheDefaultFlagAndReturnsTheNewFilms() throws Exception {
        SaveRequest expected = new SaveRequest(List.of(
            new EditionRequest(5L, "Extended Edition", true,
                List.of(new FileRequest(1L, 1), new FileRequest(2L, 2))),
            new EditionRequest(null, "Theatrical", false, List.of(new FileRequest(3L, null)))), List.of(4L));
        when(arranger.save(7L, expected)).thenReturn(new SaveResult(7L, List.of(31L)));

        mvc.perform(put("/api/admin/films/7/editions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(7))
            .andExpect(jsonPath("$.newFilmIds[0]").value(31));
        verify(arranger).save(7L, expected);
    }

    @Test
    void aBrokenArrangementIs400WithTheFirstMessageAndTheFieldErrors() throws Exception {
        when(arranger.save(7L, new SaveRequest(List.of(), List.of()))).thenThrow(new FieldErrorsException(List.of(
            new FieldError("editions", "Keep at least one file with this film."),
            new FieldError("default", "Choose a default edition."))));

        mvc.perform(put("/api/admin/films/7/editions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"editions\":[],\"notThisFilm\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Keep at least one file with this film."))
            .andExpect(jsonPath("$.errors[0].field").value("editions"))
            .andExpect(jsonPath("$.errors[0].message").value("Keep at least one file with this film."))
            .andExpect(jsonPath("$.errors[1].field").value("default"))
            .andExpect(jsonPath("$.errors[1].message").value("Choose a default edition."));
    }

    @Test
    void aScanInTheWayIs409() throws Exception {
        when(arranger.save(7L, new SaveRequest(List.of(), List.of()))).thenThrow(
            new ConflictException("A scan is running"));

        mvc.perform(put("/api/admin/films/7/editions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"editions\":[],\"notThisFilm\":[]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running"));
    }

    @Test
    void aMalformedBodyIs400() throws Exception {
        mvc.perform(put("/api/admin/films/7/editions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"editions\":\"nope\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIs401AndASaveWithoutTheHeaderIs403() throws Exception {
        mvc.perform(get("/api/admin/films/7/editions")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(put("/api/admin/films/7/editions").header("X-CineFilm", "1").contentType("application/json")
                .content(BODY))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/films/7/editions").with(user("owner")).contentType("application/json")
                .content(BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
