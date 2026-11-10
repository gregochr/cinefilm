package gregochr.cinefilm.transcode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link SegmentFiles} over {@code java.nio.file}. Failures are logged, never thrown: cleanup is best effort. */
@Component
class NioSegmentFiles implements SegmentFiles {

    private static final Logger LOG = LoggerFactory.getLogger(NioSegmentFiles.class);

    @Override
    public boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    @Override
    public void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Cannot delete {}: {}", file, e.getMessage());
        }
    }

    @Override
    public void deleteRange(Path rungDir, int firstIndex, int lastIndex) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rungDir, "seg-*.ts")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String number = name.substring("seg-".length(), name.length() - ".ts".length());
                if (number.matches("\\d{1,9}") && Integer.parseInt(number) >= firstIndex
                    && Integer.parseInt(number) <= lastIndex) {
                    delete(file);
                }
            }
        } catch (NoSuchFileException e) {
            LOG.debug("Nothing to clean in {}", rungDir);
        } catch (IOException e) {
            LOG.warn("Cannot clean {}: {}", rungDir, e.getMessage());
        }
    }

    @Override
    public void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                    delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException e) {
            LOG.debug("Nothing to delete at {}", dir);
        } catch (IOException e) {
            LOG.warn("Cannot delete {}: {}", dir, e.getMessage());
        }
    }

    @Override
    public List<Path> directories(Path dir) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            stream.forEach(found::add);
        } catch (NoSuchFileException e) {
            LOG.debug("No folder {}", dir);
        } catch (IOException e) {
            LOG.warn("Cannot list {}: {}", dir, e.getMessage());
        }
        return found;
    }

    @Override
    public long size(Path dir) {
        long[] total = {0};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException e) {
            LOG.debug("No folder {}", dir);
        } catch (IOException e) {
            LOG.warn("Cannot measure {}: {}", dir, e.getMessage());
        }
        return total[0];
    }
}
