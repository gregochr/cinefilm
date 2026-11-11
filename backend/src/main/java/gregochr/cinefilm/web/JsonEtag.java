package gregochr.cinefilm.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a browse GET with its JSON, a weak ETag (the first 16 hex characters of the SHA-256 of the exact bytes
 * sent, {@code W/"…"}; weak because Tomcat does not compress a response that carries a strong one) and
 * {@code Cache-Control: no-cache, private}: the browser keeps a copy and asks before every use, and an unchanged
 * answer is a 304 with no body. Setting {@code Cache-Control} here stops Spring Security adding its {@code no-store}
 * default to these responses; errors never pass through here and keep it.
 */
@Component
public class JsonEtag {

    private static final int ETAG_HEX_CHARS = 16;

    private final JsonMapper mapper;

    /** Creates the helper over the application's JSON mapper. */
    public JsonEtag(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** Serialises {@code body} and answers 200 with it, or 304 when the request's {@code If-None-Match} names it. */
    public ResponseEntity<byte[]> respond(HttpServletRequest request, Object body) {
        byte[] bytes = mapper.writeValueAsBytes(body);
        return respond(request, bytes, etag(bytes));
    }

    /** Answers 304 when an {@code If-None-Match} value names {@code etag}, else 200 with the bytes. */
    public static ResponseEntity<byte[]> respond(HttpServletRequest request, byte[] body, String etag) {
        boolean unchanged = Collections.list(request.getHeaders(HttpHeaders.IF_NONE_MATCH)).stream()
            .anyMatch(header -> matches(header, etag));
        ResponseEntity.BodyBuilder response = ResponseEntity
            .status(unchanged ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
            .header(HttpHeaders.ETAG, etag)
            .cacheControl(CacheControl.noCache().cachePrivate());
        if (unchanged) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** The weak ETag for these bytes: the first 16 hex characters of their SHA-256, quoted, with a {@code W/}. */
    public static String etag(byte[] body) {
        return etag("SHA-256", body);
    }

    static String etag(String algorithm, byte[] body) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(body));
            return "W/\"" + hex.substring(0, ETAG_HEX_CHARS) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    /** Whether an {@code If-None-Match} value (a comma list, {@code *}, quoted or not, weak or strong) names it. */
    static boolean matches(String header, String etag) {
        String wanted = opaque(etag);
        for (String candidate : header.split(",")) {
            String value = candidate.strip();
            if (value.equals("*") || opaque(value).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String opaque(String tag) {
        String value = tag.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
