package gregochr.cinefilm.transcode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** {@link FileStamps} over {@code java.nio.file.Files}. */
@Component
public class NioFileStamps implements FileStamps {

    @Override
    public Stamp read(Path file) throws IOException {
        return new Stamp(Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }
}
