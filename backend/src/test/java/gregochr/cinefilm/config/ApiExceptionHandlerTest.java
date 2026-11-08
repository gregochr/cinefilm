package gregochr.cinefilm.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void notFoundIs404WithTheMessageAsTheError() {
        ResponseEntity<Map<String, String>> response = handler.notFound(new NotFoundException("No such film"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsExactly(Map.entry("error", "No such film"));
    }

    @Test
    void badRequestIs400WithTheMessageAsTheError() {
        ResponseEntity<Map<String, String>> response = handler.badRequest(new BadRequestException("bad sort"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsExactly(Map.entry("error", "bad sort"));
    }

    @Test
    void anUnreadableBodyIs400WithAGenericMessageThatLeaksNothing() {
        ResponseEntity<Map<String, String>> response = handler.unreadable(new HttpMessageNotReadableException(
            "JSON parse error: Unrecognized token 'secret'", new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsExactly(Map.entry("error", "Invalid request"));
    }

    @Test
    void aMistypedPathOrQueryValueIs400WithTheSameGenericMessage() {
        ResponseEntity<Map<String, String>> response = handler.unreadable(
            new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, new NumberFormatException()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsExactly(Map.entry("error", "Invalid request"));
    }

    @Test
    void conflictIs409WithTheMessageAsTheError() {
        ResponseEntity<Map<String, String>> response = handler.conflict(
            new ConflictException("A scan is already running"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsExactly(Map.entry("error", "A scan is already running"));
    }
}
