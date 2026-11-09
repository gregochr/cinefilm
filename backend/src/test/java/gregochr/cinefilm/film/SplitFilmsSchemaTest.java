package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The V8 columns and table round-trip through the real mappings (H2 in PostgreSQL mode, ddl-auto=validate). */
class SplitFilmsSchemaTest extends ApiTestBase {

    @Autowired
    private EntityManager em;

    @Autowired
    private GroupingChangeRepository changes;

    @Test
    void newRowsGetTheMigrationDefaults() {
        Film saved = save(film(null, "Heat", 1995, NOW));
        Edition edition = edition(saved, null, "Original", 100 * MIN, null);
        file(edition, "heat.m4v");
        save(saved);
        preferences.save(new FilmPreference(saved.getId()));
        em.flush();
        em.clear();

        Film loaded = films.findById(saved.getId()).orElseThrow();
        assertThat(loaded.isEditionsConfirmed()).isFalse();
        assertThat(loaded.getEditions().get(0).getGroupingSource()).isEqualTo(GroupingSource.AUTO);
        assertThat(loaded.getEditions().get(0).originalFile().getPartNumber()).isEqualTo(1);
        assertThat(preferences.findById(saved.getId()).orElseThrow().isDefaultEditionChosen()).isFalse();
    }

    @Test
    void theNewFieldsAreStored() {
        Film saved = film(null, "Towers", 2002, NOW);
        saved.setEditionsConfirmed(true);
        Edition edition = edition(saved, null, "Extended", 0, null);
        edition.setGroupingSource(GroupingSource.PLEX);
        MediaFile second = file(edition, "pt2.m4v");
        second.setPartNumber(2);
        save(saved);
        FilmPreference preference = new FilmPreference(saved.getId());
        preference.setDefaultEditionChosen(true);
        preferences.save(preference);
        em.flush();
        em.clear();

        Film loaded = films.findById(saved.getId()).orElseThrow();
        assertThat(loaded.isEditionsConfirmed()).isTrue();
        assertThat(loaded.getEditions().get(0).getGroupingSource()).isEqualTo(GroupingSource.PLEX);
        assertThat(loaded.getEditions().get(0).originalFile().getPartNumber()).isEqualTo(2);
        assertThat(preferences.findById(saved.getId()).orElseThrow().isDefaultEditionChosen()).isTrue();
    }

    @Test
    void groupingChangesAreStoredAndTheNewestTwentyComeBackNewestFirst() {
        for (int i = 0; i < 22; i++) {
            changes.save(new GroupingChange(NOW.plus(Duration.ofMinutes(i)), GroupingChange.JOIN, "change " + i));
        }
        changes.save(new GroupingChange(NOW.plus(Duration.ofMinutes(21)), GroupingChange.FLAG, "tie"));
        em.flush();
        em.clear();

        List<GroupingChange> newest = changes.findTop20ByOrderByHappenedAtDescIdDesc();

        assertThat(newest).hasSize(20);
        assertThat(newest.get(0).getSummary()).isEqualTo("tie");
        assertThat(newest.get(0).getKind()).isEqualTo("flag");
        assertThat(newest.get(0).getHappenedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(21)));
        assertThat(newest.get(1).getSummary()).isEqualTo("change 21");
        assertThat(newest.get(1).getKind()).isEqualTo("join");
        assertThat(newest.get(19).getSummary()).isEqualTo("change 3");
        assertThat(GroupingChange.SPLIT).isEqualTo("split");
    }
}
