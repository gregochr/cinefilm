package gregochr.cinefilm.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Sign in, sign out and auth status for the single user. */
@RestController
public class SessionController {

    /** Body of {@code POST /api/session}. */
    public record Login(String password) {
    }

    /** Auth status. */
    public record Status(boolean authenticated) {
    }

    private final PasswordChecker passwords;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public SessionController(PasswordChecker passwords) {
        this.passwords = passwords;
    }

    @GetMapping("/api/session")
    ResponseEntity<Status> status() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        return ResponseEntity.status(signedIn ? HttpStatus.OK : HttpStatus.UNAUTHORIZED).body(new Status(signedIn));
    }

    @PostMapping("/api/session")
    ResponseEntity<Status> signIn(@RequestBody Login body, HttpServletRequest request,
                                  HttpServletResponse response) {
        if (!passwords.matches(body.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Status(false));
        }
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "owner", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.ok(new Status(true));
    }

    @DeleteMapping("/api/session")
    Status signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return new Status(false);
    }
}
