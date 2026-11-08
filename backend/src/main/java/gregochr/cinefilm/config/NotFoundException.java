package gregochr.cinefilm.config;

/** Thrown when a requested resource does not exist; mapped to a 404 JSON response. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
