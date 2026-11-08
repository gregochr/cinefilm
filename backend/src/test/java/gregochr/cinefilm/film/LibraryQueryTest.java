package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LibraryQueryTest {

    @Test
    void allMatchesEverythingWithNoFilterNoSortAndNoSearch() {
        LibraryQuery all = LibraryQuery.all();

        assertThat(all.q()).isNull();
        assertThat(all.sort()).isNull();
        assertThat(all.genre()).isNull();
        assertThat(all.decade()).isNull();
        assertThat(all.collection()).isNull();
        assertThat(all.show()).isNull();
    }
}
