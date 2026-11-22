package gregochr.cinefilm.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The candidates against the pass with cards off. */
class CreditsAutoCandidatesCardsOffIntegrationTest extends CreditsAutoCandidatesIntegrationBase {

    @DynamicPropertySource
    static void cardsOff(DynamicPropertyRegistry registry) {
        registry.add("cinefilm.credits.auto.cards", () -> "false");
    }

    @Override
    boolean cardsOn() {
        return false;
    }
}
