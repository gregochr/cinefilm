package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FrameLabelRecordTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void theJsonHasLabelRecordsFieldsInOrderThenTheTwoFlags() {
        FrameLabelRecord record = new FrameLabelRecord(7, "100:200", "unit-7", Side.DEV, 2, 100_000L, 0L, false,
            List.of(new Span(130_000, 295_000)), List.of(new Span(0, 95_000)),
            List.of(new LeftOut(105_000, 130_000, LeftOutReason.LIT_OPENING),
                new LeftOut(260_000, 295_000, LeftOutReason.LIT_RUN)), null, null, FrameKind.LIT, true);

        assertThat(MAPPER.writeValueAsString(record)).isEqualTo(
            "{\"media_file_id\":7,\"fingerprint\":\"100:200\",\"unit_key\":\"unit-7\",\"side\":\"dev\",\"fold\":2,"
                + "\"target_start_ms\":100000,\"shift_ms\":0,\"leading_island\":false,"
                + "\"credits\":[[130000,295000]],\"film\":[[0,95000]],"
                + "\"left_out\":[{\"from_ms\":105000,\"to_ms\":130000,\"reason\":\"LIT_OPENING\"},"
                + "{\"from_ms\":260000,\"to_ms\":295000,\"reason\":\"LIT_RUN\"}],"
                + "\"excluded\":null,\"excluded_note\":null,\"opening_type\":\"LIT\",\"lit_opening\":true}");
    }

    @Test
    void anExcludedRecordWritesNullFlagsAndEmptyLists() {
        FrameLabelRecord record = new FrameLabelRecord(9, "1:2", "unit-9", Side.TEST, null, null, null, null,
            List.of(), List.of(), List.of(), ExcludeReason.NO_ARCHIVE_ITEM, null, null, null);

        assertThat(MAPPER.writeValueAsString(record)).isEqualTo(
            "{\"media_file_id\":9,\"fingerprint\":\"1:2\",\"unit_key\":\"unit-9\",\"side\":\"test\",\"fold\":null,"
                + "\"target_start_ms\":null,\"shift_ms\":null,\"leading_island\":null,\"credits\":[],\"film\":[],"
                + "\"left_out\":[],\"excluded\":\"NO_ARCHIVE_ITEM\",\"excluded_note\":null,"
                + "\"opening_type\":null,\"lit_opening\":null}");
    }

    @Test
    void aPlateAndADarkOpeningWriteTheirNames() {
        FrameLabelRecord plate = new FrameLabelRecord(1, "a", "u", Side.DEV, 0, 1L, 0L, false, List.of(), List.of(),
            List.of(), null, null, FrameKind.PLATE, false);
        FrameLabelRecord dark = new FrameLabelRecord(1, "a", "u", Side.DEV, 0, 1L, 0L, false, List.of(), List.of(),
            List.of(), null, null, FrameKind.DARK, false);

        assertThat(MAPPER.writeValueAsString(plate)).endsWith("\"opening_type\":\"PLATE\",\"lit_opening\":false}");
        assertThat(MAPPER.writeValueAsString(dark)).endsWith("\"opening_type\":\"DARK\",\"lit_opening\":false}");
    }

    @Test
    void theLabelRecordWriterStillWritesNoOpeningFields() {
        LabelRecord record = new LabelRecord(7, "100:200", "unit-7", Side.DEV, 2, 100_000L, 0L, false, List.of(),
            List.of(), List.of(new LeftOut(1, 2, LeftOutReason.BOUNDARY)), null, null);

        assertThat(MAPPER.writeValueAsString(record)).doesNotContain("opening_type").doesNotContain("lit_opening")
            .endsWith("\"excluded\":null,\"excluded_note\":null}");
    }
}
