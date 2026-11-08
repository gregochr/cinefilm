package gregochr.cinefilm.library;

import gregochr.cinefilm.config.ConflictException;

/** A scan was requested while one is already running; mapped to a 409. */
public class ScanAlreadyRunningException extends ConflictException {

    /** Creates the exception. */
    public ScanAlreadyRunningException() {
        super("A scan is already running");
    }
}
