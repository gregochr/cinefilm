package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Extracts an embedded text subtitle stream to WebVTT with {@code ffmpeg} on first request and caches it. Only a
 * media file and stream index that exist in the database are accepted, and the source path always comes from the
 * database resolved under the library root (SECURITY.md rules 3 and 4).
 */
@Service
public class SubtitleService {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleService.class);

    /** How long ffmpeg may run before it is killed. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final MediaFileRepository files;
    private final LibraryRoot root;
    private final CommandRunner runner;
    private final TransactionTemplate transactions;
    private final String ffmpeg;
    private final Path cacheDir;

    /** Creates the service; the cache folder is {@code cinefilm.cache.dir}, ffmpeg is {@code cinefilm.tools.ffmpeg}. */
    public SubtitleService(MediaFileRepository files, LibraryRoot root, CommandRunner runner,
                           TransactionTemplate transactions, @Value("${cinefilm.tools.ffmpeg:ffmpeg}") String ffmpeg,
                           @Value("${cinefilm.cache.dir}") String cacheDir) {
        this.files = files;
        this.root = root;
        this.runner = runner;
        this.transactions = transactions;
        this.ffmpeg = ffmpeg;
        this.cacheDir = Path.of(cacheDir).toAbsolutePath().normalize().resolve("subtitles");
    }

    /** What the extraction needs from the database. */
    private record Source(String path, long size, long modified) {
    }

    /**
     * The exact ffmpeg command: the source and output are separate elements, the stream number is an integer.
     *
     * @param binary the ffmpeg executable
     * @param source the media file
     * @param streamIndex the stream index in the file
     * @param output the WebVTT file to write
     * @return the argument list
     */
    static List<String> command(String binary, Path source, int streamIndex, Path output) {
        return List.of(binary, "-v", "error", "-nostdin", "-y", "-i", source.toString(), "-map", "0:" + streamIndex,
            "-f", "webvtt", output.toString());
    }

    /**
     * The WebVTT file for a subtitle stream, extracting it first if it is not cached.
     *
     * @param mediaFileId the media file id
     * @param streamIndex the stream index (as stored, the ffprobe index)
     * @return the cached file
     * @throws NotFoundException when the file or stream is unknown, the file is gone, or extraction fails
     */
    public Path vtt(Long mediaFileId, int streamIndex) {
        Source source = transactions.execute(status -> lookup(mediaFileId, streamIndex));
        Path cached = cacheDir.resolve(mediaFileId + "-" + streamIndex + "-" + source.size() + "-"
            + source.modified() + ".vtt");
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        Path input = root.resolveFile(source.path())
            .orElseThrow(() -> new NotFoundException("Media file is not available"));
        Path temporary = cacheDir.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(cacheDir);
            CommandRunner.Result result = runner.run(command(ffmpeg, input, streamIndex, temporary), TIMEOUT);
            if (!result.succeeded() || !Files.isRegularFile(temporary)) {
                LOG.warn("ffmpeg could not extract subtitles for media file {} stream {} (exit {}, timed out {})",
                    mediaFileId, streamIndex, result.exitCode(), result.timedOut());
                throw new NotFoundException("Subtitles could not be extracted");
            }
            Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            return cached;
        } catch (IOException e) {
            LOG.warn("Subtitle extraction failed for media file {} stream {}: {}", mediaFileId, streamIndex,
                e.getMessage());
            throw new NotFoundException("Subtitles could not be extracted");
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                LOG.debug("Could not remove temporary file {}", temporary);
            }
        }
    }

    private Source lookup(Long mediaFileId, int streamIndex) {
        MediaFile file = files.findById(mediaFileId).orElseThrow(() -> new NotFoundException("No such media file"));
        Optional<SubtitleStream> stream = file.getSubtitleStreams().stream()
            .filter(s -> s.getIndex() == streamIndex).findFirst();
        if (stream.isEmpty()) {
            throw new NotFoundException("No such subtitle stream");
        }
        return new Source(file.getPath(), file.getSizeBytes() == null ? 0 : file.getSizeBytes(),
            file.getModifiedAt() == null ? 0 : file.getModifiedAt().toEpochMilli());
    }
}
