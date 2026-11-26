package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Retiming as a file arrives: only while the setting is on, by the job's own paths. */
@ExtendWith(MockitoExtension.class)
class RetimeOnArrivalTest {

    @Mock
    private SubtitleRetimeService downloads;
    @Mock
    private ReferenceRetimeService references;

    private RetimeOnArrival arrival(String setting) {
        return new RetimeOnArrival(downloads, references, setting);
    }

    @Test
    void withTheSettingOnADownloadThatArrivesIsRetimedByTheServicesArrivalPath() {
        arrival("on").downloadArrived(5L);

        verify(downloads).process(5L, RetimeMode.ON);
    }

    @Test
    void withTheSettingOnAReferenceThatArrivesIsRetimedAndTheCallerIsToldWhenItWasRewritten() {
        when(references.process(7L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN, Outcome.UNCHANGED, Outcome.BUSY);
        RetimeOnArrival on = arrival("on");

        assertThat(on.referenceArrived(7L)).isTrue();
        assertThat(on.referenceArrived(7L)).isFalse();
        assertThat(on.referenceArrived(7L)).isFalse();
    }

    @Test
    void withTheSettingOffOrRestoreNothingHappensOnArrival() {
        for (String setting : new String[] {"off", "restore", "nonsense"}) {
            RetimeOnArrival other = arrival(setting);

            other.downloadArrived(5L);
            assertThat(other.referenceArrived(7L)).isFalse();
        }

        verifyNoInteractions(downloads, references);
    }

    @Test
    void aFailureOnArrivalIsLoggedAndTheFileIsLeftAsStoredForTheJob() {
        when(downloads.process(5L, RetimeMode.ON)).thenThrow(new IllegalStateException("boom"));
        when(references.process(7L, RetimeMode.ON)).thenThrow(new IllegalStateException("boom"));
        RetimeOnArrival on = arrival("on");

        on.downloadArrived(5L);

        assertThat(on.referenceArrived(7L)).isFalse();
    }
}
