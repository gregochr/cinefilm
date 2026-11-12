package gregochr.cinefilm.opensubtitles;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One English foreign-parts-only subtitle OpenSubtitles offers for a film. Everything came from an untrusted reply:
 * the text fields are cut and cleaned by the parser and are only ever stored and shown as text.
 *
 * @param id OpenSubtitles' id of the subtitle
 * @param fileId the file id {@code /download} takes
 * @param release the release name it was made for, cut to 200 characters, or null
 * @param fps the frame rate it was timed for, or null when not stated
 * @param downloadCount how many times it was downloaded
 * @param fromTrusted true when a trusted uploader made it
 * @param hearingImpaired true when it is a hearing-impaired version
 * @param uploadDate the upload date as OpenSubtitles wrote it, or null
 * @param uploaderRank the uploader's rank as OpenSubtitles wrote it, or null
 * @param fileCount how many files (discs) the subtitle is split over: the larger of {@code nb_cd} and the number
 *     of files listed, at least 1; above 1 it is only part of the film ({@code fileId} is the first file); null when
 *     unknown (a result stored before the count was kept)
 * @param fileName the first file's name, cut to 200 characters, or null (read for the cut it says, as the release is)
 */
public record OpenSubtitlesResult(long id, long fileId, String release, Double fps, int downloadCount,
                                  boolean fromTrusted, boolean hearingImpaired, String uploadDate,
                                  String uploaderRank, Integer fileCount, String fileName) {

    /** A result with no file name. */
    public OpenSubtitlesResult(long id, long fileId, String release, Double fps, int downloadCount,
                               boolean fromTrusted, boolean hearingImpaired, String uploadDate,
                               String uploaderRank, Integer fileCount) {
        this(id, fileId, release, fps, downloadCount, fromTrusted, hearingImpaired, uploadDate, uploaderRank,
            fileCount, null);
    }

    /** A single-file result (the usual kind). */
    public OpenSubtitlesResult(long id, long fileId, String release, Double fps, int downloadCount,
                               boolean fromTrusted, boolean hearingImpaired, String uploadDate,
                               String uploaderRank) {
        this(id, fileId, release, fps, downloadCount, fromTrusted, hearingImpaired, uploadDate, uploaderRank, 1, null);
    }

    /** The cut tags in the release name and the file name. */
    public Set<CutTag> cutTags() {
        Set<CutTag> tags = EnumSet.noneOf(CutTag.class);
        tags.addAll(CutTag.in(release));
        tags.addAll(CutTag.in(fileName));
        return tags;
    }

    /** True when the subtitle is split over several files, so one file is only part of the film. */
    public boolean isMultiFile() {
        return fileCount != null && fileCount > 1;
    }

    /** True only when the count is known and is 1: the one kind of result that is a whole film. */
    public boolean isSingleFile() {
        return fileCount != null && fileCount == 1;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Reads back what {@link #toJson} wrote (the film's {@code os_best}). Only the id, the file id and the release are
     * needed by the readers; the rest is restored when present.
     *
     * @param json the stored JSON
     * @return the result, or null when the text is not JSON or has no positive {@code id} and {@code fileId}
     */
    public static OpenSubtitlesResult fromJson(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
        return fromNode(root);
    }

    /**
     * Reads back what {@link #listToJson} wrote (the film's {@code os_candidates}).
     *
     * @param json the stored JSON array
     * @return the results in stored order, leaving out elements that are not readable; an empty list when the text is
     *     not a JSON array
     */
    public static List<OpenSubtitlesResult> listFromJson(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }
        List<OpenSubtitlesResult> results = new ArrayList<>();
        for (JsonNode node : root) {
            OpenSubtitlesResult result = fromNode(node);
            if (result != null) {
                results.add(result);
            }
        }
        return List.copyOf(results);
    }

    /** The JSON array kept on the film as {@code os_candidates}: each element as {@link #toJson} writes it. */
    public static String listToJson(List<OpenSubtitlesResult> results) {
        List<Map<String, Object>> elements = new ArrayList<>();
        for (OpenSubtitlesResult result : results) {
            elements.add(result.toMap());
        }
        return JSON.writeValueAsString(elements);
    }

    private static OpenSubtitlesResult fromNode(JsonNode root) {
        JsonNode id = root.path("id");
        JsonNode fileId = root.path("fileId");
        if (!id.isIntegralNumber() || id.asLong() <= 0 || !fileId.isIntegralNumber() || fileId.asLong() <= 0) {
            return null;
        }
        JsonNode release = root.path("release");
        JsonNode fps = root.path("fps");
        JsonNode fileName = root.path("fileName");
        return new OpenSubtitlesResult(id.asLong(), fileId.asLong(), release.isString() ? release.asString() : null,
            fps.isNumber() ? fps.asDouble() : null, root.path("downloadCount").asInt(0),
            root.path("fromTrusted").asBoolean(false), root.path("hearingImpaired").asBoolean(false), null, null,
            fileCountOf(root.path("fileCount")), fileName.isString() ? fileName.asString() : null);
    }

    /** The stored count clamped to 1 to 1,000; null when there is none (legacy JSON) or it is not a whole number. */
    private static Integer fileCountOf(JsonNode node) {
        return node.isIntegralNumber() ? (int) Math.max(1, Math.min(1000, node.asLong())) : null;
    }

    /**
     * The JSON kept on the film: {@code {id, fileId, release, fps, downloadCount, fromTrusted, hearingImpaired,
     * fileCount}} and, when there is one, {@code fileName}.
     * At most a few hundred characters over the 200 of the release and 200 of the file name, so it fits the
     * 2,000-character column.
     */
    public String toJson() {
        return JSON.writeValueAsString(toMap());
    }

    private Map<String, Object> toMap() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("fileId", fileId);
        json.put("release", release);
        json.put("fps", fps);
        json.put("downloadCount", downloadCount);
        json.put("fromTrusted", fromTrusted);
        json.put("hearingImpaired", hearingImpaired);
        json.put("fileCount", fileCount);
        if (fileName != null) {
            json.put("fileName", fileName);
        }
        return json;
    }
}
