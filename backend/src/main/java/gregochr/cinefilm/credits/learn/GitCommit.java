package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * The commit this build was made from, for {@code complete.json}. It reads {@code git.properties} from the classpath:
 * the Maven plugin writes {@code git.commit.id}, the Docker build writes {@code git.commit.id.full}.
 */
@Component
public class GitCommit {

    /** What is reported when the build has no git information. */
    public static final String UNKNOWN = "unknown";

    /**
     * The commit of this build.
     *
     * @return the full commit id, else the commit id, else {@value #UNKNOWN}
     */
    public String commit() {
        return read(GitCommit.class.getResourceAsStream("/git.properties"));
    }

    /**
     * The commit named by a properties file. The stream is closed.
     *
     * @param in the file, or null when there is none
     * @return {@code git.commit.id.full}, else {@code git.commit.id}, else {@value #UNKNOWN} (also when there is no
     *     file or it cannot be read)
     */
    static String read(InputStream in) {
        if (in == null) {
            return UNKNOWN;
        }
        Properties properties = new Properties();
        try (in) {
            properties.load(in);
        } catch (IOException e) {
            return UNKNOWN;
        }
        String commit = properties.getProperty("git.commit.id.full", properties.getProperty("git.commit.id"));
        return commit == null || commit.isBlank() ? UNKNOWN : commit;
    }
}
