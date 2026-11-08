package gregochr.cinefilm.library;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Walks the library folder for film files. Read-only: nothing here writes to the media mount. */
@Component
public class LibraryScanner {

    private static final Set<String> PLAYABLE = Set.of("m4v", "mp4", "mov");
    private static final Set<String> NEEDS_CONVERSION = Set.of("avi", "mkv");

    private final LibraryRoot root;

    /** Creates the scanner over the library root. */
    public LibraryScanner(LibraryRoot root) {
        this.root = root;
    }

    /**
     * A film file found on disk.
     *
     * @param path path relative to the library root, with forward slashes
     * @param sizeBytes file size
     * @param modifiedAt last-modified time
     * @param createdAt creation time, or the modified time when the file system has none
     * @param needsConversion true for {@code .avi} and {@code .mkv}, which Safari cannot direct-play
     */
    public record FoundFile(String path, long sizeBytes, Instant modifiedAt, Instant createdAt,
                            boolean needsConversion) {

        /** The file name without its folders. */
        public String fileName() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /**
     * The result of a walk.
     *
     * @param files every candidate film file found
     * @param rejected candidate files refused because they resolve outside the library root
     * @param unreadable folders (or files) under the root that could not be read, relative to it; what is below
     *     them is unknown, not gone
     */
    public record Found(List<FoundFile> files, int rejected, List<String> unreadable) {

        /** A walk that read everything it tried to. */
        public Found(List<FoundFile> files, int rejected) {
            this(files, rejected, List.of());
        }

        /** True when the path is, or is inside, a folder or file the walk could not read. */
        public boolean isUnder(String path) {
            return unreadable.stream().anyMatch(u -> path.equals(u) || path.startsWith(u + "/"));
        }
    }

    /**
     * Walks the library recursively for {@code .m4v}, {@code .mp4}, {@code .mov} (playable) and {@code .avi},
     * {@code .mkv} (needs conversion), skipping hidden files and folders and AppleDouble {@code ._*} files. There is
     * no size filter.
     *
     * @return the files found; an unreadable or missing root yields an {@link UncheckedIOException}
     */
    public Found scan() {
        List<FoundFile> files = new ArrayList<>();
        int[] rejected = {0};
        List<String> unreadable = new ArrayList<>();
        try {
            Files.walkFileTree(root.root(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    boolean hidden = !dir.equals(root.root()) && dir.getFileName().toString().startsWith(".");
                    return hidden ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (name.startsWith(".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String extension = extension(name);
                    if (!PLAYABLE.contains(extension) && !NEEDS_CONVERSION.contains(extension)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = root.relativize(file);
                    Optional<Path> real = root.resolveFile(relative);
                    if (real.isEmpty()) {
                        rejected[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    BasicFileAttributes actual = Files.readAttributes(real.get(), BasicFileAttributes.class);
                    Instant modified = actual.lastModifiedTime().toInstant();
                    files.add(new FoundFile(relative, actual.size(), modified,
                        created(actual.creationTime().toInstant(), modified), NEEDS_CONVERSION.contains(extension)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    if (file.equals(root.root())) {
                        throw exc;
                    }
                    unreadable.add(root.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the library folder", e);
        }
        files.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        return new Found(List.copyOf(files), rejected[0], List.copyOf(unreadable));
    }

    /** When the file was created; file systems with no creation time report the epoch, so use the modified time. */
    static Instant created(Instant creationTime, Instant modifiedTime) {
        return creationTime.toEpochMilli() > 0 ? creationTime : modifiedTime;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
