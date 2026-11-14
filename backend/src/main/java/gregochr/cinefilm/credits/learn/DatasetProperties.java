package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The settings of the credits dataset job ({@code cinefilm.credits.dataset.*}), with the paths made absolute.
 *
 * @param enabled whether the job is switched on
 * @param dir the dataset directory: frames go in {@code <dir>/frames/<mediaFileId>/}, the running marker in
 *     {@code <dir>/running}
 * @param outDir the directory the tool runs write their results to
 * @param nice the niceness every ffmpeg call of the job runs under
 * @param minFreeBytes the usable disk below which the job stops
 * @param ffmpeg the ffmpeg executable ({@code cinefilm.tools.ffmpeg})
 * @param readerDir the directory the frame reader keeps its jobs in, which it wipes at start-up
 */
@Component
public record DatasetProperties(boolean enabled, Path dir, Path outDir, int nice, long minFreeBytes, String ffmpeg,
                                Path readerDir) {

    /** One gigabyte, as the {@code min-free-gb} setting counts it. */
    static final long GB = 1_000_000_000L;

    /** Reads the settings. */
    @Autowired
    public DatasetProperties(@Value("${cinefilm.credits.dataset.enabled:false}") boolean enabled,
                             @Value("${cinefilm.credits.dataset.dir}") String dir,
                             @Value("${cinefilm.credits.dataset.out-dir}") String outDir,
                             @Value("${cinefilm.credits.dataset.nice:19}") int nice,
                             @Value("${cinefilm.credits.dataset.min-free-gb:15}") long minFreeGb,
                             @Value("${cinefilm.tools.ffmpeg:ffmpeg}") String ffmpeg,
                             @Value("${cinefilm.cache.dir}") String cacheDir) {
        this(enabled, absolute(dir), absolute(outDir), nice, minFreeGb * GB, ffmpeg,
            absolute(cacheDir).resolve("credits"));
    }

    private static Path absolute(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    /** Where each file's frames go. */
    public Path framesDir() {
        return dir.resolve("frames");
    }

    /** The marker whose presence makes the job start again after a restart. */
    public Path marker() {
        return dir.resolve("running");
    }

    /** True when the dataset directory is the frame reader's own, which is wiped at start-up. */
    public boolean overlapsReaderDir() {
        return dir.startsWith(readerDir);
    }
}
