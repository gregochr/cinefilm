package gregochr.cinefilm.auth;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Holds the single password and compares candidates in constant time. */
@Component
public class PasswordChecker {

    /** Development fallback used when CINEFILM_PASSWORD is not set. */
    static final String FALLBACK = "changeme";

    private static final Logger LOG = LoggerFactory.getLogger(PasswordChecker.class);

    private final String password;
    private final boolean fromFallback;

    public PasswordChecker(@Value("${cinefilm.password:}") String configured) {
        this.fromFallback = configured == null || configured.isBlank();
        this.password = fromFallback ? FALLBACK : configured;
    }

    @PostConstruct
    void warnIfFallback() {
        if (fromFallback) {
            LOG.warn("CINEFILM_PASSWORD is not set: using the insecure development password. "
                + "Set CINEFILM_PASSWORD before exposing CineFilm.");
        }
    }

    /** Whether the candidate equals the password; the comparison time does not depend on where they differ. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(candidate), sha256(password));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
