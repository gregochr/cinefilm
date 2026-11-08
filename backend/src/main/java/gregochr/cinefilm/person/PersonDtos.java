package gregochr.cinefilm.person;

import gregochr.cinefilm.film.FilmDtos.FilmCard;
import java.util.List;

/** JSON shapes of the person endpoint. */
public final class PersonDtos {

    private PersonDtos() {
    }

    /** One owned film with the roles the person had in it. */
    public record PersonFilm(FilmCard film, List<String> roles) {
    }

    /** The person page. */
    public record PersonPage(Long id, String name, String profilePath, List<PersonFilm> films) {
    }
}
