package gregochr.cinefilm.credits.learn;

/**
 * One file the credits dataset job reads: a media file whose Plex marker still describes the file on disk.
 *
 * @param mediaFileId the media file
 * @param path its path relative to the library root
 * @param fingerprint its size and modification time, equal to the marker's
 * @param durationMs its length on its timeline
 * @param targetStartMs where Plex says the credits start inside the file
 */
public record DatasetTarget(long mediaFileId, String path, String fingerprint, long durationMs, long targetStartMs) {
}
