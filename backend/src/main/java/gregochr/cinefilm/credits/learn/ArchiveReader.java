package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the Plex archive directory: {@code section-<id>.json} (the list, to find items that were never fetched) and
 * {@code item-<ratingKey>.json} (one item with its markers and files). Only names matching exactly are parsed.
 */
final class ArchiveReader {

    private static final Pattern ITEM_FILE = Pattern.compile("item-(\\d+)\\.json");
    private static final Pattern SECTION_FILE = Pattern.compile("section-\\d+\\.json");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ArchiveReader() {
    }

    /**
     * Reads the archive.
     *
     * @param directory the archive directory
     * @return the items, the listed keys with no item file, and the unexpected {@code item-*} files
     * @throws IOException when the directory or a file cannot be read
     * @throws IllegalArgumentException when a file matching the patterns is not the JSON it should be
     */
    static PlexArchive read(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.sorted().toList();
        }
        Map<Long, ArchiveItem> items = new TreeMap<>();
        TreeSet<Long> listed = new TreeSet<>();
        List<String> unexpected = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            var item = ITEM_FILE.matcher(name);
            if (item.matches()) {
                long key = Long.parseLong(item.group(1));
                items.put(key, parseItem(key, name, Files.readString(file, StandardCharsets.UTF_8)));
            } else if (name.startsWith("item-")) {
                unexpected.add(name);
            } else if (SECTION_FILE.matcher(name).matches()) {
                listed.addAll(listedKeys(name, Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        listed.removeAll(items.keySet());
        return new PlexArchive(items, List.copyOf(listed), List.copyOf(unexpected));
    }

    /** The rating keys a section file lists. */
    static List<Long> listedKeys(String name, String json) {
        List<Long> keys = new ArrayList<>();
        for (JsonNode entry : tree(name, json).path("MediaContainer").path("Metadata")) {
            String key = entry.path("ratingKey").asString("");
            if (!key.matches("\\d{1,18}")) {
                throw new IllegalArgumentException(name + " lists an item with no numeric ratingKey");
            }
            keys.add(Long.parseLong(key));
        }
        return keys;
    }

    /** One item file's content. */
    static ArchiveItem parseItem(long key, String name, String json) {
        JsonNode item = tree(name, json).path("MediaContainer").path("Metadata").path(0);
        if (item.isMissingNode()) {
            throw new IllegalArgumentException(name + " has no item in MediaContainer.Metadata");
        }
        List<ArchiveItem.Media> media = new ArrayList<>();
        for (JsonNode version : item.path("Media")) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : version.path("Part")) {
                if (part.path("file").isString()) {
                    parts.add(part.path("file").asString());
                }
            }
            media.add(new ArchiveItem.Media(number(version, "duration"), List.copyOf(parts)));
        }
        List<ArchiveItem.Marker> markers = new ArrayList<>();
        for (JsonNode marker : item.path("Marker")) {
            markers.add(new ArchiveItem.Marker(marker.path("type").asString(""), number(marker, "startTimeOffset"),
                number(marker, "endTimeOffset"), marker.path("final").asBoolean(false)));
        }
        return new ArchiveItem(key, number(item, "duration"), List.copyOf(media), List.copyOf(markers));
    }

    private static JsonNode tree(String name, String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(name + " is not valid JSON", e);
        }
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}
