package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Which films need full subtitles, end to end: the real queries, the rule, the lock and the JSON. */
class FullNeedApiTest extends SubtitleTestBase {

    @Autowired
    private LibraryLock lock;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory factory;

    private Film withLanguages(String title, String original) {
        Film film = filmWithParts(title, 1, 90);
        film.setTmdbId(film.getId() + 1000);
        film.setOriginalLanguage(original);
        film.setSpokenLanguages(original);
        film.setLanguagesFetchedAt(NOW);
        return films.saveAndFlush(film);
    }

    /** Replaces the audio tracks of a film's part: each label is a language, a leading {@code *} marks the default. */
    private void audioOf(Film film, int part, String... labels) {
        MediaFile file = film.getEditions().get(0).originalParts().get(part - 1);
        file.getAudioStreams().clear();
        int index = 0;
        for (String label : labels) {
            AudioStream stream = new AudioStream();
            stream.setMediaFile(file);
            stream.setIndex(index++);
            stream.setDefault(label.startsWith("*"));
            stream.setLanguage(label.replace("*", "").isEmpty() ? null : label.replace("*", ""));
            file.getAudioStreams().add(stream);
        }
        films.saveAndFlush(film);
    }

    @Test
    void theListHoldsTheFilmsNotMadeInEnglishWithTheirNeedFromTheAudio() throws Exception {
        Film foreign = withLanguages("A Foreign", "fr");
        audioOf(foreign, 1, "English", "*French");
        Film dubbed = withLanguages("B Dubbed", "fr");
        audioOf(dubbed, 1, "*English", "French");
        Film unlabelled = withLanguages("C Unlabelled", "ja");
        audioOf(unlabelled, 1, "*");
        withLanguages("D English", "en");
        filmWithParts("E Not asked", 1, 90);
        Film chosen = withLanguages("F Chosen", "en");
        chosen.setFullNeedOwner(FullNeedChoice.NEEDED);
        films.saveAndFlush(chosen);

        mvc.perform(get("/api/admin/subtitles/full-need"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films.length()").value(4))
            .andExpect(jsonPath("$.films[0].title").value("A Foreign"))
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("FOREIGN_AUDIO"))
            .andExpect(jsonPath("$.films[0].ownerChoice").value((Object) null))
            .andExpect(jsonPath("$.films[0].editions.length()").value(1))
            .andExpect(jsonPath("$.films[0].editions[0].name").value("Original"))
            .andExpect(jsonPath("$.films[0].editions[0].audio").value("French"))
            .andExpect(jsonPath("$.films[1].need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.films[1].reason").value("ENGLISH_AUDIO"))
            .andExpect(jsonPath("$.films[1].editions[0].audio").value("English"))
            .andExpect(jsonPath("$.films[2].need").value("UNSURE"))
            .andExpect(jsonPath("$.films[2].reason").value("UNLABELLED_AUDIO"))
            .andExpect(jsonPath("$.films[2].editions[0].audio").value((Object) null))
            .andExpect(jsonPath("$.films[3].title").value("F Chosen"))
            .andExpect(jsonPath("$.films[3].reason").value("OWNER"))
            .andExpect(jsonPath("$.films[3].ownerChoice").value("NEEDED"))
            .andExpect(jsonPath("$.counts.needed").value(2))
            .andExpect(jsonPath("$.counts.unsure").value(1))
            .andExpect(jsonPath("$.counts.notNeeded").value(1))
            .andExpect(jsonPath("$.counts.unknown").value(0));
    }

    @Test
    void aMissingPartOneHidesTheEditionAndOnlyPartOnesAudioCounts() throws Exception {
        Film split = filmWithParts("A Split", 2, 60);
        split.setTmdbId(split.getId() + 1000);
        split.setOriginalLanguage("fr");
        split.setSpokenLanguages("fr");
        split.setLanguagesFetchedAt(NOW);
        audioOf(split, 1, "*English");
        audioOf(split, 2, "*French");
        Film gone = withLanguages("B Gone", "fr");
        audioOf(gone, 1, "*French");
        gone.getEditions().get(0).originalParts().get(0).setPresence(Presence.MISSING);
        films.saveAndFlush(gone);

        mvc.perform(get("/api/admin/subtitles/full-need"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films[0].title").value("A Split"))
            .andExpect(jsonPath("$.films[0].need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.films[0].editions[0].audio").value("English"))
            .andExpect(jsonPath("$.films[1].title").value("B Gone"))
            .andExpect(jsonPath("$.films[1].editions.length()").value(0))
            .andExpect(jsonPath("$.films[1].need").value("UNSURE"));
    }

    @Test
    void theListCostsNoMoreStatementsForThreeFilmsThanForOne() throws Exception {
        Statistics statistics = factory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            audioOf(withLanguages("One", "fr"), 1, "*French");
            long withOne = statementsOfOneList(statistics);

            audioOf(withLanguages("Two", "fr"), 1, "*French");
            audioOf(withLanguages("Three", "de"), 1, "*German", "English");
            long withThree = statementsOfOneList(statistics);

            assertThat(withOne).isPositive();
            assertThat(withThree).isEqualTo(withOne);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private long statementsOfOneList(Statistics statistics) throws Exception {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        mvc.perform(get("/api/admin/subtitles/full-need")).andExpect(status().isOk());
        return statistics.getPrepareStatementCount();
    }

    @Test
    void theOwnersChoiceIsSavedAnsweredAndShownInTheList() throws Exception {
        Film film = withLanguages("Amelie", "fr");
        audioOf(film, 1, "*French");

        mvc.perform(put("/api/admin/films/{id}/full-need", film.getId()).content("{\"choice\":\"NOT_NEEDED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(film.getId()))
            .andExpect(jsonPath("$.need").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.reason").value("OWNER"))
            .andExpect(jsonPath("$.ownerChoice").value("NOT_NEEDED"))
            .andExpect(jsonPath("$.editions[0].audio").value("French"));
        assertThat(films.findById(film.getId()).orElseThrow().getFullNeedOwner())
            .isEqualTo(FullNeedChoice.NOT_NEEDED);
        mvc.perform(get("/api/admin/subtitles/full-need"))
            .andExpect(jsonPath("$.films[0].ownerChoice").value("NOT_NEEDED"));

        mvc.perform(put("/api/admin/films/{id}/full-need", film.getId()).content("{\"choice\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.need").value("NEEDED"))
            .andExpect(jsonPath("$.reason").value("FOREIGN_AUDIO"))
            .andExpect(jsonPath("$.ownerChoice").value((Object) null));
        assertThat(films.findById(film.getId()).orElseThrow().getFullNeedOwner()).isNull();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void theFilmDetailCarriesTheNeed() throws Exception {
        Film film = withLanguages("Amelie", "fr");
        audioOf(film, 1, "*French");
        Film english = withLanguages("Heat", "en");

        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullNeed").value("NEEDED"));
        mvc.perform(get("/api/films/{id}", english.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullNeed").value("NOT_NEEDED"));
    }

    @Test
    void anUnknownFilmIs404AnotherValueIs400AndABusyLibraryIs409() throws Exception {
        Film film = withLanguages("Amelie", "fr");

        mvc.perform(put("/api/admin/films/987654/full-need").content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such film"));
        mvc.perform(put("/api/admin/films/{id}/full-need", film.getId()).content("{\"choice\":\"MAYBE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The choice must be NEEDED, NOT_NEEDED or null"));
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();
        try {
            mvc.perform(put("/api/admin/films/{id}/full-need", film.getId()).content("{\"choice\":\"NEEDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("A scan is running"));
            assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        } finally {
            lock.release();
        }
        assertThat(films.findById(film.getId()).orElseThrow().getFullNeedOwner()).isNull();
    }

    @Test
    void signedOutIs401AndAChangeWithoutTheHeaderIs403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/admin/subtitles/full-need"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(MockMvcRequestBuilders.put("/api/admin/films/1/full-need").header("X-CineFilm", "1")
                .contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(MockMvcRequestBuilders.put("/api/admin/films/1/full-need").with(user("owner").roles("OWNER"))
                .contentType("application/json").content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
