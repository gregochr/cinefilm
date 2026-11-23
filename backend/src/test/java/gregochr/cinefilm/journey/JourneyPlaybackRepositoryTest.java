package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.link;
import static gregochr.cinefilm.journey.JourneyTestData.playback;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The links between journeys and playback records, including the cap's bulk delete of old records. */
class JourneyPlaybackRepositoryTest extends ApiTestBase {

    private static final String PLAYBACK_A = "00000000-0000-4000-8000-00000000000a";
    private static final String PLAYBACK_B = "00000000-0000-4000-8000-00000000000b";

    @Autowired
    private JourneyRepository journeys;

    @Autowired
    private JourneyPlaybackRepository links;

    @Autowired
    private PlaybackHealthRepository health;

    @Autowired
    private EntityManager entityManager;

    private Long storedJourney(long runStartedMs) {
        return journeys.saveAndFlush(journey(runStartedMs, 1, null)).getId();
    }

    private Long playbackRecord(String playbackId) {
        return health.saveAndFlush(playback(playbackId)).getId();
    }

    @Test
    void aLinkStoresAndTheSamePairCannotBeStoredTwice() {
        Long journeyId = storedJourney(1_000_000L);
        Long recordId = playbackRecord(PLAYBACK_A);
        links.saveAndFlush(link(journeyId, recordId));
        entityManager.clear();

        assertThatThrownBy(() -> links.saveAndFlush(link(journeyId, recordId)))
            .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        assertThat(links.count()).isEqualTo(1);
        assertThat(links.findById(new JourneyPlaybackId(journeyId, recordId))).isPresent();
    }

    @Test
    void aSavedLinkIsNotNewAndSavingTheSameInstanceAgainAfterAClearAddsNoRow() {
        Long journeyId = storedJourney(1_000_000L);
        Long recordId = playbackRecord(PLAYBACK_A);
        JourneyPlayback saved = link(journeyId, recordId);
        links.saveAndFlush(saved);
        assertThat(saved.isNew()).isFalse();
        assertThat(saved.getId()).isEqualTo(new JourneyPlaybackId(journeyId, recordId));
        entityManager.clear();

        links.saveAndFlush(saved);
        entityManager.clear();

        assertThat(links.findById(saved.getId()).orElseThrow().isNew()).isFalse();
        assertThat(links.count()).isEqualTo(1);
    }

    @Test
    void oneRecordMayBeLinkedToTwoJourneysAndOneJourneyToTwoRecords() {
        Long first = storedJourney(1_000_000L);
        Long second = storedJourney(2_000_000L);
        Long recordA = playbackRecord(PLAYBACK_A);
        Long recordB = playbackRecord(PLAYBACK_B);
        links.saveAndFlush(link(first, recordA));
        links.saveAndFlush(link(second, recordA));
        links.saveAndFlush(link(first, recordB));
        entityManager.clear();

        assertThat(links.findByPlaybackHealthId(recordA)).extracting(JourneyPlayback::getJourneyId)
            .containsExactlyInAnyOrder(first, second);
        assertThat(links.findByJourneyId(first)).extracting(JourneyPlayback::getPlaybackHealthId)
            .containsExactlyInAnyOrder(recordA, recordB);
    }

    @Test
    void aLinkToAJourneyOrARecordThatDoesNotExistIsRefused() {
        Long journeyId = storedJourney(1_000_000L);
        Long recordId = playbackRecord(PLAYBACK_A);

        assertThatThrownBy(() -> links.saveAndFlush(link(journeyId, recordId + 1000)))
            .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();
        assertThatThrownBy(() -> links.saveAndFlush(link(journeyId + 1000, recordId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheJourneyTakesItsLinkAndLeavesThePlaybackRecord() {
        Long journeyId = storedJourney(1_000_000L);
        Long recordId = playbackRecord(PLAYBACK_A);
        links.saveAndFlush(link(journeyId, recordId));
        entityManager.clear();

        journeys.deleteAllByIdInBatch(List.of(journeyId));
        entityManager.clear();

        assertThat(links.count()).isZero();
        assertThat(health.findById(recordId)).isPresent();
    }

    @Test
    void theCapsBulkDeleteOfALinkedRecordTakesTheLinkLeavesTheJourneyAndANewRecordStoresAfterwards() {
        Long journeyId = storedJourney(1_000_000L);
        Long recordId = playbackRecord(PLAYBACK_A);
        links.saveAndFlush(link(journeyId, recordId));
        entityManager.clear();

        health.deleteAllByIdInBatch(List.of(recordId));
        entityManager.clear();

        assertThat(links.count()).isZero();
        assertThat(journeys.findById(journeyId)).isPresent();
        assertThat(health.findById(recordId)).isEmpty();
        assertThat(playbackRecord(PLAYBACK_B)).isNotNull();
    }
}
