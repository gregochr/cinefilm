package gregochr.cinefilm.credits.learn;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The plain-text report of a labels run: counts only, in a fixed order, so the same inputs give the same text. */
final class LabelsReport {

    private LabelsReport() {
    }

    /**
     * Renders the report.
     *
     * @param date the run date
     * @param records every file's record, by media file id
     * @param archive the archive as read
     * @param split what the split decided
     * @param preseedCount the number of film ids in the pre-seed file
     * @param ownerReasons the exclusions file's reasons by media file id, in id order
     * @return the text, one line per figure, ending with a newline
     */
    static String render(LocalDate date, List<LabelRecord> records, PlexArchive archive, CreditsSplit.Result split,
                         int preseedCount, Map<Long, String> ownerReasons) {
        Map<ExcludeReason, Integer> reasons = new EnumMap<>(ExcludeReason.class);
        for (ExcludeReason reason : ExcludeReason.values()) {
            reasons.put(reason, 0);
        }
        int dev = 0;
        int test = 0;
        int labelledDev = 0;
        int labelledTest = 0;
        int islands = 0;
        for (LabelRecord record : records) {
            boolean labelled = record.excluded() == null;
            if (!labelled) {
                reasons.merge(record.excluded(), 1, Integer::sum);
            }
            if (record.side() == Side.DEV) {
                dev++;
                labelledDev += labelled ? 1 : 0;
            } else {
                test++;
                labelledTest += labelled ? 1 : 0;
            }
            if (Boolean.TRUE.equals(record.leadingIsland())) {
                islands++;
            }
        }
        long units = records.stream().map(LabelRecord::unitKey).distinct().count();
        StringBuilder out = new StringBuilder();
        line(out, "CineFilm credits labels, run date " + date);
        line(out, "files: " + records.size());
        line(out, "units: " + units);
        line(out, "files by side, all exported: dev " + dev + ", test " + test);
        line(out, "labelled files: " + (labelledDev + labelledTest) + " (dev " + labelledDev + ", test "
            + labelledTest + ")");
        line(out, "excluded files: " + reasons.values().stream().mapToInt(Integer::intValue).sum());
        reasons.forEach((reason, count) -> line(out, "  " + reason + ": " + count));
        line(out, "leading_island files: " + islands);
        line(out, "archive items parsed: " + archive.items().size());
        line(out, "archive keys listed in a section with no item file, not parsed: "
            + archive.listedWithoutItem().size() + list(archive.listedWithoutItem()));
        line(out, "archive files named item-* that are not item-<digits>.json, not parsed: "
            + archive.unexpectedFiles().size() + list(archive.unexpectedFiles()));
        line(out, "pre-seed film ids: " + preseedCount + ", not in the export: " + split.preseedMissing().size()
            + list(split.preseedMissing()));
        line(out, "carry-over conflicts, previous manifest won over the pre-seed: " + split.preseedOverridden().size()
            + list(split.preseedOverridden()));
        line(out, "units split across sides: " + split.unitsSplit());
        line(out, "films kept on their old side after changing unit: " + split.keptOnOldSide().size()
            + list(split.keptOnOldSide()));
        line(out, "films excluded as SPLIT_CONFLICT: " + split.conflicts().size()
            + list(split.conflicts()));
        line(out, "owner exclusions: " + ownerReasons.size());
        ownerReasons.forEach((id, reason) -> line(out, "  " + id + ": " + reason));
        return out.toString();
    }

    private static String list(List<?> items) {
        String joined = items.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return items.isEmpty() ? "" : " [" + joined + "]";
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
