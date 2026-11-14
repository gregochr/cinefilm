package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.plex.PlexRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * One run of the labels tool: reads the archive, the export and the optional inputs, labels every file, places every
 * film on a side, and writes {@code labels-<date>.json}, {@code split-<date>.json} and
 * {@code labels-<date>-report.txt}. No database, no clock, no network.
 */
final class LabelsJob {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> EXCLUSION_HEADER = List.of("media_file_id", "reason");

    private LabelsJob() {
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return the three files written, in the order labels, split, report
     * @throws IOException when an input cannot be read or an output cannot be written
     * @throws IllegalArgumentException when an input is malformed
     */
    static List<Path> run(LabelsOptions options) throws IOException {
        PlexArchive archive = ArchiveReader.read(options.archive());
        List<ExportRow> rows = ExportReader.parse(read(options.export()));
        Map<Long, String> ownerReasons = exclusions(options.exclusions());
        Set<Long> preseed = preseed(read(options.preseed()));
        Map<Long, ManifestEntry> previous = new TreeMap<>();
        List<Object> looks = List.of();
        if (options.previousSplit() != null) {
            SplitManifest manifest = MAPPER.readValue(read(options.previousSplit()), SplitManifest.class);
            validate(manifest);
            manifest.films().forEach(entry -> previous.put(entry.filmId(), entry));
            looks = manifest.looks() == null ? List.of() : manifest.looks();
        }

        List<ExportRow> sorted = rows.stream().sorted(Comparator.comparingLong(ExportRow::mediaFileId)).toList();
        Map<Long, CreditsSplit.SplitFilm> films = new TreeMap<>();
        for (ExportRow row : sorted) {
            films.putIfAbsent(row.filmId(), new CreditsSplit.SplitFilm(row.filmId(), CreditsSplit.unitKey(
                row.collectionTmdbId(), row.collectionId(), row.filmTmdbId(), row.filmTitle(), row.filmYear())));
        }
        CreditsSplit.Result split = CreditsSplit.assign(new ArrayList<>(films.values()), previous, preseed);

        Map<String, List<ArchiveMatch>> index = archive.index(options.prefix());
        Set<Long> conflicts = new HashSet<>(split.conflicts());
        List<LabelRecord> records = new ArrayList<>();
        for (ExportRow row : sorted) {
            FileLabel label = CreditsLabels.label(row, index.getOrDefault(PlexRules.nfc(row.path()), List.of()),
                ownerReasons.containsKey(row.mediaFileId()));
            if (label.excluded() == null && conflicts.contains(row.filmId())) {
                label = FileLabel.excluded(ExcludeReason.SPLIT_CONFLICT);
            }
            ManifestEntry entry = split.entries().get(row.filmId());
            records.add(new LabelRecord(row.mediaFileId(), row.fingerprint(), entry.unitKey(), entry.side(),
                entry.side() == Side.DEV ? entry.fold() : null, label.targetStartMs(), label.shiftMs(),
                label.leadingIsland(), label.credits(), label.film(), label.leftOut(), label.excluded(),
                label.excluded() == ExcludeReason.OWNER_EXCLUDED ? ownerReasons.get(row.mediaFileId()) : null));
        }

        String date = options.date().toString();
        Files.createDirectories(options.out());
        Path labelsFile = options.out().resolve("labels-" + date + ".json");
        Path splitFile = options.out().resolve("split-" + date + ".json");
        Path reportFile = options.out().resolve("labels-" + date + "-report.txt");
        Files.writeString(labelsFile, lines(records), StandardCharsets.UTF_8);
        Files.writeString(splitFile, "{\n\"date\": " + MAPPER.writeValueAsString(date) + ",\n\"films\": "
            + lines(new ArrayList<>(split.entries().values())) + ",\n\"looks\": " + MAPPER.writeValueAsString(looks)
            + "\n}\n", StandardCharsets.UTF_8);
        Files.writeString(reportFile, LabelsReport.render(options.date(), records, archive, split, preseed.size(),
            ownerReasons), StandardCharsets.UTF_8);
        return List.of(labelsFile, splitFile, reportFile);
    }

    /** Rejects a previous manifest that is not a well-formed split, so a bad file can never be carried over. */
    static void validate(SplitManifest manifest) {
        if (manifest == null || manifest.films() == null) {
            throw new IllegalArgumentException("previous split has no films list");
        }
        Set<Long> seen = new HashSet<>();
        for (ManifestEntry entry : manifest.films()) {
            if (entry == null) {
                throw new IllegalArgumentException("previous split has a null film entry");
            }
            if (entry.unitKey() == null || entry.side() == null) {
                throw new IllegalArgumentException("previous split film " + entry.filmId()
                    + " needs a unit_key and a side");
            }
            Integer fold = entry.fold();
            boolean foldOk = entry.side() == Side.DEV ? fold != null && fold >= 0 && fold < CreditsSplit.FOLDS
                : fold == null;
            if (!foldOk) {
                throw new IllegalArgumentException("previous split film " + entry.filmId()
                    + " has a fold that does not fit its side (dev 0-4, test none)");
            }
            if (!seen.add(entry.filmId())) {
                throw new IllegalArgumentException("previous split repeats film_id " + entry.filmId());
            }
        }
    }

    /** A JSON array with one element per line, so a diff of two runs reads line by line. */
    private static String lines(List<?> items) {
        if (items.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (Object item : items) {
            parts.add(MAPPER.writeValueAsString(item));
        }
        return "[\n" + String.join(",\n", parts) + "\n]";
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The owner's exclusions by media file id, in id order; empty when the flag is absent, or the file is missing. */
    static Map<Long, String> exclusions(Path file) throws IOException {
        Map<Long, String> reasons = new TreeMap<>();
        List<List<String>> table = file == null || !Files.exists(file) ? List.of() : CsvReader.parse(read(file));
        if (!table.isEmpty() && !table.get(0).equals(EXCLUSION_HEADER)) {
            throw new IllegalArgumentException("exclusions header must be " + String.join(",", EXCLUSION_HEADER));
        }
        for (List<String> fields : table.stream().skip(1).toList()) {
            if (fields.size() != 2) {
                throw new IllegalArgumentException("exclusions row needs " + EXCLUSION_HEADER.size() + " fields");
            }
            try {
                reasons.put(Long.parseLong(fields.get(0)), fields.get(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("exclusions media_file_id is not a number: " + fields.get(0), e);
            }
        }
        return reasons;
    }

    /** The pre-seed film ids, one per line; blank lines are skipped. */
    static Set<Long> preseed(String text) {
        Set<Long> ids = new HashSet<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                try {
                    ids.add(Long.parseLong(line.strip()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("pre-seed line is not a film id: " + line.strip(), e);
                }
            }
        }
        return ids;
    }
}
