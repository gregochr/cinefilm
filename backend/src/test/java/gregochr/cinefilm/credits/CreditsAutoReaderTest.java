package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.credits.CreditsAutoTestData.crawl;
import static gregochr.cinefilm.credits.CreditsAutoTestData.denyFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsApply.AutoInputs;
import gregochr.cinefilm.credits.CreditsApply.Detection;
import gregochr.cinefilm.credits.CreditsApply.UnitInputs;
import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.EditionFilmRow;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.UnitRow;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** What step 3 reads: when it reads nothing, what it maps, and the chunks of listed films. */
@ExtendWith(MockitoExtension.class)
class CreditsAutoReaderTest {

    private static final String FINGERPRINT = "4000000000:" + NOW.toEpochMilli();
    private static final DenyList LIST = denyFile("file 999").list();

    @Mock
    private EditionRepository editions;
    @Mock
    private FilmRepository films;
    @Mock
    private CreditsDetectionRepository detections;

    private CreditsAutoReader reader(boolean enabled, boolean cards) {
        return new CreditsAutoReader(editions, films, detections, enabled, cards);
    }

    /** An edition (id) of a film (id + 100) with one file (id). */
    private static Edition anEdition(long id) {
        Edition edition = edition(film(id + 100, "Film", 2000, NOW), id, "Original", 100 * MIN, null);
        file(edition, "film.m4v").setId(id);
        return edition;
    }

    @Test
    void withTheSwitchOffNothingIsReadAndNoEditionHasInputs() {
        CreditsAutoReader reader = reader(false, true);
        Edition edition = anEdition(1);

        assertThat(reader.enabled()).isFalse();
        assertThat(reader.active(LIST)).isFalse();
        assertThat(reader.listedUnitKeys(LIST)).isEmpty();
        assertThat(reader.lookup(List.of(edition), LIST, Map.of()).inputs(edition)).isNull();
        verifyNoInteractions(editions, films, detections);
    }

    @Test
    void withNoDenyListNothingIsReadAndNoEditionHasInputs() {
        CreditsAutoReader reader = reader(true, true);
        Edition edition = anEdition(1);

        assertThat(reader.enabled()).isTrue();
        assertThat(reader.active(null)).isFalse();
        assertThat(reader.listedUnitKeys(null)).isEmpty();
        assertThat(reader.lookup(List.of(edition), null, Map.of()).inputs(edition)).isNull();
        verifyNoInteractions(editions, films, detections);
    }

    @Test
    void anEditionsInputsAreItsDetectionRowItsFilmsUnitKeyInputsTheListTheSwitchAndTheCardsFlag() {
        Edition edition = anEdition(7);
        CreditsDetection row = crawl(7, FINGERPRINT, 5_000_000);
        when(detections.findAllById(List.of(7L))).thenReturn(List.of(row));
        when(editions.findFilmRowsByIdIn(List.of(7L))).thenReturn(List.of(
            new EditionFilmRow(7L, 107L, 603L, "The Matrix", 1999, 5L, 2344L)));
        Map<Long, String> listed = Map.of(55L, "tmdb:1");

        AutoInputs inputs = reader(true, false).lookup(List.of(edition), LIST, listed).inputs(edition);

        assertThat(inputs).isEqualTo(new AutoInputs(true, false, LIST, 7, 107,
            new UnitInputs(2344L, 5L, 603L, "The Matrix", 1999), listed,
            new Detection(2, CreditsVerdict.FOUND, 5_000_000L, FINGERPRINT, row.getReasons())));
        assertThat(inputs.film().unitKey()).isEqualTo("ctmdb:2344");
    }

    @Test
    void anEditionWithNoDetectionRowHasNoDetection() {
        Edition edition = anEdition(7);
        when(detections.findAllById(List.of(7L))).thenReturn(List.of());
        when(editions.findFilmRowsByIdIn(List.of(7L))).thenReturn(List.of(
            new EditionFilmRow(7L, 107L, 603L, "The Matrix", 1999, null, null)));

        AutoInputs inputs = reader(true, true).lookup(List.of(edition), LIST, Map.of()).inputs(edition);

        assertThat(inputs.detection()).isNull();
    }

