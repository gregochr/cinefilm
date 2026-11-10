package gregochr.cinefilm.config;

/**
 * One problem in a request body, tied to the part of the body it is about.
 *
 * @param field where the problem is, as a path into the request ({@code editions[1].name})
 * @param message what is wrong, in the words the owner sees
 */
public record FieldError(String field, String message) {
}
