package gregochr.cinefilm;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.CreditRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/** Base for MockMvc tests: in-memory H2, rolled back after each test, signed in as the owner by default. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class ApiTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected FilmRepository films;

    @Autowired
    protected PersonRepository people;

    @Autowired
    protected FilmCollectionRepository collections;

    @Autowired
    protected CreditRepository credits;

    @Autowired
    protected PlaybackStateRepository states;

    @Autowired
    protected FilmPreferenceRepository preferences;

    protected static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.with(user("owner").roles("OWNER")).header("X-CineFilm", "1");
    }

    protected static MockHttpServletRequestBuilder get(String url, Object... vars) {
        return authed(MockMvcRequestBuilders.get(url, vars));
    }

    protected static MockHttpServletRequestBuilder put(String url, Object... vars) {
        return authed(MockMvcRequestBuilders.put(url, vars)).contentType("application/json");
    }

    protected static MockHttpServletRequestBuilder post(String url, Object... vars) {
        return authed(MockMvcRequestBuilders.post(url, vars)).contentType("application/json");
    }

    protected Film save(Film film) {
        return films.save(film);
    }

    protected PlaybackState saveState(PlaybackState state) {
        return states.save(state);
    }
}
