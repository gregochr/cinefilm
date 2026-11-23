package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.JourneyFile.DeviceInfo;
import gregochr.cinefilm.journey.JourneyFile.Modem;
import gregochr.cinefilm.journey.JourneyFile.Sample;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The rules a journey file must keep. The file comes from the browser and is untrusted: each value is bounded, so
 * one bad value refuses the whole file and nothing odd is stored. Every failure is the same
 * {@link BadRequestException}, and nothing else is ever thrown, whatever is null. The ranges are the plan's tables
 * ({@code journey-data-plan.md}, "The import's request"), kept as tables here.
 */
public final class JourneyFileRules {

    /** The message of every refusal. */
    public static final String INVALID = "Invalid journey file";

    /** Most samples a file holds. */
    static final int MAX_SAMPLES = 2_000;

    /** Most modems a sample holds. */
    static final int MAX_MODEMS = 16;

    static final long MIN_MS = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
    static final long MAX_MS = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli();
    private static final long MIN_S = MIN_MS / 1_000;
    private static final long MAX_S = MAX_MS / 1_000;
    private static final long TERA = 1_000_000_000_000L;
    private static final long PETA = 1_000_000_000_000_000L;
    private static final int OPERATOR_MAX = 10;

    /** A whole number: its name in the file, its range, whether it must be there, and where to read it. */
    record Whole<T>(String name, long low, long high, boolean required, Function<T, Number> get) {
    }

    /** A decimal: its name, its range ({@code lowOpen}: the lowest value itself is out) and where to read it. */
    record Decimal<T>(String name, double low, double high, boolean lowOpen, Function<T, Double> get) {
    }

    /** A text: its name, its longest length (the shortest is 1) and where to read it. */
    record Text<T>(String name, int max, Function<T, String> get) {
    }

    /** A text that must be one of a few words. */
    record Word<T>(String name, Set<String> allowed, Function<T, String> get) {
    }

    static final List<Whole<JourneyFile>> FILE_WHOLES = List.of(
        new Whole<>("runStartedMs", MIN_MS, MAX_MS, true, JourneyFile::runStartedMs),
        new Whole<>("loggerVersion", 2, 3, true, JourneyFile::loggerVersion));

    static final List<Whole<DeviceInfo>> DEVICE_WHOLES = List.of(
        new Whole<>("cores", 1, 1_024, true, DeviceInfo::cores),
        new Whole<>("threads", 1, 4_096, true, DeviceInfo::threads),
        new Whole<>("memoryBytes", 1, 1L << 50, true, DeviceInfo::memoryBytes));

    static final List<Whole<Sample>> SAMPLE_WHOLES = List.of(
        new Whole<>("round", 1, 10_000_000, true, Sample::round),
        new Whole<>("at", MIN_MS, MAX_MS, true, Sample::at),
        new Whole<>("feedAt", MIN_S, MAX_S, false, Sample::feedAt),
        new Whole<>("fixMode", 0, 9, false, Sample::fixMode),
        new Whole<>("fixAgeS", 0, 1_000_000, false, Sample::fixAgeS),
        new Whole<>("satellites", 0, 200, false, Sample::satellites),
        new Whole<>("online", 0, 9, false, Sample::online),
        new Whole<>("usersOnline", 0, 100_000, false, Sample::usersOnline),
        new Whole<>("usersTotal", 0, 100_000, false, Sample::usersTotal),
        new Whole<>("downLimit", 0, TERA, false, Sample::downLimit),
        new Whole<>("upLimit", 0, TERA, false, Sample::upLimit),
        new Whole<>("dataDownUsed", 0, PETA, false, Sample::dataDownUsed),
        new Whole<>("dataUpUsed", 0, PETA, false, Sample::dataUpUsed),
        new Whole<>("capLevel", 0, 1_000, false, Sample::capLevel),
        new Whole<>("userClass", 0, 1_000, false, Sample::userClass),
        new Whole<>("probeMs", 0, 600_000, false, Sample::probeMs),
        new Whole<>("probeCode", 100, 599, false, Sample::probeCode),
        new Whole<>("batteryPercent", 0, 100, false, Sample::batteryPercent),
        new Whole<>("lowPower", 0, 1, false, Sample::lowPower),
        new Whole<>("cpuLimit", 0, 100, false, Sample::cpuLimit),
        new Whole<>("wifiRssi", -120, -1, false, Sample::wifiRssi),
        new Whole<>("wifiNoise", -120, -1, false, Sample::wifiNoise),
        new Whole<>("wifiPhy", 1, 20, false, Sample::wifiPhy),
        new Whole<>("wifiBand", 1, 20, false, Sample::wifiBand),
        new Whole<>("wifiWidth", 1, 20, false, Sample::wifiWidth),
        new Whole<>("wifiChannel", 1, 233, false, Sample::wifiChannel));