    @Test
    void anEditionWithNoOriginalFileHasNoDetectionAndAsksForNoDetectionRows() {
        Edition bare = new Edition();
        bare.setId(8L);
        when(editions.findFilmRowsByIdIn(List.of(8L))).thenReturn(List.of(
            new EditionFilmRow(8L, 108L, 603L, "The Matrix", 1999, null, null)));

        AutoInputs inputs = reader(true, true).lookup(List.of(bare), LIST, Map.of()).inputs(bare);

        assertThat(inputs.detection()).isNull();
        verifyNoInteractions(detections);
    }

    @Test
    void anEditionWhoseFilmRowIsGoneHasNoInputs() {
        Edition edition = anEdition(7);
        when(detections.findAllById(List.of(7L))).thenReturn(List.of());
        when(editions.findFilmRowsByIdIn(List.of(7L))).thenReturn(List.of());

        assertThat(reader(true, true).lookup(List.of(edition), LIST, Map.of()).inputs(edition)).isNull();
    }

    @Test
    void noEditionsMeansNoStatements() {
        assertThat(reader(true, true).lookup(List.of(), LIST, Map.of())).isNotNull();
        verifyNoInteractions(editions, films, detections);
    }

    @Test
    void theCandidateReadsGoAheadWithTheSwitchOffAndTheirInputsSayOn() {
        Edition edition = anEdition(7);
        CreditsDetection row = crawl(7, FINGERPRINT, 5_000_000);
        when(detections.findAllById(List.of(7L))).thenReturn(List.of(row));
        when(editions.findFilmRowsByIdIn(List.of(7L))).thenReturn(List.of(
            new EditionFilmRow(7L, 107L, 603L, "The Matrix", 1999, null, null)));
        when(films.findUnitRowsByIdIn(List.of(999L))).thenReturn(List.of(
            new UnitRow(999L, 604L, "Reloaded", 2003, null, null)));
        CreditsAutoReader reader = reader(false, false);
        DenyList list = denyFile("film 999").list();

        Map<Long, String> listed = reader.candidateListedUnitKeys(list);
        AutoInputs inputs = reader.candidateLookup(List.of(edition), list, listed).inputs(edition);

        assertThat(listed).containsExactlyEntriesOf(Map.of(999L, "tmdb:604"));
        assertThat(inputs.enabled()).isTrue();
        assertThat(inputs.cards()).isFalse();
        assertThat(inputs.detection().offsetMs()).isEqualTo(5_000_000L);
    }

    @Test
    void theCandidateReadsWithNoDenyListReadNothingAndGiveNoInputs() {
        Edition edition = anEdition(7);
        CreditsAutoReader reader = reader(true, true);

        assertThat(reader.candidateListedUnitKeys(null)).isEmpty();
        assertThat(reader.candidateLookup(List.of(edition), null, Map.of()).inputs(edition)).isNull();
        verifyNoInteractions(editions, films, detections);
    }

    @Test
    void aListNamingNoFilmAsksForNoFilm() {
        assertThat(reader(true, true).listedUnitKeys(denyFile("file 1", "unit tmdb:1").list())).isEmpty();
        verifyNoInteractions(films);
    }

    @Test
    void listedFilmsAreAskedForInChunksOfFiveHundredInIdOrderAndKeyedByTheCurrentUnitKey() {
        List<Long> all = LongStream.rangeClosed(1, 501).boxed().toList();
        DenyList list = new DenyList(new HashSet<>(all), Set.of(), Set.of(), Set.of());
        when(films.findUnitRowsByIdIn(all.subList(0, 500))).thenReturn(List.of(
            new UnitRow(1L, 603L, "The Matrix", 1999, null, 77L),
            new UnitRow(2L, 604L, "Reloaded", 2003, null, null)));
        when(films.findUnitRowsByIdIn(List.of(501L))).thenReturn(List.of(
            new UnitRow(501L, null, "The Film", 2001, 9L, null)));

        Map<Long, String> keys = reader(true, true).listedUnitKeys(list);

        assertThat(keys).containsExactlyInAnyOrderEntriesOf(
            Map.of(1L, "ctmdb:77", 2L, "tmdb:604", 501L, "cid:9"));
    }
}
