package gregochr.cinefilm.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The candidates against the pass with cards on (the default). */
class CreditsAutoCandidatesCardsOnIntegrationTest extends CreditsAutoCandidatesIntegrationBase {

    @DynamicPropertySource
    static void cardsOn(DynamicPropertyRegistry registry) {
        registry.add("cinefilm.credits.auto.cards", () -> "true");
    }

    @Override
    boolean cardsOn() {
        return true;
    }
}
