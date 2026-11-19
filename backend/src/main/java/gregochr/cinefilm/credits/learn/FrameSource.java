package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything {@code CreditsEmbedJob} opens inside a film's directory, in one place, so that a test can record that no
 * JPEG was opened before every check passed. A check that a frame exists does not open it.
 */
interface FrameSource {

    /**
     * The raw bytes of the directory's {@code complete.json}.
     *
     * @param dir the film's directory
     * @return the bytes
     * @throws IOException when the file cannot be read
     */
    byte[] completeBytes(Path dir) throws IOException;

    /**
     * Whether a stored colour frame is a regular file. The file is not opened.
     *
     * @param dir the film's directory
     * @param kind {@code coarse} or {@code dense}
     * @param offsetMs the frame's offset
     * @return true when {@code <kind>/<offsetMs>.jpg} is a regular file
     */
    boolean hasJpeg(Path dir, String kind, long offsetMs);

    /**
     * One stored colour frame's bytes.
     *
     * @param dir the film's directory
     * @param kind {@code coarse} or {@code dense}
     * @param offsetMs the frame's offset
     * @return the JPEG
     * @throws IOException when the file cannot be read
     */
    byte[] jpeg(Path dir, String kind, long offsetMs) throws IOException;

    /**
     * The source that reads the disk.
     *
     * @return the source
     */
    static FrameSource disk() {
        return new FrameSource() {
            @Override
            public byte[] completeBytes(Path dir) throws IOException {
                return Files.readAllBytes(dir.resolve(DatasetComplete.FILE_NAME));
            }

            @Override
            public boolean hasJpeg(Path dir, String kind, long offsetMs) {
                return Files.isRegularFile(dir.resolve(kind).resolve(offsetMs + ".jpg"));
            }

            @Override
            public byte[] jpeg(Path dir, String kind, long offsetMs) throws IOException {
                return Files.readAllBytes(dir.resolve(kind).resolve(offsetMs + ".jpg"));
            }
        };
    }
}
