package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Saving a film's keywords: stamp first (the guard), then replace the rows; refused for a gone or rematched film. */
@ExtendWith(MockitoExtension.class)
class FilmKeywordsTest {

    @Mock
    private FilmRepository films;

    @Test
    void theGuardedStampComesFirstThenTheRowsAreReplacedInOrder() {
        when(films.stampKeywords(1L, 949L, NOW)).thenReturn(1);

        boolean saved = new FilmKeywords(films).save(1L, 949L, List.of("heist", "christmas"), NOW);

        assertThat(saved).isTrue();
        InOrder order = inOrder(films);
        order.verify(films).stampKeywords(1L, 949L, NOW);
        order.verify(films).deleteKeywordRows(1L);
        order.verify(films).insertKeywordRow(1L, "heist");
        order.verify(films).insertKeywordRow(1L, "christmas");
        order.verifyNoMoreInteractions();
    }

    @Test
    void noKeywordsStillStampsTheFilmAndClearsTheRows() {
        when(films.stampKeywords(1L, 949L, NOW)).thenReturn(1);

        boolean saved = new FilmKeywords(films).save(1L, 949L, List.of(), NOW);

        assertThat(saved).isTrue();
        verify(films).stampKeywords(1L, 949L, NOW);
        verify(films).deleteKeywordRows(1L);
        verifyNoMoreInteractions(films);
    }

    @Test
    void aKeywordLongerThanTheColumnIsCutAndDuplicatesAfterCuttingAreInsertedOnce() {
        when(films.stampKeywords(1L, 949L, NOW)).thenReturn(1);

        new FilmKeywords(films).save(1L, 949L, List.of("x".repeat(150), "x".repeat(120)), NOW);

        verify(films).insertKeywordRow(1L, "x".repeat(100));
    }

    @Test
    void theCutNeverSplitsASurrogatePair() {
        when(films.stampKeywords(1L, 949L, NOW)).thenReturn(1);
        String emoji = "🎄";

        new FilmKeywords(films).save(1L, 949L, List.of("x".repeat(99) + emoji), NOW);

        verify(films).insertKeywordRow(1L, "x".repeat(99));
    }

    @Test
    void aFilmRematchedMeanwhileOrGoneIsRefusedAndNothingElseIsWritten() {
        when(films.stampKeywords(1L, 111L, NOW)).thenReturn(0);

        boolean saved = new FilmKeywords(films).save(1L, 111L, List.of("heist"), NOW);

        assertThat(saved).isFalse();
        verify(films).stampKeywords(1L, 111L, NOW);
        verifyNoMoreInteractions(films);
    }
}
