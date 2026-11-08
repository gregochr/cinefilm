package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.auth.SessionController.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** The status rule in isolation: which authentication states count as signed in. */
class SessionControllerTest {

    private final SessionController controller = new SessionController(new PasswordChecker("sesame"));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ResponseEntity<Status> statusFor(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return controller.status();
    }

    @Test
    void noAuthenticationAtAllIsSignedOut() {
        ResponseEntity<Status> response = statusFor(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(new Status(false));
    }

    @Test
    void anAuthenticationThatIsNotMarkedAuthenticatedIsSignedOut() {
        Authentication pending = new TestingAuthenticationToken("owner", "pw");
        assertThat(pending.isAuthenticated()).isFalse();

        assertThat(statusFor(pending).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theAnonymousTokenIsSignedOutEvenThoughItIsMarkedAuthenticated() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(anonymous.isAuthenticated()).isTrue();

        ResponseEntity<Status> response = statusFor(anonymous);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(new Status(false));
    }

    @Test
    void aProperlyAuthenticatedUserIsSignedIn() {
        Authentication owner = new TestingAuthenticationToken("owner", "pw",
            AuthorityUtils.createAuthorityList("ROLE_OWNER"));
        assertThat(owner.isAuthenticated()).isTrue();

        ResponseEntity<Status> response = statusFor(owner);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new Status(true));
    }
}
