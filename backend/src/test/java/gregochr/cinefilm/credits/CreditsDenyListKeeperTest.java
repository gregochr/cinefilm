package gregochr.cinefilm.credits;

import static gregochr.cinefilm.credits.CreditsAutoTestData.denyFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import gregochr.cinefilm.credits.CreditsDenyListKeeper.Refreshed;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The list in force: the latest good read, kept across bad ones, and never read from the file by {@code current}. */
@ExtendWith(MockitoExtension.class)
class CreditsDenyListKeeperTest {

    @Mock
    private CreditsDenyFileReader reader;

    @Test
    void nothingIsInForceUntilAReadSucceedsAndCurrentNeverReadsTheFile() {
        CreditsDenyListKeeper keeper = new CreditsDenyListKeeper(reader);

        assertThat(keeper.current()).isNull();
        verifyNoInteractions(reader);
    }

    @Test
    void aBadReadAtStartUpLeavesNoListInForce() {
        when(reader.read()).thenReturn(Optional.empty());
        CreditsDenyListKeeper keeper = new CreditsDenyListKeeper(reader);

        assertThat(keeper.refresh()).isEqualTo(new Refreshed(null, false));
        assertThat(keeper.current()).isNull();
    }

    @Test
    void aGoodReadIsInForceAndAUsingItIsNotAFallBack() {
        Parsed good = denyFile("file 1");
        when(reader.read()).thenReturn(Optional.of(good));
        CreditsDenyListKeeper keeper = new CreditsDenyListKeeper(reader);

        assertThat(keeper.refresh()).isEqualTo(new Refreshed(good, false));
        assertThat(keeper.current()).isSameAs(good);
    }

    @Test
    void aGoodReadThenABadOneKeepsTheFirstCopyAndSaysItFellBack() {
        Parsed first = denyFile("file 1");
        when(reader.read()).thenReturn(Optional.of(first), Optional.empty(), Optional.empty());
        CreditsDenyListKeeper keeper = new CreditsDenyListKeeper(reader);

        keeper.refresh();

        assertThat(keeper.refresh()).isEqualTo(new Refreshed(first, true));
        assertThat(keeper.refresh()).as("still the first copy on a second bad read")
            .isEqualTo(new Refreshed(first, true));
        assertThat(keeper.current()).isSameAs(first);
    }

    @Test
    void aBadReadThenAGoodOneUsesTheNewCopy() {
        Parsed later = denyFile("file 2");
        when(reader.read()).thenReturn(Optional.empty(), Optional.of(later));
        CreditsDenyListKeeper keeper = new CreditsDenyListKeeper(reader);

        keeper.refresh();

        assertThat(keeper.refresh()).isEqualTo(new Refreshed(later, false));
        assertThat(keeper.current()).isSameAs(later);
    }

    @Test
    void aNewerGoodCopyReplacesTheOlderOneAndCurrentReadsNothingMore() {
        Parsed first = denyFile("file 1");
        Parsed second = denyFile("file 2", "file 3");
        when(reader.read()).thenReturn(Optional.of(first), Optional.of(second));
        CreditsDenyListKeeper keeper = new CreditsDenyListKeeper(reader);

        keeper.refresh();
        keeper.refresh();
        keeper.current();

        assertThat(keeper.current()).isSameAs(second);
        verify(reader, times(2)).read();
    }
}
