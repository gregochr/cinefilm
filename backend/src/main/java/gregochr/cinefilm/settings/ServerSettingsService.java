package gregochr.cinefilm.settings;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.film.BrowseRules;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.settings.SettingsDtos.Settings;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes the server settings. Nothing is cached: a change applies to the next request. */
@Service
public class ServerSettingsService {

    /** Minutes watched before a film counts as in progress, until the owner changes it. */
    public static final int DEFAULT_MIN_MINUTES = 5;

    /** Smallest minimum watch time, in minutes (0 means any position above 0 counts). */
    public static final int MIN_MINUTES = 0;

    /** Largest minimum watch time, in minutes. */
    public static final int MAX_MINUTES = 30;

    private final ServerSettingRepository settings;
    private final LibraryLoader loader;

    /** Creates the service over the settings row and the library loader. */
    public ServerSettingsService(ServerSettingRepository settings, LibraryLoader loader) {
        this.settings = settings;
        this.loader = loader;
    }

    /** The minimum watch time before a film counts as in progress, in milliseconds. */
    @Transactional(readOnly = true)
    public long minWatchMs() {
        return toMs(row().getContinueWatchingMinMinutes());
    }

    /** The settings with the number of films in progress under them. */
    @Transactional(readOnly = true)
    public Settings current() {
        return view(row());
    }

    /**
     * Sets the minimum watch time and returns the settings with the recomputed count. Two overlapping updates are
     * each one statement on the single row: the later one wins.
     *
     * @throws BadRequestException when {@code minutes} is null or outside 0 to 30
     */
    @Transactional
    public Settings update(Integer minutes) {
        if (minutes == null || minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new BadRequestException(
                "continueWatchingMinMinutes must be a whole number from " + MIN_MINUTES + " to " + MAX_MINUTES);
        }
        ServerSetting row = row();
        row.setContinueWatchingMinMinutes(minutes);
        return view(settings.save(row));
    }

    private Settings view(ServerSetting row) {
        long minMs = toMs(row.getContinueWatchingMinMinutes());
        int count = (int) loader.loadAll().stream().filter(v -> BrowseRules.inProgress(v, minMs)).count();
        return new Settings(row.getContinueWatchingMinMinutes(), count);
    }

    private static long toMs(int minutes) {
        return Duration.ofMinutes(minutes).toMillis();
    }

    private ServerSetting row() {
        return settings.findById(ServerSetting.ONLY_ROW)
            .orElseThrow(() -> new IllegalStateException("The server_setting row is missing"));
    }
}
