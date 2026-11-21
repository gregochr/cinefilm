package gregochr.cinefilm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

/** {@link DevSeed} on its own: mocked repositories, no Spring context. */
class DevSeedUnitTest {

    @Test
    void everySeededMarkerIsTheOwnersAndAnEditionWithNoMarkerHasNoSource() throws Exception {
        FilmRepository films = mock(FilmRepository.class);
        PersonRepository people = mock(PersonRepository.class);
        FilmCollectionRepository collections = mock(FilmCollectionRepository.class);
        when(films.count()).thenReturn(0L);
        when(people.save(any(Person.class))).then(returnsFirstArg());
        when(collections.save(any())).then(returnsFirstArg());
        DevSeed seed = new DevSeed(films, people, collections, mock(PlaybackStateRepository.class),
            Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC), mock(LibraryRoot.class), "");

        seed.run(mock(ApplicationArguments.class));

        ArgumentCaptor<Film> saved = ArgumentCaptor.forClass(Film.class);
        verify(films, atLeastOnce()).save(saved.capture());
        List<Edition> seeded = saved.getAllValues().stream().flatMap(f -> f.getEditions().stream()).toList();
        assertThat(seeded).filteredOn(e -> e.getCreditsMarkerMs() != null).isNotEmpty()
            .allSatisfy(e -> assertThat(e.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.OWNER));
        assertThat(seeded).filteredOn(e -> e.getCreditsMarkerMs() == null).isNotEmpty()
            .allSatisfy(e -> assertThat(e.getCreditsMarkerSource()).isNull());
    }
}
