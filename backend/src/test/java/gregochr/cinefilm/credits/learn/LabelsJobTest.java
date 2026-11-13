package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.plex.PlexRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LabelsJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path FIXTURES = LearnFixtures.dir();

    @TempDir
    Path temp;

    private LabelsOptions options(Path out, Path previous, Path exclusions) {
        return new LabelsOptions(FIXTURES.resolve("archive"), FIXTURES.resolve("export.csv"),
            FIXTURES.resolve("preseed.txt"), PlexRules.prefix(LearnFixtures.PREFIX), LocalDate.of(2026, 11, 13), out,
            previous, exclusions);
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static JsonNode labelOf(Path labels, long mediaFileId) throws IOException {
        for (JsonNode node : MAPPER.readTree(Files.readString(labels))) {
            if (node.path("media_file_id").asLong() == mediaFileId) {
                return node;
            }
        }
        throw new AssertionError("no record for " + mediaFileId);
    }

    @Test
    void writesTheThreeFilesNamedByTheDate() throws IOException {
        List<Path> written = LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));

        assertThat(written).containsExactly(temp.resolve("labels-2026-11-13.json"),
            temp.resolve("split-2026-11-13.json"), temp.resolve("labels-2026-11-13-report.txt"));
        assertThat(written).allSatisfy(path -> assertThat(path).isRegularFile());
    }

    @Test
    void createsTheOutputDirectory() throws IOException {
        Path out = temp.resolve("deeper/still");

        LabelsJob.run(options(out, null, null));

        assertThat(out.resolve("labels-2026-11-13.json")).isRegularFile();
    }

    @Test
    void aLabelledFileIsOneJsonLineWithTheExactSchema() throws IOException {
        LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));

        List<String> labels = lines(temp.resolve("labels-2026-11-13.json"));

        assertThat(labels).hasSize(21);
        assertThat(labels.get(0)).isEqualTo("[");
        assertThat(labels.get(20)).isEqualTo("]");
        assertThat(labels.get(1)).isEqualTo("{\"media_file_id\":101,\"fingerprint\":\"1001:1700000000000\","
            + "\"unit_key\":\"tmdb:1001\",\"side\":\"dev\",\"fold\":0,\"target_start_ms\":4800000,\"shift_ms\":0,"
            + "\"leading_island\":false,\"credits\":[[4805000,5395000]],\"film\":[[3900000,4795000]],"
            + "\"left_out\":[{\"from_ms\":4795000,\"to_ms\":4805000,\"reason\":\"BOUNDARY\"},"
            + "{\"from_ms\":5395000,\"to_ms\":5405000,\"reason\":\"BOUNDARY\"},"
            + "{\"from_ms\":5405000,\"to_ms\":6000000,\"reason\":\"AFTER_LAST_CREDITS\"}],\"excluded\":null},");
    }

    @Test
    void anExcludedFileKeepsItsSplitAndFingerprintAndHasEmptyListsAndNullFigures() throws IOException {
        LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));

        assertThat(lines(temp.resolve("labels-2026-11-13.json")).get(5)).isEqualTo(
            "{\"media_file_id\":105,\"fingerprint\":\"1005:1700000000000\",\"unit_key\":\"cid:8\",\"side\":\"dev\","
            + "\"fold\":4,\"target_start_ms\":null,\"shift_ms\":null,\"leading_island\":null,\"credits\":[],"
            + "\"film\":[],\"left_out\":[],\"excluded\":\"MISSING_MARKER_END\"},");
    }

    @Test
    void everyFixtureFileGetsTheExpectedExclusionOrLabel() throws IOException {
        LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));
        Path labels = temp.resolve("labels-2026-11-13.json");

        Map<Long, String> expected = Map.ofEntries(Map.entry(105L, "MISSING_MARKER_END"),
            Map.entry(106L, "NO_CREDITS_MARKER"), Map.entry(108L, "SHIFT_MISMATCH"),
            Map.entry(109L, "SHIFT_OUT_OF_RANGE"), Map.entry(110L, "FINGERPRINT_CHANGED"),
            Map.entry(111L, "NOT_LAST_PART"), Map.entry(112L, "NO_ARCHIVE_ITEM"),
            Map.entry(113L, "AMBIGUOUS_ARCHIVE_ITEM"), Map.entry(114L, "MARKER_BEFORE_WINDOW"),
            Map.entry(115L, "NOT_PRESENT"), Map.entry(116L, "OWNER_EXCLUDED"), Map.entry(119L, "NOT_LAST_PART"));
        for (Map.Entry<Long, String> e : expected.entrySet()) {
            assertThat(labelOf(labels, e.getKey()).path("excluded").asString()).as("file %d", e.getKey())
                .isEqualTo(e.getValue());
        }
        for (long labelled : List.of(101L, 102L, 103L, 104L, 107L, 117L, 118L)) {
            assertThat(labelOf(labels, labelled).path("excluded").isNull()).as("file %d", labelled).isTrue();
        }
    }

    @Test
    void theShiftNfcMergingAndWindowAreAppliedToFixtureFiles() throws IOException {
        LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));
        Path labels = temp.resolve("labels-2026-11-13.json");

        JsonNode eta = labelOf(labels, 107);
        assertThat(eta.path("shift_ms").asLong()).isEqualTo(2_000);
        assertThat(eta.path("target_start_ms").asLong()).isEqualTo(4_802_000);
        assertThat(eta.path("film").toString()).isEqualTo("[[3902000,4797000]]");
        assertThat(eta.path("credits").toString()).isEqualTo("[[4807000,5397000]]");
        JsonNode delta = labelOf(labels, 104);
        assertThat(delta.path("credits").toString()).isEqualTo("[[4805000,5295000]]");
        JsonNode gamma = labelOf(labels, 103);
        assertThat(gamma.path("leading_island").asBoolean()).isTrue();
        JsonNode rho = labelOf(labels, 117);
        assertThat(rho.path("credits").toString()).isEqualTo("[[5905000,6000000]]");
        assertThat(rho.path("side").asString()).isEqualTo("test");
        assertThat(rho.path("fold").isNull()).isTrue();
        JsonNode amelie = labelOf(labels, 118);
        assertThat(amelie.path("unit_key").asString()).isEqualTo("title:amélie|2001");
        assertThat(amelie.path("excluded").isNull()).isTrue();
    }

    @Test
    void theManifestListsEveryFilmByIdWithItsUnitSideAndFoldAndThePreseedWins() throws IOException {
        LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));

        List<String> split = lines(temp.resolve("split-2026-11-13.json"));

        assertThat(split.subList(0, 4)).containsExactly("{", "\"date\": \"2026-11-13\",", "\"films\": [",
            "{\"film_id\":1,\"unit_key\":\"tmdb:1001\",\"side\":\"dev\",\"fold\":0},");
        assertThat(split).contains("{\"film_id\":6,\"unit_key\":\"title:zetafilm|\",\"side\":\"test\",\"fold\":null},",
            "{\"film_id\":7,\"unit_key\":\"tmdb:1007\",\"side\":\"dev\",\"fold\":0},",
            "{\"film_id\":3,\"unit_key\":\"ctmdb:500\",\"side\":\"dev\",\"fold\":1},",
            "{\"film_id\":4,\"unit_key\":\"ctmdb:500\",\"side\":\"dev\",\"fold\":1},");
        assertThat(split.subList(split.size() - 4, split.size())).containsExactly("{\"film_id\":19,"
            + "\"unit_key\":\"tmdb:1019\",\"side\":\"dev\",\"fold\":1}", "],", "\"looks\": []", "}");
        JsonNode manifest = MAPPER.readTree(Files.readString(temp.resolve("split-2026-11-13.json")));
        assertThat(manifest.path("films")).hasSize(19);
        assertThat(manifest.path("looks")).isEmpty();
    }

    @Test
    void theReportCountsEverythingOnTheFirstRun() throws IOException {
        LabelsJob.run(options(temp, null, FIXTURES.resolve("exclusions.csv")));

        assertThat(Files.readString(temp.resolve("labels-2026-11-13-report.txt"))).isEqualTo("""
            CineFilm credits labels, run date 2026-11-13
            files: 19
            units: 18
            files by side, all exported: dev 17, test 2
            labelled files: 7 (dev 6, test 1)
            excluded files: 12
              NOT_LAST_PART: 2
              NOT_PRESENT: 1
              FINGERPRINT_CHANGED: 1
              NO_ARCHIVE_ITEM: 1
              AMBIGUOUS_ARCHIVE_ITEM: 1
              NO_CREDITS_MARKER: 1
              MISSING_MARKER_END: 1
              SHIFT_MISMATCH: 1
              SHIFT_OUT_OF_RANGE: 1
              MARKER_BEFORE_WINDOW: 1
              OWNER_EXCLUDED: 1
              SPLIT_CONFLICT: 0
            leading_island files: 1
            archive items parsed: 19
            archive keys listed in a section with no item file, not parsed: 1 [12]
            archive files named item-* that are not item-<digits>.json, not parsed: 2 [item-21.json.tmp, \
            item-notes.json]
            pre-seed film ids: 2, not in the export: 1 [999]
            carry-over conflicts, previous manifest won over the pre-seed: 0
            units split across sides: 0
            films kept on their old side after changing unit: 0
            films excluded as SPLIT_CONFLICT: 0
            owner exclusions: 1
              116: Plex marker is wrong: stills, not credits
            """);
    }

    @Test
    void aPreviousManifestCarriesSidesOverAndItsLooksAreCopiedUnchanged() throws IOException {
        LabelsJob.run(options(temp, FIXTURES.resolve("previous-split.json"), FIXTURES.resolve("exclusions.csv")));
        Path labels = temp.resolve("labels-2026-11-13.json");

        JsonNode alpha = labelOf(labels, 101);
        assertThat(alpha.path("side").asString()).isEqualTo("test");
        assertThat(alpha.path("fold").isNull()).isTrue();
        assertThat(labelOf(labels, 104).path("fold").asInt()).isEqualTo(4);
        JsonNode eta = labelOf(labels, 107);
        assertThat(eta.path("side").asString()).isEqualTo("test");
        JsonNode gamma = labelOf(labels, 103);
        assertThat(gamma.path("excluded").asString()).isEqualTo("SPLIT_CONFLICT");
        assertThat(gamma.path("side").asString()).isEqualTo("dev");
        assertThat(gamma.path("fold").asInt()).isEqualTo(1);
        assertThat(gamma.path("target_start_ms").isNull()).isTrue();
        assertThat(lines(temp.resolve("split-2026-11-13.json")).reversed().get(1))
            .isEqualTo("\"looks\": [{\"look\":\"L5\",\"date\":\"2026-11-02\"}]");
        String report = Files.readString(temp.resolve("labels-2026-11-13-report.txt"));
        assertThat(report).contains("  SPLIT_CONFLICT: 1\n")
            .contains("carry-over conflicts, previous manifest won over the pre-seed: 1 [7]\n")
            .contains("films kept on their old side after changing unit: 1 [4]\n")
            .contains("films excluded as SPLIT_CONFLICT: 1 [3]\n").contains("units split across sides: 0\n")
            .contains("labelled files: 6 (dev 3, test 3)\n").contains("leading_island files: 0\n");
    }

    @Test
    void aMissingOwnerExclusionsFileOrNoFlagMeansNoExclusions() throws IOException {
        LabelsJob.run(options(temp, null, temp.resolve("nothing.csv")));

        assertThat(labelOf(temp.resolve("labels-2026-11-13.json"), 116).path("excluded").isNull()).isTrue();
        assertThat(Files.readString(temp.resolve("labels-2026-11-13-report.txt"))).contains("owner exclusions: 0\n");
    }

    @Test
    void theSameInputsGiveByteIdenticalOutputs() throws IOException {
        Path first = temp.resolve("one");
        Path second = temp.resolve("two");

        LabelsJob.run(options(first, FIXTURES.resolve("previous-split.json"), FIXTURES.resolve("exclusions.csv")));
        LabelsJob.run(options(second, FIXTURES.resolve("previous-split.json"), FIXTURES.resolve("exclusions.csv")));

        for (String name : List.of("labels-2026-11-13.json", "split-2026-11-13.json",
            "labels-2026-11-13-report.txt")) {
            assertThat(Files.readAllBytes(second.resolve(name))).isEqualTo(Files.readAllBytes(first.resolve(name)));
        }
    }

    @Test
    void anUnreadableInputIsAnIoError() {
        LabelsOptions missing = new LabelsOptions(FIXTURES.resolve("archive"), FIXTURES.resolve("nope.csv"),
            FIXTURES.resolve("preseed.txt"), "/p/", LocalDate.of(2026, 11, 13), temp, null, null);
        LabelsOptions noPrevious = options(temp, temp.resolve("nope.json"), null);

        assertThatThrownBy(() -> LabelsJob.run(missing)).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> LabelsJob.run(noPrevious)).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void anExportWithNoRowsGivesEmptyArrays() throws IOException {
        Path export = temp.resolve("empty.csv");
        Files.writeString(export, String.join(",", ExportReader.COLUMNS) + "\n");
        LabelsOptions options = new LabelsOptions(FIXTURES.resolve("archive"), export, FIXTURES.resolve("preseed.txt"),
            "/p/", LocalDate.of(2026, 11, 13), temp.resolve("out"), null, null);

        LabelsJob.run(options);

        assertThat(Files.readString(temp.resolve("out/labels-2026-11-13.json"))).isEqualTo("[]");
        assertThat(Files.readString(temp.resolve("out/split-2026-11-13.json")))
            .isEqualTo("{\n\"date\": \"2026-11-13\",\n\"films\": [],\n\"looks\": []\n}\n");
    }

    @Test
    void aPreviousManifestWithoutLooksHasNone() throws IOException {
        Path previous = temp.resolve("previous.json");
        Files.writeString(previous, "{\"date\":\"2026-11-01\",\"films\":[]}");

        LabelsJob.run(options(temp.resolve("out"), previous, null));

        assertThat(lines(temp.resolve("out/split-2026-11-13.json")).reversed().get(1)).isEqualTo("\"looks\": []");
    }

    @Test
    void exclusionsAreReadInIdOrderWithQuotedReasons() throws IOException {
        Path file = temp.resolve("x.csv");
        Files.writeString(file, "media_file_id,reason\n9,\"a, b\"\n2,plain\n");

        assertThat(LabelsJob.exclusions(file)).containsExactly(Map.entry(2L, "plain"), Map.entry(9L, "a, b"));
    }

    @Test
    void anEmptyExclusionsFileIsFine() throws IOException {
        Path file = temp.resolve("x.csv");
        Files.writeString(file, "");

        assertThat(LabelsJob.exclusions(file)).isEmpty();
        assertThat(LabelsJob.exclusions(null)).isEmpty();
    }

    @Test
    void aBadExclusionsHeaderRowOrIdIsAnError() throws IOException {
        Path header = temp.resolve("h.csv");
        Files.writeString(header, "id,reason\n1,x\n");
        Path row = temp.resolve("r.csv");
        Files.writeString(row, "media_file_id,reason\n1,x,y\n");
        Path id = temp.resolve("i.csv");
        Files.writeString(id, "media_file_id,reason\none,x\n");

        assertThatThrownBy(() -> LabelsJob.exclusions(header)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("exclusions header must be media_file_id,reason");
        assertThatThrownBy(() -> LabelsJob.exclusions(row)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("exclusions row needs 2 fields");
        assertThatThrownBy(() -> LabelsJob.exclusions(id)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("exclusions media_file_id is not a number: one");
    }

    @Test
    void preseedReadsOneIdPerLineAndSkipsBlankLines() {
        assertThat(LabelsJob.preseed("7\r\n\n 12 \n  \n999")).isEqualTo(Set.of(7L, 12L, 999L));
        assertThat(LabelsJob.preseed("")).isEmpty();
    }

    @Test
    void aPreseedLineThatIsNotAnIdIsAnError() {
        assertThatThrownBy(() -> LabelsJob.preseed("7\nWicked\n")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("pre-seed line is not a film id: Wicked");
    }
}
