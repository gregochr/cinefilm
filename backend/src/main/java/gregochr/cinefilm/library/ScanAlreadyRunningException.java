package gregochr.cinefilm.library;

import gregochr.cinefilm.config.ConflictException;

/** A scan was requested while a scan or a Plex import is running; mapped to a 409. */
public class ScanAlreadyRunningException extends ConflictException {

    /** Creates the exception. */
    public ScanAlreadyRunningException() {
        super("A scan is already running");
    }

    /** Creates the exception with the reason (a Plex import holds the library lock). */
    public ScanAlreadyRunningException(String message) {
        super(message);
    }
}
