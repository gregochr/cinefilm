package gregochr.cinefilm.subtitle;

import java.util.List;

/** Request and response shapes of the Admin picker's OpenSubtitles routes. */
public final class EditionOpenSubtitlesDtos {

    private EditionOpenSubtitlesDtos() {
    }

    /**
     * One file a search found.
     *
     * @param fileId the OpenSubtitles file id, what the download route takes
     * @param release the release name it was made for, or null
     * @param fileName the file's name, or null
     * @param fps the frame rate it was timed for, or null
     * @param downloadCount how many times it was downloaded
     * @param fromTrusted true when a trusted uploader made it
     * @param hearingImpaired true for a hearing-impaired version
     * @param uploadDate the upload date as OpenSubtitles wrote it, or null
     */
    public record ResultDto(long fileId, String release, String fileName, Double fps, int downloadCount,
                            boolean fromTrusted, boolean hearingImpaired, String uploadDate) {
    }

    /**
     * A search's answer.
     *
     * @param editionId the edition
     * @param kind {@code full} or {@code forced}
     * @param imdbId the film's IMDb id that was searched
     * @param downloadsLeft how many more files the edition may be given of this kind
     * @param results the single-file results, best first for this edition's name
     */
    public record SearchDto(Long editionId, String kind, String imdbId, int downloadsLeft, List<ResultDto> results) {
    }

    /**
     * The body of a download.
     *
     * @param fileId a file id a search for this edition and kind returned
     * @param kind {@code full} or {@code forced}
     */
    public record DownloadRequest(Long fileId, String kind) {
    }

    /**
     * A download's answer.
     *
     * @param editionId the edition
     * @param kind {@code full} or {@code forced}
     * @param fileId the file that was downloaded
     * @param release its release name, or null
     * @param downloadsLeft how many more files the edition may be given of this kind
     */
    public record DownloadDto(Long editionId, String kind, long fileId, String release, int downloadsLeft) {
    }
}
