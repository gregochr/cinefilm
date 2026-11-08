package gregochr.cinefilm.person;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Film;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PersonApiTest extends ApiTestBase {

    private Film add(String title, int year) {
        Film film = film(null, title, year, Instant.now());
        edition(film, null, "Theatrical Cut", 100 * MIN, null);
        return film;
    }

    @Test
    void listsOnlyOwnedFilmsWithRolesNewestFirst() throws Exception {
        Person deNiro = people.save(new Person("Robert De Niro"));
        deNiro.setProfilePath("/dn.jpg");
        Person mann = people.save(new Person("Michael Mann"));
        Film heat = add("Heat", 1995);
        credit(heat, mann, CreditRole.DIRECTOR, null, 0);
        credit(heat, deNiro, CreditRole.CAST, "Neil McCauley", 1);
        Film taxi = add("Taxi Driver", 1976);
        credit(taxi, deNiro, CreditRole.CAST, "Travis Bickle", 1);
        Film unrelated = add("Alien", 1979);
        credit(unrelated, mann, CreditRole.DIRECTOR, null, 0);
        save(heat);
        save(taxi);
        save(unrelated);

        mvc.perform(get("/api/people/{id}", deNiro.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(deNiro.getId()))
            .andExpect(jsonPath("$.name").value("Robert De Niro"))
            .andExpect(jsonPath("$.profilePath").value("/dn.jpg"))
            .andExpect(jsonPath("$.films", hasSize(2)))
            .andExpect(jsonPath("$.films[*].film.title", contains("Heat", "Taxi Driver")))
            .andExpect(jsonPath("$.films[0].film.year").value(1995))
            .andExpect(jsonPath("$.films[0].roles", contains("as Neil McCauley")))
            .andExpect(jsonPath("$.films[1].roles", contains("as Travis Bickle")));

        mvc.perform(get("/api/people/{id}", mann.getId()))
            .andExpect(jsonPath("$.films[*].film.title", contains("Heat", "Alien")))
            .andExpect(jsonPath("$.films[0].roles", contains("Director")));
    }

    @Test
    void aPersonWhoDirectedAndActedGetsBothRoles() throws Exception {
        Person welles = people.save(new Person("Orson Welles"));
        Film film = add("Citizen Kane", 1941);
        credit(film, welles, CreditRole.DIRECTOR, null, 0);
        credit(film, welles, CreditRole.CAST, "Charles Foster Kane", 1);
        credit(film, welles, CreditRole.CAST, null, 2);
        save(film);

        mvc.perform(get("/api/people/{id}", welles.getId()))
            .andExpect(jsonPath("$.films", hasSize(1)))
            .andExpect(jsonPath("$.films[0].roles", contains("Director", "as Charles Foster Kane", "Actor")));
    }

    @Test
    void aPersonWithNoFilmsHasAnEmptyList() throws Exception {
        Person nobody = people.save(new Person("Nobody"));
        mvc.perform(get("/api/people/{id}", nobody.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films", hasSize(0)));
    }

    @Test
    void unknownPersonIs404() throws Exception {
        mvc.perform(get("/api/people/{id}", 424242))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").exists());
    }
}
