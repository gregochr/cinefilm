package gregochr.cinefilm.opensubtitles;

import java.util.LinkedHashMap;
import java.util.Map;
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
 */
public record OpenSubtitlesResult(long id, long fileId, String release, Double fps, int downloadCount,
                                  boolean fromTrusted, boolean hearingImpaired, String uploadDate,
                                  String uploaderRank) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        return JSON.writeValueAsString(json);
    }
}
