package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.transcode.MediaKeyframes;
import gregochr.cinefilm.transcode.MediaKeyframesRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FilmRatesTest {

    private static final long EDITION_ID = 40L;
    private static final long SIZE = 1_000L;

    @Mock
    private EditionRepository editions;
    @Mock
    private MediaFileRepository files;
    @Mock
    private MediaKeyframesRepository keyframes;

    private FilmRates rates;
    private Edition edition;

    @BeforeEach
    void setUp() {
        rates = new FilmRates(editions, files, keyframes);
        edition = TestData.edition(TestData.film(1L, "Taken", 2008, NOW), EDITION_ID, "Original", 0, null);
    }

    private MediaFile part(long id, int number, Long size) {
        MediaFile file = TestData.file(edition, "part" + id + ".m4v");
        file.setId(id);
        file.setPartNumber(number);
        file.setSizeBytes(size);
        return file;
    }

    private static MediaKeyframes index(long fileId, long size, Double fps) {
        MediaKeyframes index = new MediaKeyframes();
        index.setMediaFileId(fileId);
        index.setSourceSize(size);
        index.setFps(fps);
        return index;
    }

    private void editionIs() {
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID))).thenReturn(List.of(edition));
    }

    @Test
    void aSingleFileTakesTheRateOfItsIndexWhileItsSizeMatches() {
        part(1, 1, SIZE);
        editionIs();
        when(keyframes.findAllById(List.of(1L))).thenReturn(List.of(index(1, SIZE, 25.0)));

        assertThat(rates.forEdition(EDITION_ID)).isEqualTo(25.0);
    }

    @Test
    void anIndexMadeForAnotherSizeOrWithNoRateGivesNone() {
        part(1, 1, SIZE);
        editionIs();
        when(keyframes.findAllById(List.of(1L))).thenReturn(List.of(index(1, SIZE + 1, 25.0)),
            List.of(index(1, SIZE, null)), List.of());

        assertThat(rates.forEdition(EDITION_ID)).isNull();
        assertThat(rates.forEdition(EDITION_ID)).isNull();
        assertThat(rates.forEdition(EDITION_ID)).isNull();
    }

    @Test
    void aFileWithNoKnownSizeGivesNone() {
        part(1, 1, null);
        editionIs();
        when(keyframes.findAllById(List.of(1L))).thenReturn(List.of(index(1, SIZE, 25.0)));

        assertThat(rates.forEdition(EDITION_ID)).isNull();
    }

    @Test
    void aSplitEditionHasARateOnlyWhenEveryPartHasTheSameCurrentOne() {
        part(1, 1, SIZE);
        part(2, 2, SIZE);
        editionIs();
        when(keyframes.findAllById(List.of(1L, 2L))).thenReturn(
            List.of(index(1, SIZE, 23.976), index(2, SIZE, 23.976)),
            List.of(index(1, SIZE, 23.976), index(2, SIZE, 25.0)),
            List.of(index(1, SIZE, 23.976)));

        assertThat(rates.forEdition(EDITION_ID)).isEqualTo(23.976);
        assertThat(rates.forEdition(EDITION_ID)).isNull();
        assertThat(rates.forEdition(EDITION_ID)).isNull();
    }

    @Test
    void anUnknownEditionOrOneWithNoPartsGivesNone() {
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID))).thenReturn(List.of(), List.of(edition));

        assertThat(rates.forEdition(EDITION_ID)).isNull();
        assertThat(rates.forEdition(EDITION_ID)).isNull();
    }

    @Test
    void aSingleFilesRateIsReadFromItsOwnIndex() {
        MediaFile file = part(1, 1, SIZE);
        when(files.findById(1L)).thenReturn(Optional.of(file));
        when(keyframes.findById(1L)).thenReturn(Optional.of(index(1, SIZE, 24.0)));

        assertThat(rates.forFile(1L)).isEqualTo(24.0);
    }

    @Test
    void severalEditionsAreReadInOneEditionQueryAndOneIndexQuery() {
        part(1, 1, SIZE);
        Edition other = TestData.edition(TestData.film(2L, "Alien", 1979, NOW), 41L, "Original", 0, null);
        MediaFile otherPart = TestData.file(other, "alien.m4v");
        otherPart.setId(2L);
        otherPart.setPartNumber(1);
        otherPart.setSizeBytes(SIZE);
        Edition unrated = TestData.edition(TestData.film(3L, "Heat", 1995, NOW), 42L, "Original", 0, null);
        MediaFile unratedPart = TestData.file(unrated, "heat.m4v");
        unratedPart.setId(3L);
        unratedPart.setPartNumber(1);
        unratedPart.setSizeBytes(SIZE);
        when(editions.findWithFilesByIdIn(Set.of(EDITION_ID, 41L, 42L))).thenReturn(List.of(edition, other, unrated));
        when(keyframes.findAllById(List.of(1L, 2L, 3L))).thenReturn(
            List.of(index(1, SIZE, 25.0), index(2, SIZE, 23.976), index(3, SIZE, null)));

        Map<Long, Double> found = rates.forEditions(List.of(EDITION_ID, 41L, 42L));

        assertThat(found).containsExactlyInAnyOrderEntriesOf(Map.of(EDITION_ID, 25.0, 41L, 23.976));
    }

    @Test
    void noEditionsAreReadWithoutAnyQuery() {
        assertThat(rates.forEditions(List.of())).isEmpty();

        verifyNoInteractions(editions, keyframes);
    }

    @Test
    void aMissingFileGivesNone() {
        when(files.findById(9L)).thenReturn(Optional.empty());

        assertThat(rates.forFile(9L)).isNull();
    }
}
