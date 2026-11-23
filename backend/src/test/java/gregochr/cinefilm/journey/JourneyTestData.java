package gregochr.cinefilm.journey;

import static gregochr.cinefilm.TestData.NOW;

import gregochr.cinefilm.health.PlaybackHealth;
import java.time.Instant;
import java.time.LocalDate;

/** Made-up rows for the journey tables' tests: every value is invented. */
public final class JourneyTestData {

    private JourneyTestData() {
    }

    public static Device device(String model) {
        Device device = new Device();
        device.setModel(model);
        device.setCpu("Test Chip One");
        device.setCores(8);
        device.setThreads(8);
        device.setMemoryBytes(17_179_869_184L);
        return device;
    }

    public static Journey journey(long runStartedMs, int segment, Long deviceId) {
        Journey journey = new Journey();
        journey.setRunStartedMs(runStartedMs);
        journey.setSegment(segment);
        journey.setDeviceId(deviceId);
        journey.setLoggerVersion(3);
        journey.setImportedAt(NOW);
        return journey;
    }

    /** A journey with every column given its own value. */
    public static Journey fullJourney(Long deviceId, Long trainRunId) {
        Journey journey = journey(1_000_000L, 2, deviceId);
        journey.setTrainRunId(trainRunId);
        journey.setOsVersion("TestOS 1.2");
        journey.setTravelClass("first");
        journey.setTrainUnit("Unit-000");
        journey.setSourceFile("test-log.jsonl");
        return journey;
    }

    /** A made-up train run: a uid, a date and the headcode, with only the required columns. */
    public static TrainRun trainRun(String timetableUid, LocalDate runDate) {
        TrainRun run = new TrainRun();
        run.setTimetableUid(timetableUid);
        run.setRunDate(runDate);
        run.setHeadcode("9Z99");
        run.setImportedAt(NOW);
        return run;
    }

    /** A train run with every column given its own value. */
    public static TrainRun fullTrainRun() {
        TrainRun run = trainRun("X12345", LocalDate.of(2030, 1, 15));
        run.setOperator("Test Rail Company");
        run.setSource("https://example.invalid/run");
        return run;
    }

    /** A call with only its key and place. */
    public static TrainRunCall call(Long trainRunId, int seq) {
        TrainRunCall call = new TrainRunCall();
        call.setTrainRunId(trainRunId);
        call.setSeq(seq);
        call.setLocation("Alpha Junction");
        return call;
    }

    /** A call with every column given its own value. */
    public static TrainRunCall fullCall(Long trainRunId) {
        TrainRunCall call = call(trainRunId, 4);
        call.setLocation("Beta Parkway");
        call.setPlannedArrival(NOW.plusSeconds(1));
        call.setPlannedDeparture(NOW.plusSeconds(2));
        call.setPlannedPass(NOW.plusSeconds(3));
        call.setActualArrival(NOW.plusSeconds(4));
        call.setActualDeparture(NOW.plusSeconds(5));
        call.setActualPass(NOW.plusSeconds(6));
        return call;
    }

    /** A sample with every column given its own value. */
    public static JourneySample fullSample(Long journeyId) {
        JourneySample full = sample(journeyId, 7);
        full.setFeedAt(NOW.plusSeconds(1));
        full.setFixMode(3);
        full.setFixAgeS(4);
        full.setLatitude(12.5);
        full.setLongitude(34.5);
        full.setAltitudeM(56.5);
        full.setSpeedMps(21.5);
        full.setCourseDeg(187.5);
        full.setSatellites(9);
        full.setOnline(1);
        full.setUsersOnline(11);
        full.setUsersTotal(12);
        full.setDownLimit(1_300L);
        full.setUpLimit(1_400L);
        full.setCapLevel(15);
        full.setUserClass(16);
        full.setDataDownUsed(1_700L);
        full.setDataUpUsed(1_800L);
        full.setProbeMs(19);
        full.setProbeCode(204);
        full.setPowerSource("Test Power");
        full.setBatteryPercent(22);
        full.setLowPower(0);
        full.setCpuLimit(24);
        full.setLoadAvg(2.5);
        full.setSwapMb(26.5);
        full.setWifiRssi(-57);
        full.setWifiNoise(-91);
        full.setWifiRate(29.5);
        full.setWifiPhy(8);
        full.setWifiChannel(31);
        full.setWifiBand(32);
        full.setWifiWidth(33);
        return full;
    }

    /** A modem sample with every column given its own value. */
    public static JourneyModemSample fullModemSample(Long journeyId) {
        JourneyModemSample full = modemSample(journeyId, 1, 0);
        full.setDeviceState("TestState");
        full.setLinkState("TestLink");
        full.setTechnology("TestTech");
        full.setOperatorId("00000");
        full.setCellId(123_456L);
        return full;
    }

    public static JourneySample sample(Long journeyId, int roundNo) {
        JourneySample sample = new JourneySample();
        sample.setJourneyId(journeyId);
        sample.setRoundNo(roundNo);
        sample.setSampledAt(NOW.plusSeconds(30L * roundNo));
        return sample;
    }

    public static JourneyModem modem(Long journeyId, int modemIndex) {
        JourneyModem modem = new JourneyModem();
        modem.setJourneyId(journeyId);
        modem.setModemIndex(modemIndex);
        modem.setDeviceType("TestModemType");
        modem.setDeviceSubtype("TestSubtype");
        return modem;
    }

    public static JourneyModemSample modemSample(Long journeyId, int roundNo, int modemIndex) {
        JourneyModemSample sample = new JourneyModemSample();
        sample.setJourneyId(journeyId);
        sample.setRoundNo(roundNo);
        sample.setModemIndex(modemIndex);
        sample.setRssi(-70);
        return sample;
    }

    public static JourneyPlayback link(Long journeyId, Long playbackHealthId) {
        JourneyPlayback link = new JourneyPlayback();
        link.setJourneyId(journeyId);
        link.setPlaybackHealthId(playbackHealthId);
        return link;
    }

    public static PlaybackHealth playback(String playbackId) {
        PlaybackHealth row = new PlaybackHealth();
        row.setPlaybackId(playbackId);
        row.setEditionId(1L);
        row.setFilmId(1L);
        row.setFilmTitle("Test Film");
        Instant started = NOW.minusSeconds(60);
        row.setStartedAt(started);
        row.setUpdatedAt(NOW);
        row.setSeq(1);
        row.setSummary("{}");
        return row;
    }
}
