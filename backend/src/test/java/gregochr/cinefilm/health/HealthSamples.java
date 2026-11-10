package gregochr.cinefilm.health;

import gregochr.cinefilm.health.HealthDtos.HealthReport;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The plan's example report, as text and as an editable JSON tree, for the health tests. */
public final class HealthSamples {

    /** A plain mapper: the unknown-fields behaviour must come from the records, not from Spring's configuration. */
    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** The example report of the playback health plan. */
    public static final String EXAMPLE = """
        {
          "seq": 4,
          "editionId": 11,
          "elapsedMs": 185000,
          "environment": "train",
          "operator": "AS5089 Virgin Media Limited",
          "latencyMs": 48,
          "engine": "hlsjs",
          "quality": "auto",
          "autoplayRefused": false,
          "startupMs": 2380,
          "watchedMs": 171000,
          "stallCount": 2,
          "stallMs": 9400,
          "longestStallMs": 6100,
          "seekCount": 1,
          "seekWaitMs": 1800,
          "reloadCount": 0,
          "reloadMs": 0,
          "reconnectCount": 1,
          "reconnectMs": 12000,
          "recoveries": 0,
          "fellBack": false,
          "stepsDown": 1,
          "stepsUp": 0,
          "netErrors": 3,
          "timeouts": 1,
          "kbpsLow": 420,
          "kbpsMedian": 910,
          "kbpsHigh": 2400,
          "kbpsSamples": 41,
          "rungMs": { "480p": 58000, "360p": 113000 },
          "minutes": [[0, 900, 4, 58000, 0], [1, 0, 5, 41600, 9400], [2, 760, 5, 60000, 0], [3, 800, 5, 11400, 0]],
          "client": { "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", "screen": "1512x982", "cores": 8 }
        }
        """;

    private HealthSamples() {
    }

    /** A fresh, editable copy of the example report. */
    public static ObjectNode example() {
        return (ObjectNode) JSON.readTree(EXAMPLE);
    }

    /** The example with another edition id and sequence number. */
    public static ObjectNode example(long editionId, long seq) {
        ObjectNode node = example();
        node.put("editionId", editionId);
        node.put("seq", seq);
        return node;
    }

    /** The minutes array of a tree. */
    public static ArrayNode minutes(ObjectNode node) {
        return (ArrayNode) node.get("minutes");
    }

    /** The client object of a tree. */
    public static ObjectNode client(ObjectNode node) {
        return (ObjectNode) node.get("client");
    }

    /** The text of a tree. */
    public static String text(ObjectNode node) {
        return JSON.writeValueAsString(node);
    }

    /** A tree read as a typed report. */
    public static HealthReport report(ObjectNode node) {
        return JSON.readValue(text(node), HealthReport.class);
    }

    /** One minute entry of five numbers: a high figure everywhere, so a track of them is as long as it can be. */
    public static ArrayNode fullMinute() {
        ArrayNode minute = JSON.createArrayNode();
        minute.add(2_000_000_000L).add(2_000_000_000L).add(6L).add(2_000_000_000L).add(2_000_000_000L);
        return minute;
    }
}
