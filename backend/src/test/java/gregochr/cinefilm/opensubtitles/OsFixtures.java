package gregochr.cinefilm.opensubtitles;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Builders for recorded-shape OpenSubtitles replies; every field can be replaced by raw JSON or removed. */
final class OsFixtures {

    /** The film the fixtures are about: tt0936501 (Taken). */
    static final long TAKEN = 936501L;

    private OsFixtures() {
    }

    /** A reply page with the given result items. */
    static String page(Object... items) {
        String data = java.util.Arrays.stream(items).map(Object::toString).collect(Collectors.joining(","));
        return "{\"total_count\":" + items.length + ",\"total_pages\":1,\"per_page\":50,\"page\":1,\"data\":[" + data
            + "]}";
    }

    /** A good result: English foreign parts only, human made, 23.976 fps, for Taken. */
    static Item item(long id) {
        return new Item(id);
    }

    /** One result, as JSON. */
    static final class Item {

        private String id;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Item(long id) {
            this.id = "\"" + id + "\"";
            attributes.put("subtitle_id", "\"" + id + "\"");
            attributes.put("language", "\"en\"");
            attributes.put("foreign_parts_only", "true");
            attributes.put("hearing_impaired", "false");
            attributes.put("from_trusted", "false");
            attributes.put("machine_translated", "false");
            attributes.put("ai_translated", "false");
            attributes.put("fps", "23.976");
            attributes.put("download_count", "100");
            attributes.put("release", "\"Taken.2008.BluRay.x264\"");
            attributes.put("upload_date", "\"2020-01-02T03:04:05Z\"");
            attributes.put("uploader", "{\"uploader_id\":5,\"name\":\"someone\",\"rank\":\"Gold member\"}");
            attributes.put("feature_details", "{\"feature_id\":1,\"title\":\"Taken\",\"imdb_id\":" + TAKEN + "}");
            attributes.put("files", "[{\"file_id\":" + (id + 5000) + ",\"cd_number\":1,\"file_name\":\"a.srt\"}]");
        }

        /** Replaces the result's own {@code id} with raw JSON. */
        Item id(String rawJson) {
            this.id = rawJson;
            return this;
        }

        /** Removes the result's own {@code id}. */
        Item noId() {
            this.id = null;
            return this;
        }

        /** Replaces an attribute with raw JSON. */
        Item set(String name, String rawJson) {
            attributes.put(name, rawJson);
            return this;
        }

        /** Removes an attribute. */
        Item remove(String name) {
            attributes.remove(name);
            return this;
        }

        /** Sets {@code feature_details.imdb_id} to raw JSON. */
        Item imdb(String rawJson) {
            return set("feature_details", "{\"imdb_id\":" + rawJson + "}");
        }

        /** Sets the file id of the only file. */
        Item fileId(String rawJson) {
            return set("files", "[{\"file_id\":" + rawJson + "}]");
        }

        @Override
        public String toString() {
            String attrs = attributes.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(","));
            return "{" + (id == null ? "" : "\"id\":" + id + ",") + "\"type\":\"subtitle\",\"attributes\":{"
                + attrs + "}}";
        }
    }
}
