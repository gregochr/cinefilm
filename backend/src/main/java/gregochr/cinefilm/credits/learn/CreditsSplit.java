package gregochr.cinefilm.credits.learn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The fixed train/test split: a film's unit key, the hash that puts the unit on a side and in a fold, and the
 * carry-over rules that keep earlier decisions when the library changes. Pure. The rules are in
 * {@code docs/engineering/credits-classifier-plan.md}, "The split".
 */
final class CreditsSplit {

    /** A unit is on the development side when its hash mod 100 is below this. */
    static final int DEV_PERCENT = 70;
    /** The number of cross-validation folds. */
    static final int FOLDS = 5;

    private CreditsSplit() {
    }

    /**
     * One film to place.
     *
     * @param filmId the film
     * @param unitKey its unit key
     */
    record SplitFilm(long filmId, String unitKey) {
    }

    /**
     * What the split decided, and what it had to report.
     *
     * @param entries every film's entry, by film id
     * @param keptOnOldSide films that kept their old side after their unit key changed
     * @param preseedOverridden films the previous manifest had on the test side although the pre-seed would put their
     *     unit on development: the previous manifest won
     * @param conflicts films excluded as {@code SPLIT_CONFLICT}; their entry holds their own hash side and fold,
     *     for reference only
     * @param unitsSplit units that have films on both sides
     * @param preseedMissing pre-seeded film ids that are not in the export
     */
    record Result(Map<Long, ManifestEntry> entries, List<Long> keptOnOldSide, List<Long> preseedOverridden,
                  List<Long> conflicts, int unitsSplit, List<Long> preseedMissing) {
    }

    /**
     * The unit key: the first that applies.
     *
     * @param collectionTmdbId the collection's TMDB id, or null
     * @param collectionId the collection's id, or null
     * @param filmTmdbId the film's TMDB id, or null
     * @param title the film's title
     * @param year the film's release year, or null
     * @return {@code ctmdb:<id>}, {@code cid:<id>}, {@code tmdb:<id>} or {@code title:<t>|<year>}
     */
    static String unitKey(Long collectionTmdbId, Long collectionId, Long filmTmdbId, String title, Integer year) {
        if (collectionTmdbId != null) {
            return "ctmdb:" + collectionTmdbId;
        }
        if (collectionId != null) {
            return "cid:" + collectionId;
        }
        if (filmTmdbId != null) {
            return "tmdb:" + filmTmdbId;
        }
        String name = Normalizer.normalize(title, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
        return "title:" + name + "|" + (year == null ? "" : year);
    }

    /** Where the hash gets its SHA-256, so a test can show what happens when the JVM has none. */
    interface Digests {
        /** A new SHA-256 digest. */
        MessageDigest sha256() throws NoSuchAlgorithmException;
    }

    /** The first 8 bytes of SHA-256 over the unit key's UTF-8 bytes, big-endian, as an unsigned 64-bit value. */
    static long hash(String unitKey) {
        return hash(unitKey, () -> MessageDigest.getInstance("SHA-256"));
    }

    static long hash(String unitKey, Digests digests) {
        try {
            byte[] digest = digests.sha256().digest(unitKey.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }

    /** The side the hash gives a unit: hash mod 100 below 70 is development. */
    static Side hashSide(String unitKey) {
        return Long.remainderUnsigned(hash(unitKey), 100) < DEV_PERCENT ? Side.DEV : Side.TEST;
    }

    /** The fold the hash gives a unit: hash mod 5. Only development units use it. */
    static int hashFold(String unitKey) {
        return (int) Long.remainderUnsigned(hash(unitKey), FOLDS);
    }

    /**
     * Places every film, applying the carry-over rules in order.
     *
     * @param films every exported film, once
     * @param previous the previous manifest's entries by film id (empty on a first run)
     * @param preseed film ids forced into development with their whole units
     * @return the entries and what to report
     */
    static Result assign(List<SplitFilm> films, Map<Long, ManifestEntry> previous, Set<Long> preseed) {
        Map<Long, String> unitOf = new TreeMap<>();
        films.forEach(f -> unitOf.put(f.filmId(), f.unitKey()));
        Set<String> preseedUnits = new HashSet<>();
        List<Long> preseedMissing = new ArrayList<>();
        for (long id : preseed.stream().sorted().toList()) {
            if (unitOf.containsKey(id)) {
                preseedUnits.add(unitOf.get(id));
            } else {
                preseedMissing.add(id);
            }
        }
        Map<String, List<ManifestEntry>> members = new TreeMap<>();
        previous.values().stream().sorted(Comparator.comparingLong(ManifestEntry::filmId))
            .forEach(e -> members.computeIfAbsent(unitOf.getOrDefault(e.filmId(), e.unitKey()), k -> new ArrayList<>())
                .add(e));

        Map<Long, ManifestEntry> entries = new TreeMap<>();
        List<Long> kept = new ArrayList<>();
        List<Long> overridden = new ArrayList<>();
        List<Long> conflicts = new ArrayList<>();
        for (Map.Entry<Long, String> film : unitOf.entrySet()) {
            long id = film.getKey();
            String unit = film.getValue();
            ManifestEntry old = previous.get(id);
            if (old != null) {
                entries.put(id, new ManifestEntry(id, unit, old.side(), old.fold()));
                if (!old.unitKey().equals(unit)) {
                    kept.add(id);
                }
                if (old.side() == Side.TEST && preseedUnits.contains(unit)) {
                    overridden.add(id);
                }
            } else if (members.containsKey(unit)) {
                List<ManifestEntry> same = members.get(unit);
                if (EnumSet.copyOf(same.stream().map(ManifestEntry::side).toList()).size() > 1) {
                    Side side = hashSide(unit);
                    entries.put(id, new ManifestEntry(id, unit, side, side == Side.DEV ? hashFold(unit) : null));
                    conflicts.add(id);
                } else {
                    entries.put(id, new ManifestEntry(id, unit, same.get(0).side(), same.get(0).fold()));
                }
            } else if (preseedUnits.contains(unit)) {
                entries.put(id, new ManifestEntry(id, unit, Side.DEV, hashFold(unit)));
            } else {
                Side side = hashSide(unit);
                entries.put(id, new ManifestEntry(id, unit, side, side == Side.DEV ? hashFold(unit) : null));
            }
        }
        return new Result(entries, kept, overridden, conflicts, unitsSplit(entries), preseedMissing);
    }

    private static int unitsSplit(Map<Long, ManifestEntry> entries) {
        Map<String, Set<Side>> sides = new TreeMap<>();
        for (ManifestEntry entry : entries.values()) {
            sides.computeIfAbsent(entry.unitKey(), k -> EnumSet.noneOf(Side.class)).add(entry.side());
        }
        return (int) sides.values().stream().filter(s -> s.size() > 1).count();
    }
}
