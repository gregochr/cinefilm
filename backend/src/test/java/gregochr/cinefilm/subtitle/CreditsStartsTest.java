package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Where the credits start on an edition's timeline, from the detection of its last part. */
@ExtendWith(MockitoExtension.class)
class CreditsStartsTest {

    @Mock
    private CreditsDetectionRepository credits;

    private Edition edition;
    private MediaFile last;

    @BeforeEach
    void setUp() {
        edition = edition(film(1L, "Taken", 2008, NOW), 40L, "Original", 90 * MIN, null);
        last = file(edition, "Taken.m4v");
        last.setId(10L);
    }

    private static CreditsDetection detection(CreditsVerdict verdict, Long offsetMs) {
        CreditsDetection row = new CreditsDetection();
        row.setVerdict(verdict);
        row.setOffsetMs(offsetMs);
        return row;
    }

    @Test
    void aFoundDetectionOnASingleFileIsTheOffsetItself() {
        when(credits.findById(10L)).thenReturn(Optional.of(detection(CreditsVerdict.FOUND, 80 * MIN)));

        assertThat(CreditsStarts.of(edition, credits)).isEqualTo(80 * MIN);
    }

    @Test
    void noDetectionOrOneThatFoundNothingOrHasNoOffsetGivesNull() {
        when(credits.findById(10L)).thenReturn(Optional.empty(),
            Optional.of(detection(CreditsVerdict.UNSURE, 80 * MIN)),
            Optional.of(detection(CreditsVerdict.FOUND, null)));

        assertThat(CreditsStarts.of(edition, credits)).isNull();
        assertThat(CreditsStarts.of(edition, credits)).isNull();
        assertThat(CreditsStarts.of(edition, credits)).isNull();
    }

    @Test
    void onASplitEditionTheLastPartsOffsetIsPlacedAfterTheEarlierParts() {
        first(30 * MIN);
        last.setPartNumber(2);
        last.setDurationMs(60 * MIN);
        when(credits.findById(10L)).thenReturn(Optional.of(detection(CreditsVerdict.FOUND, 50 * MIN)));

        assertThat(CreditsStarts.of(edition, credits)).isEqualTo(80 * MIN);
    }

    @Test
    void onASplitEditionWithAPartOfUnknownLengthTheCreditsStartCannotBePlaced() {
        first(30 * MIN);
        last.setPartNumber(2);
        last.setDurationMs(null);
        when(credits.findById(10L)).thenReturn(Optional.of(detection(CreditsVerdict.FOUND, 50 * MIN)));

        assertThat(CreditsStarts.of(edition, credits)).isNull();
    }

    @Test
    void theCreditsStartsOfSeveralEditionsAreReadInOneQuery() {
        Edition other = edition(film(2L, "Alien", 1979, NOW), 41L, "Original", 90 * MIN, null);
        MediaFile otherFile = file(other, "Alien.m4v");
        otherFile.setId(11L);
        CreditsDetection found = detection(CreditsVerdict.FOUND, 80 * MIN);
        found.setMediaFileId(10L);
        when(credits.findAllById(List.of(10L, 11L))).thenReturn(List.of(found));

        Map<Long, Long> starts = CreditsStarts.ofAll(List.of(edition, other), credits);

        assertThat(starts).containsExactly(Map.entry(40L, 80 * MIN));
    }

    private void first(long durationMs) {
        MediaFile first = file(edition, "Taken1.m4v");
        first.setId(9L);
        first.setPartNumber(1);
        first.setDurationMs(durationMs);
    }
}
