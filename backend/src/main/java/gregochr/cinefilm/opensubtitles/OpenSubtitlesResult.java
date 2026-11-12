package gregochr.cinefilm.opensubtitles;

import java.util.LinkedHashMap;
import java.util.Map;
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
 */
public record OpenSubtitlesResult(long id, long fileId, String release, Double fps, int downloadCount,
                                  boolean fromTrusted, boolean hearingImpaired, String uploadDate,
                                  String uploaderRank, Integer fileCount) {

    /** A single-file result (the usual kind). */
    public OpenSubtitlesResult(long id, long fileId, String release, Double fps, int downloadCount,
                               boolean fromTrusted, boolean hearingImpaired, String uploadDate,
                               String uploaderRank) {
        this(id, fileId, release, fps, downloadCount, fromTrusted, hearingImpaired, uploadDate, uploaderRank, 1);
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
        JsonNode id = root.path("id");
        JsonNode fileId = root.path("fileId");
        if (!id.isIntegralNumber() || id.asLong() <= 0 || !fileId.isIntegralNumber() || fileId.asLong() <= 0) {
            return null;
        }
        JsonNode release = root.path("release");
        JsonNode fps = root.path("fps");
        return new OpenSubtitlesResult(id.asLong(), fileId.asLong(), release.isString() ? release.asString() : null,
            fps.isNumber() ? fps.asDouble() : null, root.path("downloadCount").asInt(0),
            root.path("fromTrusted").asBoolean(false), root.path("hearingImpaired").asBoolean(false), null, null,
            fileCountOf(root.path("fileCount")));
    }

    /** The stored count clamped to 1 to 1,000; null when there is none (legacy JSON) or it is not a whole number. */
    private static Integer fileCountOf(JsonNode node) {
        return node.isIntegralNumber() ? (int) Math.max(1, Math.min(1000, node.asLong())) : null;
    }

    /**
     * The JSON kept on the film: {@code {id, fileId, release, fps, downloadCount, fromTrusted, hearingImpaired}}.
     * At most a few hundred characters over the 200 of the release, so it fits the 2,000-character column.
     */
    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("fileId", fileId);
        json.put("release", release);
        json.put("fps", fps);
        json.put("downloadCount", downloadCount);
        json.put("fromTrusted", fromTrusted);
        json.put("hearingImpaired", hearingImpaired);
        json.put("fileCount", fileCount);
        return JSON.writeValueAsString(json);
    }
}
