package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.library.LibraryLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Which films need forced subtitles, end to end: the real queries, the rule, the lock and the JSON. */
class ForcedNeedApiTest extends SubtitleTestBase {

    @Autowired
    private LibraryLock lock;

    private Film withLanguages(String title, String original, String spoken) {
        Film film = filmWithParts(title, 1, 90);
        film.setTmdbId(film.getId() + 1000);
        film.setOriginalLanguage(original);
        film.setSpokenLanguages(spoken);
        film.setLanguagesFetchedAt(NOW);
        return films.saveAndFlush(film);
    }

    @Test
    void theListWorksEachFilmsNeedFromItsFilesAndLanguagesAndCountsThem() throws Exception {
        Film attached = withLanguages("A Attached", "en", "en");
        sidecar("A Attached.en.forced.srt", fileId(attached, 1), false);
        withLanguages("B English", "en", "en");
        withLanguages("C Mixed", "en", "en,fr");
        withLanguages("D Foreign", "fr", "fr");
        filmWithParts("E Not asked", 1, 90);
        Film chosen = withLanguages("F Chosen", "en", "en");
        chosen.setForcedNeedOwner(ForcedNeedChoice.NEEDED);
        films.saveAndFlush(chosen);

        mvc.perform(get("/api/admin/forced-subtitles/films"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films.length()").value(6))
            .andExpect(jsonPath("$.films[0].title").value("A Attached"))
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("SIDECAR"))
            .andExpect(jsonPath("$.films[0].hasForcedSubtitle").value(true))
            .andExpect(jsonPath("$.films[0].ownerChoice").value((Object) null))
            .andExpect(jsonPath("$.films[1].need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.films[1].reason").value("ENGLISH_ONLY"))
            .andExpect(jsonPath("$.films[1].originalLanguage").value("en"))
            .andExpect(jsonPath("$.films[1].spokenLanguages[0]").value("en"))
            .andExpect(jsonPath("$.films[2].need").value("UNSURE"))
            .andExpect(jsonPath("$.films[2].reason").value("OTHER_LANGUAGES"))
            .andExpect(jsonPath("$.films[2].spokenLanguages[1]").value("fr"))
            .andExpect(jsonPath("$.films[3].need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.films[3].reason").value("FOREIGN_LANGUAGE_FILM"))
            .andExpect(jsonPath("$.films[4].need").value("UNKNOWN"))
            .andExpect(jsonPath("$.films[4].reason").value("NO_LANGUAGES"))
            .andExpect(jsonPath("$.films[5].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[5].reason").value("OWNER"))
            .andExpect(jsonPath("$.films[5].ownerChoice").value("NEEDED"))
            .andExpect(jsonPath("$.counts.needed").value(2))
            .andExpect(jsonPath("$.counts.neededMissing").value(1))
            .andExpect(jsonPath("$.counts.unsure").value(1))
            .andExpect(jsonPath("$.counts.unknown").value(1))
            .andExpect(jsonPath("$.counts.notNeeded").value(2));
    }

    @Test
    void anOpenSubtitlesSearchOfTheFilmsCurrentImdbIdMakesItNeededAndIsShownInTheList() throws Exception {
        Film searched = withLanguages("A Searched", "en", "en");
        searched.setImdbId("tt0936501");
        films.saveAndFlush(searched);
        films.saveOpenSubtitles(searched.getId(), "tt0936501", 7, "{\"id\":1}", "[]", NOW);
        Film empty = withLanguages("B Empty", "en", "en");
        empty.setImdbId("tt0000002");
        films.saveAndFlush(empty);
        films.saveOpenSubtitles(empty.getId(), "tt0000002", 0, null, "[]", NOW);
        withLanguages("C Never", "en", "en");

        mvc.perform(get("/api/admin/forced-subtitles/films"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("OPENSUBTITLES"))
            .andExpect(jsonPath("$.films[0].openSubtitlesForcedCount").value(7))
            .andExpect(jsonPath("$.films[0].openSubtitlesSearchedAt").value("2024-03-31T23:30:00Z"))
            .andExpect(jsonPath("$.films[1].need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.films[1].openSubtitlesForcedCount").value(0))
            .andExpect(jsonPath("$.films[2].openSubtitlesForcedCount").value((Object) null))
            .andExpect(jsonPath("$.films[2].openSubtitlesSearchedAt").value((Object) null))
            .andExpect(jsonPath("$.counts.needed").value(1));
    }

    @Test
    void theOwnersChoiceIsSavedAnsweredAndShownInTheList() throws Exception {
        Film film = withLanguages("Taken", "en", "en");

        mvc.perform(put("/api/admin/films/{id}/forced-need", film.getId()).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(film.getId()))
            .andExpect(jsonPath("$.need").value("NEEDED"))
            .andExpect(jsonPath("$.reason").value("OWNER"))
            .andExpect(jsonPath("$.ownerChoice").value("NEEDED"));
        assertThat(films.findById(film.getId()).orElseThrow().getForcedNeedOwner())
            .isEqualTo(ForcedNeedChoice.NEEDED);
        mvc.perform(get("/api/admin/forced-subtitles/films"))
            .andExpect(jsonPath("$.films[0].ownerChoice").value("NEEDED"));

        mvc.perform(put("/api/admin/films/{id}/forced-need", film.getId()).content("{\"choice\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.reason").value("ENGLISH_ONLY"))
            .andExpect(jsonPath("$.ownerChoice").value((Object) null));
        assertThat(films.findById(film.getId()).orElseThrow().getForcedNeedOwner()).isNull();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void theAnsweredRowSaysWhetherAFileIsAttached() throws Exception {
        Film film = withLanguages("Taken", "en", "en");
        sidecar("Taken.en.forced.srt", fileId(film, 1), false);

        mvc.perform(put("/api/admin/films/{id}/forced-need", film.getId()).content("{\"choice\":\"NOT_NEEDED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.hasForcedSubtitle").value(true));
    }

    private Film zeroCueOnly() {
        Film film = withLanguages("Zero cues", "en", "en");
        sidecar("Zero cues.en.forced.srt", fileId(film, 1), false).setCueCount(0);
        return film;
    }

    private Film conflicting() {
        Film film = withLanguages("Conflict", "en", "en");
        sidecar("Conflict.en.forced.srt", fileId(film, 1), false);
        sidecar("Conflict.forced.en.srt", fileId(film, 1), false);
        return film;
    }

    private Film normal() {
        Film film = withLanguages("Normal", "en", "en");
        sidecar("Normal.en.forced.srt", fileId(film, 1), false);
        return film;
    }

    @Test
    void aFileWithNoCuesIsNoEvidenceAndAConflictIsEvidenceThatPlaybackDoesNotServe() throws Exception {
        normal();
        conflicting();
        zeroCueOnly();

        mvc.perform(get("/api/admin/forced-subtitles/films"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films[0].title").value("Conflict"))
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("SIDECAR"))
            .andExpect(jsonPath("$.films[0].hasForcedSubtitle").value(false))
            .andExpect(jsonPath("$.films[1].title").value("Normal"))
            .andExpect(jsonPath("$.films[1].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[1].hasForcedSubtitle").value(true))
            .andExpect(jsonPath("$.films[2].title").value("Zero cues"))
            .andExpect(jsonPath("$.films[2].need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.films[2].reason").value("ENGLISH_ONLY"))
            .andExpect(jsonPath("$.films[2].hasForcedSubtitle").value(false))
            .andExpect(jsonPath("$.counts.needed").value(2))
            .andExpect(jsonPath("$.counts.neededMissing").value(1))
            .andExpect(jsonPath("$.counts.notNeeded").value(1));
    }

    @Test
    void theSameThreeCasesThroughThePutResponse() throws Exception {
        Film normal = normal();
        Film conflict = conflicting();
        Film zero = zeroCueOnly();

        mvc.perform(put("/api/admin/films/{id}/forced-need", normal.getId()).content("{\"choice\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.need").value("NEEDED"))
            .andExpect(jsonPath("$.reason").value("SIDECAR"))
            .andExpect(jsonPath("$.hasForcedSubtitle").value(true));
        mvc.perform(put("/api/admin/films/{id}/forced-need", conflict.getId()).content("{\"choice\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.need").value("NEEDED"))
            .andExpect(jsonPath("$.reason").value("SIDECAR"))
            .andExpect(jsonPath("$.hasForcedSubtitle").value(false));
        mvc.perform(put("/api/admin/films/{id}/forced-need", zero.getId()).content("{\"choice\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.reason").value("ENGLISH_ONLY"))
            .andExpect(jsonPath("$.hasForcedSubtitle").value(false));
    }

    @Test
    void anUnknownFilmIs404AnotherValueIs400AndABusyLibraryIs409() throws Exception {
        Film film = withLanguages("Taken", "en", "en");

        mvc.perform(put("/api/admin/films/987654/forced-need").content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such film"));
        mvc.perform(put("/api/admin/films/{id}/forced-need", film.getId()).content("{\"choice\":\"MAYBE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The choice must be NEEDED, NOT_NEEDED or null"));
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            mvc.perform(put("/api/admin/films/{id}/forced-need", film.getId()).content("{\"choice\":\"NEEDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A scan is running"));
            assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        } finally {
            lock.release();
        }
        assertThat(films.findById(film.getId()).orElseThrow().getForcedNeedOwner()).isNull();
    }

    @Test
    void signedOutIs401AndAChangeWithoutTheHeaderIs403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/admin/forced-subtitles/films"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(MockMvcRequestBuilders.put("/api/admin/films/1/forced-need").header("X-CineFilm", "1")
                .contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(MockMvcRequestBuilders.put("/api/admin/films/1/forced-need").with(user("owner").roles("OWNER"))
                .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
