package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.RateComparison.Kind;
import gregochr.cinefilm.subtitle.RateComparison.Side;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DueCheckTest {

    private static final Side RETIMED = new Side(Kind.DOWNLOAD, 23.976, RetimeChoice.AUTO, 0.959, "original");

    @Test
    void theOwnersFilesAreTheFileSideAndAreNotADownload() {
        DueCheck due = new DueCheck(1L, 2L, "tt1", "Original", List.of(), Set.of(), "v1", false, List.of());

        assertThat(due.rateSide()).isEqualTo(Side.file());
        assertThat(due.isDownload()).isFalse();
        assertThat(due.originalCues()).isNull();
    }

    @Test
    void aDownloadGivenASideHasThatSide() {
        DueCheck due = new DueCheck(1L, 2L, "tt1", "Original", List.of(), Set.of(), "v1", false, List.of(), 7L,
            RETIMED);

        assertThat(due.rateSide()).isSameAs(RETIMED);
        assertThat(due.isDownload()).isTrue();
    }

    @Test
    void aDownloadGivenNoSideIsADownloadOfNoKnownRateNeverTheOwnersFile() {
        DueCheck due = new DueCheck(1L, 2L, "tt1", "Original", List.of(), Set.of(), "v1", false, List.of(), 7L,
            null, null);

        assertThat(due.rateSide()).isEqualTo(new Side(Kind.DOWNLOAD, null, null, null, null));
        assertThat(due.rateSide().retimed()).isFalse();
    }
}
