package gregochr.cinefilm.plex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * The artwork store: posters and backdrops fetched from Plex, kept in one flat folder under a name that is the
 * SHA-256 of the bytes plus {@code .jpg} or {@code .png}. The name is a content hash, so a re-import dedupes, and it
 * is validated on every read, so no request can name a path outside the folder. This is a persistent data mount, not a
 * cache: once Plex is gone these files cannot be fetched again.
 */
@Component
public class ArtworkStore {

    /** The URL prefix under which stored artwork is served; film rows keep {@code /api/art/<name>}. */
    public static final String URL_PREFIX = "/api/art/";

    private static final Pattern NAME = Pattern.compile("[0-9a-f]{64}\\.(jpg|png)");

    private final Path dir;

    /** Creates the store over {@code cinefilm.artwork.dir}. */
    public ArtworkStore(@Value("${cinefilm.artwork.dir}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * Stores an image and returns the path to keep on the film.
     *
     * @param bytes the image
     * @param contentType the response content type; only {@code image/jpeg} and {@code image/png} are accepted
     * @return {@code /api/art/<sha256>.<ext>}
     * @throws IllegalArgumentException when the content type is not on the allow-list
     * @throws UncheckedIOException when the file cannot be written
     */
    public String store(byte[] bytes, String contentType) {
        String extension = extension(contentType);
        String name = hash(bytes) + "." + extension;
        Path target = dir.resolve(name);
        if (!Files.isRegularFile(target)) {
            write(bytes, target);
        }
        return URL_PREFIX + name;
    }

    /** True when the film's poster or backdrop path is a stored artwork file that is really there. */
    public boolean exists(String artPath) {
        return artPath != null && artPath.startsWith(URL_PREFIX) && file(artPath.substring(URL_PREFIX.length()))
            .isPresent();
    }

    /**
     * The file for a requested name.
     *
     * @param name the last segment of the request path
     * @return the file, or empty when the name is not exactly a 64-character lower-case hex hash with a {@code .jpg}
     *     or {@code .png} extension, or no such file is stored
     */
    public Optional<Path> file(String name) {
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        Path file = dir.resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** The content type to serve a stored name with, from its extension. */
    public static MediaType mediaType(String name) {
        return name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
    }

    private static String extension(String contentType) {
        String type = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> throw new IllegalArgumentException("Not an accepted image type");
        };
    }

    private static String hash(byte[] bytes) {
        return hash(bytes, "SHA-256");
    }

    static String hash(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm unavailable: " + algorithm, e);
        }
    }

    /** Writes to a temporary file in the same folder, then moves it into place, so a reader never sees half a file. */
    private void write(byte[] bytes, Path target) {
        try {
            Files.createDirectories(dir);
            Path temporary = Files.createTempFile(dir, "art-", ".tmp");
            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the artwork", e);
        }
    }
}
