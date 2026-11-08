package gregochr.cinefilm;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

/** Checks that {@link CineFilmApplication#main} hands its own class and the arguments to Spring Boot. */
class CineFilmApplicationTest {

    @Test
    void mainRunsTheSpringApplicationForCineFilmWithTheGivenArguments() {
        String[] args = {"--spring.profiles.active=dev"};

        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            CineFilmApplication.main(args);

            spring.verify(() -> SpringApplication.run(CineFilmApplication.class, args));
        }
    }
}
