package gregochr.cinefilm.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence for a same-origin SPA: browsers will not add a custom header to a cross-site form post, so any
 * mutating request without {@code X-CineFilm: 1} is rejected with 403.
 */
public class CsrfHeaderFilter extends OncePerRequestFilter {

    /** Header the SPA sends on every mutating request. */
    public static final String HEADER = "X-CineFilm";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod()) || "1".equals(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"Missing X-CineFilm header\"}");
    }
}
