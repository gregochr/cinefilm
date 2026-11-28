package gregochr.cinefilm.journey;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import org.junit.jupiter.api.Test;

class KnownRuntimeTest {

    private static MediaFile part(Edition edition, MediaKind kind, int number, long id, Long durationMs,
                                  Long videoDurationMs) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setEdition(edition);
        file.setKind(kind);
        file.setPartNumber(number);
        file.setDurationMs(durationMs);
        file.setVideoDurationMs(videoDurationMs);
        edition.getMediaFiles().add(file);
        return file;
    }

    private static Edition edition(long storedRuntimeMs) {
        Edition edition = new Edition();
        edition.setRuntimeMs(storedRuntimeMs);
        return edition;
    }

    private static Edition oneFile(Long durationMs, Long videoDurationMs) {
        Edition edition = edition(1);
        part(edition, MediaKind.ORIGINAL, 1, 1L, durationMs, videoDurationMs);
        return edition;
    }

    private static Edition twoParts(Long durationOne, Long videoOne, Long durationTwo, Long videoTwo) {
        Edition edition = edition(1);
        part(edition, MediaKind.ORIGINAL, 1, 1L, durationOne, videoOne);
        part(edition, MediaKind.ORIGINAL, 2, 2L, durationTwo, videoTwo);
        return edition;
    }

    @Test
    void noEditionHasNoKnownRuntime() {
        assertThat(KnownRuntime.knownRuntimeMs(null)).isNull();
    }

    @Test
    void anEditionWithNoOriginalPartIsNull() {
        Edition edition = edition(5_400_000);
        part(edition, MediaKind.PREMADE_720, 1, 1L, 5_400_000L, 5_400_000L);

        assertThat(KnownRuntime.knownRuntimeMs(edition)).isNull();
    }

    @Test
    void oneFileIsItsContainerDurationNotItsVideoDuration() {
        assertThat(KnownRuntime.knownRuntimeMs(oneFile(5_400_000L, 5_300_000L))).isEqualTo(5_400_000L);
    }

    @Test
    void oneFileWithADurationOfZeroOrNullIsNullEvenWithAVideoDuration() {
        assertThat(KnownRuntime.knownRuntimeMs(oneFile(0L, 5_300_000L))).isNull();
        assertThat(KnownRuntime.knownRuntimeMs(oneFile(null, 5_300_000L))).isNull();
    }

    @Test
    void theStoredRuntimeIsNeverRead() {
        Edition edition = oneFile(5_400_000L, null);
        edition.setRuntimeMs(1_000);

        assertThat(KnownRuntime.knownRuntimeMs(edition)).isEqualTo(5_400_000L);
    }

    @Test
    void twoPartsAddTheirVideoDurationsWhereAboveZero() {
        assertThat(KnownRuntime.knownRuntimeMs(twoParts(3_100_000L, 3_000_000L, 2_100_000L, 2_000_000L)))
            .isEqualTo(5_000_000L);
    }

    @Test
    void aPartWithNoDurationButAVideoDurationIsCounted() {
        assertThat(KnownRuntime.knownRuntimeMs(twoParts(null, 3_000_000L, 2_100_000L, 2_000_000L)))
            .isEqualTo(5_000_000L);
    }

    @Test
    void aPartWithAVideoDurationOfZeroIsCountedAtItsDuration() {
        assertThat(KnownRuntime.knownRuntimeMs(twoParts(3_100_000L, 0L, 2_100_000L, 2_000_000L)))
            .isEqualTo(5_100_000L);
    }

    @Test
    void aPartWithBothDurationsNullOrZeroGivesNull() {
        assertThat(KnownRuntime.knownRuntimeMs(twoParts(null, null, 2_100_000L, 2_000_000L))).isNull();
        assertThat(KnownRuntime.knownRuntimeMs(twoParts(0L, 0L, 2_100_000L, 2_000_000L))).isNull();
    }
}
