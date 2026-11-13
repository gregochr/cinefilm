package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LabelsReportTest {

    private static LabelRecord record(long id, String unit, Side side, Boolean island, ExcludeReason excluded) {
        return new LabelRecord(id, "f", unit, side, null, null, null, island, List.of(), List.of(), List.of(),
            excluded);
    }

    @Test
    void rendersEveryFigureInAFixedOrder() {
        List<LabelRecord> records = List.of(
            record(1, "tmdb:1", Side.DEV, true, null),
            record(2, "tmdb:1", Side.DEV, false, null),
            record(3, "tmdb:2", Side.TEST, false, null),
            record(4, "tmdb:3", Side.TEST, null, ExcludeReason.NO_ARCHIVE_ITEM),
            record(5, "tmdb:4", Side.DEV, null, ExcludeReason.NO_ARCHIVE_ITEM),
            record(6, "tmdb:5", Side.DEV, null, ExcludeReason.SPLIT_CONFLICT));
        PlexArchive archive = new PlexArchive(Map.of(1L, new ArchiveItem(1, 1L, List.of(), List.of())),
            List.of(8L, 12L), List.of("item-1.json.tmp"));
        CreditsSplit.Result split = new CreditsSplit.Result(Map.of(), List.of(4L), List.of(7L, 9L), List.of(6L), 2,
            List.of(99L));

        String text = LabelsReport.render(LocalDate.of(2026, 11, 13), records, archive, split, 3,
            new java.util.TreeMap<>(Map.of(40L, "wrong, see stills", 5L, "x")));

        assertThat(text).isEqualTo("""
            CineFilm credits labels, run date 2026-11-13
            files: 6
            units: 5
            files by side, all exported: dev 4, test 2
            labelled files: 3 (dev 2, test 1)
            excluded files: 3
              NOT_LAST_PART: 0
              NOT_PRESENT: 0
              FINGERPRINT_CHANGED: 0
              NO_ARCHIVE_ITEM: 2
              AMBIGUOUS_ARCHIVE_ITEM: 0
              NO_CREDITS_MARKER: 0
              MISSING_MARKER_END: 0
              SHIFT_MISMATCH: 0
              SHIFT_OUT_OF_RANGE: 0
              MARKER_BEFORE_WINDOW: 0
              OWNER_EXCLUDED: 0
              SPLIT_CONFLICT: 1
            leading_island files: 1
            archive items parsed: 1
            archive keys listed in a section with no item file, not parsed: 2 [8, 12]
            archive files named item-* that are not item-<digits>.json, not parsed: 1 [item-1.json.tmp]
            pre-seed film ids: 3, not in the export: 1 [99]
            carry-over conflicts, previous manifest won over the pre-seed: 2 [7, 9]
            units split across sides: 2
            films kept on their old side after changing unit: 1 [4]
            films excluded as SPLIT_CONFLICT: 1 [6]
            owner exclusions: 2
              5: x
              40: wrong, see stills
            """);
    }

    @Test
    void emptyListsPrintNoBrackets() {
        String text = LabelsReport.render(LocalDate.of(2026, 11, 13), List.of(), new PlexArchive(Map.of(), List.of(),
            List.of()), new CreditsSplit.Result(Map.of(), List.of(), List.of(), List.of(), 0, List.of()), 0,
            Map.of());

        assertThat(text).contains("files: 0\nunits: 0\n").contains("no item file, not parsed: 0\n")
            .contains("not in the export: 0\n").contains("owner exclusions: 0\n").doesNotContain("[");
    }
}
