package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything {@code CreditsFeaturesJob} opens inside a film's directory, in one place, so that a test can record which
 * directories were asked for. Nothing else of the job touches a film directory. {@code complete.json} comes as bytes,
 * which the job parses itself so that the record it uses and the bytes it hashes are the same.
 */
interface FilmSource {

    /**
     * The raw bytes of the directory's {@code complete.json}.
     *
     * @param dir the film's directory
     * @return the bytes
     * @throws IOException when the file cannot be read
     */
    byte[] completeBytes(Path dir) throws IOException;

    /**
     * The raw bytes of the labels file.
     *
     * @param file the labels file
     * @return the bytes
     * @throws IOException when the file cannot be read
     */
    byte[] labelsBytes(Path file) throws IOException;

    /**
     * Checks the directory ({@link FilmCheck#check}).
     *
     * @param dir the film's directory
     * @param sink receives each sound frame
     * @return what was found
     * @throws IOException when the directory cannot be listed
     */
    FilmCheck.Result check(Path dir, FilmCheck.Sink sink) throws IOException;

    /**
     * One stored grey frame.
     *
     * @param dir the film's directory
     * @param kind {@code coarse} or {@code dense}
     * @param offsetMs the frame's offset
     * @return the full 240 px grey frame
     * @throws IOException when the file cannot be read
     */
    GreyFrame grey(Path dir, String kind, long offsetMs) throws IOException;

    /**
     * The source that reads the disk.
     *
     * @return the source
     */
    static FilmSource disk() {
        return new FilmSource() {
            @Override
            public byte[] completeBytes(Path dir) throws IOException {
                return Files.readAllBytes(dir.resolve(DatasetComplete.FILE_NAME));
            }

            @Override
            public byte[] labelsBytes(Path file) throws IOException {
                return Files.readAllBytes(file);
            }

            @Override
            public FilmCheck.Result check(Path dir, FilmCheck.Sink sink) throws IOException {
                return FilmCheck.check(dir, sink);
            }

            @Override
            public GreyFrame grey(Path dir, String kind, long offsetMs) throws IOException {
                return FramePrep.decodePng(Files.readAllBytes(dir.resolve(kind).resolve(offsetMs + ".png")));
            }
        };
    }
}
