package gregochr.cinefilm.config;

import java.util.List;

/** Thrown when a request body breaks one or more rules; mapped to a 400 with the field-level errors. */
public class FieldErrorsException extends RuntimeException {

    private final transient List<FieldError> errors;

    /**
     * Creates the exception. Its message is the first error's message, which is what a client without field-level
     * handling shows.
     *
     * @param errors the broken rules, at least one
     */
    public FieldErrorsException(List<FieldError> errors) {
        super(errors.get(0).message());
        this.errors = List.copyOf(errors);
    }

    /** The broken rules in the order they were found. */
    public List<FieldError> errors() {
        return errors;
    }
}
