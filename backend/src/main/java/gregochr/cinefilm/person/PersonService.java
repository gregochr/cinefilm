package gregochr.cinefilm.person;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRepository;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmMapper;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.person.PersonDtos.PersonFilm;
import gregochr.cinefilm.person.PersonDtos.PersonPage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The person page: only films in the library, with the roles played. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final CreditRepository credits;
    private final LibraryLoader loader;

    public PersonService(PersonRepository people, CreditRepository credits, LibraryLoader loader) {
        this.people = people;
        this.credits = credits;
        this.loader = loader;
    }

    /** Builds the person page, newest film first. */
    @Transactional(readOnly = true)
    public PersonPage get(Long id) {
        Person person = people.findById(id).orElseThrow(() -> new NotFoundException("No such person"));
        Map<Film, List<String>> rolesByFilm = new LinkedHashMap<>();
        for (Credit credit : credits.findByPersonId(id)) {
            rolesByFilm.computeIfAbsent(credit.getFilm(), f -> new ArrayList<>()).add(describe(credit));
        }
        List<PersonFilm> films = loader.views(rolesByFilm.keySet()).stream()
            .sorted(Comparator.comparingInt((FilmView v) -> v.film().getYear()).reversed()
                .thenComparing(v -> v.film().getSortTitle()))
            .map(v -> new PersonFilm(FilmMapper.card(v), rolesByFilm.get(v.film())))
            .toList();
        return new PersonPage(person.getId(), person.getName(), person.getProfilePath(), films);
    }

    private static String describe(Credit credit) {
        if (credit.getRole() == CreditRole.DIRECTOR) {
            return "Director";
        }
        String character = credit.getCharacter();
        return character == null || character.isBlank() ? "Actor" : "as " + character;
    }
}
