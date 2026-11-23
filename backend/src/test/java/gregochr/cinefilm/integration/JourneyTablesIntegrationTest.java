package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.text;
import static gregochr.cinefilm.journey.JourneyTestData.device;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.link;
import static gregochr.cinefilm.journey.JourneyTestData.modem;
import static gregochr.cinefilm.journey.JourneyTestData.modemSample;
import static gregochr.cinefilm.journey.JourneyTestData.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.journey.DeviceRepository;
import gregochr.cinefilm.journey.Journey;
import gregochr.cinefilm.journey.JourneyModemRepository;
import gregochr.cinefilm.journey.JourneyModemSampleRepository;
import gregochr.cinefilm.journey.JourneyPlaybackRepository;
import gregochr.cinefilm.journey.JourneyRepository;
import gregochr.cinefilm.journey.JourneySampleRepository;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** The journey tables on PostgreSQL, committed like production: the keys, the check, the cascades and the cap. */
@AutoConfigureMockMvc
class JourneyTablesIntegrationTest extends IntegrationTestBase {

    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";

    @Autowired
    private JourneyRepository journeys;

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private JourneySampleRepository samples;

    @Autowired
    private JourneyModemRepository modems;

    @Autowired
    private JourneyModemSampleRepository modemSamples;

    @Autowired
    private JourneyPlaybackRepository links;

    @Autowired
    private PlaybackHealthRepository health;

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmPreferenceRepository preferences;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformTransactionManager transactions;

    private long editionId;

    @BeforeEach
    void data() {
        links.deleteAll();
        journeys.deleteAll();
        devices.deleteAll();
        health.deleteAll();
        preferences.deleteAll();
        states.deleteAll();
        films.deleteAll();
        Film film = film(null, "Blade Runner", 1982, NOW, "Sci-fi");
        file(edition(film, null, "The Final Cut", 100 * MIN, null), "/nowhere/final.mp4");
        films.save(film);
        editionId = film.getEditions().get(0).getId();
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private void refused(Runnable work) {
        assertThatThrownBy(() -> inNewTransaction(() -> {
            work.run();
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long storedJourney(long runStartedMs, int segment) {
        return inNewTransaction(() -> journeys.saveAndFlush(journey(runStartedMs, segment, null)).getId());
    }

    private void send(ObjectNode body) throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/api/playback-health/" + ID)
                .with(user("owner").roles("OWNER")).header("X-CineFilm", "1")
                .contentType("application/json").content(text(body)))
            .andExpect(status().isNoContent());
    }

    @Test
    void theSameRunAndSegmentIsRefusedAndAnotherSegmentIsStored() {
        storedJourney(1_000_000L, 1);

        refused(() -> journeys.saveAndFlush(journey(1_000_000L, 1, null)));

        storedJourney(1_000_000L, 2);
        assertThat(journeys.count()).isEqualTo(2);
    }

    @Test
    void theSameDeviceKindIsRefused() {
        inNewTransaction(() -> devices.saveAndFlush(device("Testbook1,1")));

        refused(() -> devices.saveAndFlush(device("Testbook1,1")));

        assertThat(devices.count()).isEqualTo(1);
    }

    @Test
    void aTravelClassOtherThanStandardOrFirstIsRefusedAndANullOneIsStored() {
        Journey wrong = journey(1_000_000L, 1, null);
        wrong.setTravelClass("business");

        refused(() -> journeys.saveAndFlush(wrong));

        storedJourney(2_000_000L, 1);
        assertThat(journeys.count()).isEqualTo(1);
    }

    @Test
    void aModemSampleWithoutItsSampleOrWithoutItsModemIsRefused() {
        Long journeyId = storedJourney(1_000_000L, 1);
        inNewTransaction(() -> samples.saveAndFlush(sample(journeyId, 1)));
        inNewTransaction(() -> modems.saveAndFlush(modem(journeyId, 0)));

        refused(() -> modemSamples.saveAndFlush(modemSample(journeyId, 2, 0)));
        refused(() -> modemSamples.saveAndFlush(modemSample(journeyId, 1, 5)));

        assertThat(modemSamples.count()).isZero();
    }

    @Test
    void aSecondRowWithTheSameSampleKeyIsRefusedAndTheFirstIsUnchanged() {
        Long journeyId = storedJourney(1_000_000L, 1);
        var first = sample(journeyId, 1);
        first.setLatitude(12.5);
        inNewTransaction(() -> samples.saveAndFlush(first));
        var again = sample(journeyId, 1);
        again.setLatitude(99.5);

        refused(() -> samples.saveAndFlush(again));

        assertThat(samples.findByJourneyIdOrderByRoundNo(journeyId)).singleElement()
            .satisfies(row -> assertThat(row.getLatitude()).isEqualTo(12.5));
    }

    @Test
    void deletingAJourneyTakesItsSamplesModemsAndModemSamplesAndLeavesTheDevice() {
        Long deviceId = inNewTransaction(() -> devices.saveAndFlush(device("Testbook1,1")).getId());
        Long journeyId = inNewTransaction(() -> journeys.saveAndFlush(journey(1_000_000L, 1, deviceId)).getId());
        inNewTransaction(() -> samples.saveAndFlush(sample(journeyId, 1)));
        inNewTransaction(() -> modems.saveAndFlush(modem(journeyId, 0)));
        inNewTransaction(() -> modemSamples.saveAndFlush(modemSample(journeyId, 1, 0)));

        inNewTransaction(() -> {
            journeys.deleteAllByIdInBatch(List.of(journeyId));
            return null;
        });

        assertThat(samples.count()).isZero();
        assertThat(modems.count()).isZero();
        assertThat(modemSamples.count()).isZero();
        assertThat(devices.findById(deviceId)).isPresent();
    }

    @Test
    void theCapsBulkDeleteOfALinkedViewingTakesTheLinkAndTheNextViewingIsStoredOverHttp() throws Exception {
        send(example(editionId, 1));
        Long recordId = health.findByPlaybackId(ID).orElseThrow().getId();
        Long journeyId = storedJourney(1_000_000L, 1);
        inNewTransaction(() -> links.saveAndFlush(link(journeyId, recordId)));

        inNewTransaction(() -> {
            health.deleteAllByIdInBatch(List.of(recordId));
            return null;
        });

        assertThat(links.findByJourneyId(journeyId)).isEmpty();
        assertThat(journeys.findById(journeyId)).isPresent();
        assertThat(health.findByPlaybackId(ID)).isEmpty();
        send(example(editionId, 1));
        assertThat(health.findByPlaybackId(ID)).isPresent();
    }
}