    static final List<Decimal<Sample>> SAMPLE_DECIMALS = List.of(
        new Decimal<>("latitude", -90, 90, false, Sample::latitude),
        new Decimal<>("longitude", -180, 180, false, Sample::longitude),
        new Decimal<>("altitudeM", -1_000, 10_000, false, Sample::altitudeM),
        new Decimal<>("speedMps", 0, 200, false, Sample::speedMps),
        new Decimal<>("courseDeg", 0, 360, false, Sample::courseDeg),
        new Decimal<>("loadAvg", 0, 100_000, false, Sample::loadAvg),
        new Decimal<>("swapMb", 0, 10_000_000, false, Sample::swapMb),
        new Decimal<>("wifiRate", 0, 100_000, true, Sample::wifiRate));

    static final List<Whole<Modem>> MODEM_WHOLES = List.of(
        new Whole<>("index", 0, 100_000, true, Modem::index),
        new Whole<>("rssi", -200, 0, false, Modem::rssi),
        new Whole<>("cellId", 0, 1L << 40, false, Modem::cellId));

    static final List<Text<JourneyFile>> FILE_TEXTS = List.of(
        new Text<>("sourceFile", 200, JourneyFile::sourceFile),
        new Text<>("osVersion", 40, JourneyFile::osVersion));

    static final List<Text<DeviceInfo>> DEVICE_TEXTS = List.of(
        new Text<>("model", 100, DeviceInfo::model),
        new Text<>("cpu", 100, DeviceInfo::cpu));

    static final List<Text<Sample>> SAMPLE_TEXTS = List.of(new Text<>("trainUnit", 100, Sample::trainUnit));

    static final List<Text<Modem>> MODEM_TEXTS = List.of(
        new Text<>("deviceType", 40, Modem::deviceType),
        new Text<>("deviceSubtype", 40, Modem::deviceSubtype),
        new Text<>("deviceState", 40, Modem::deviceState),
        new Text<>("linkState", 40, Modem::linkState),
        new Text<>("technology", 40, Modem::technology),
        new Text<>("operatorId", OPERATOR_MAX, Modem::operatorId));

    static final List<Word<JourneyFile>> FILE_WORDS = List.of(
        new Word<>("travelClass", Set.of("standard", "first"), JourneyFile::travelClass));

    static final List<Word<Sample>> SAMPLE_WORDS = List.of(
        new Word<>("powerSource", Set.of("ac", "battery"), Sample::powerSource));

    private JourneyFileRules() {
    }

    /** Returns normally for a valid file; throws {@link BadRequestException} for anything else. */
    public static void validate(JourneyFile file) {
        refuseUnless(file != null && fields(file, FILE_WHOLES, List.of(), FILE_TEXTS, FILE_WORDS));
        refuseUnless(device(file.device()));
        List<Sample> samples = file.samples();
        refuseUnless(samples != null && !samples.isEmpty() && samples.size() <= MAX_SAMPLES);
        int previous = 0;
        for (Sample sample : samples) {
            refuseUnless(sample != null && fields(sample, SAMPLE_WHOLES, SAMPLE_DECIMALS, SAMPLE_TEXTS, SAMPLE_WORDS));
            refuseUnless(sample.round() > previous && modems(sample.modems()));
            previous = sample.round();
        }
    }

    private static void refuseUnless(boolean ok) {
        if (!ok) {
            throw new BadRequestException(INVALID);
        }
    }

    /** No device, or a whole one: a device with only some of its five values is refused. */
    private static boolean device(DeviceInfo device) {
        return device == null || (device.model() != null && device.cpu() != null
            && fields(device, DEVICE_WHOLES, List.of(), DEVICE_TEXTS, List.of()));
    }

    private static boolean modems(List<Modem> modems) {
        if (modems == null) {
            return true;
        }
        if (modems.size() > MAX_MODEMS) {
            return false;
        }
        Set<Integer> seen = new HashSet<>();
        for (Modem modem : modems) {
            if (modem == null || !fields(modem, MODEM_WHOLES, List.of(), MODEM_TEXTS, List.of())
                || !seen.add(modem.index())
                || (modem.operatorId() != null && !modem.operatorId().chars().allMatch(c -> c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** Whether every field of one record keeps its table. */
    private static <T> boolean fields(T record, List<Whole<T>> wholes, List<Decimal<T>> decimals, List<Text<T>> texts,
                                      List<Word<T>> words) {
        for (Whole<T> whole : wholes) {
            Number value = whole.get().apply(record);
            boolean outside = value != null && (value.longValue() < whole.low() || value.longValue() > whole.high());
            if (outside || (value == null && whole.required())) {
                return false;
            }
        }
        for (Decimal<T> decimal : decimals) {
            Double value = decimal.get().apply(record);
            if (value != null && !(Double.isFinite(value) && value <= decimal.high()
                && (decimal.lowOpen() ? value > decimal.low() : value >= decimal.low()))) {
                return false;
            }
        }
        for (Text<T> text : texts) {
            String value = text.get().apply(record);
            if (value != null && !printable(value, text.max())) {
                return false;
            }
        }
        for (Word<T> word : words) {
            String value = word.get().apply(record);
            if (value != null && !word.allowed().contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean printable(String value, int max) {
        return !value.isEmpty() && value.length() <= max && value.chars().allMatch(c -> c >= ' ' && c <= '~');
    }
}
