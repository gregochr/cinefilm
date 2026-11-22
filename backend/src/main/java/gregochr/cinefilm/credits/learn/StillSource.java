package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.nio.file.Path;
import java.util.Optional;

/** Where the sheet tool gets its stills: one open file at a time, one frame per file time. */
interface StillSource {

    /**
     * Opens a file for reading.
     *
     * @param file the media file
     * @return the open file; close it when done
     * @throws gregochr.cinefilm.credits.CreditsFrameReader.ReadException when it cannot be opened
     */
    Reading open(Path file);

    /** One open file. */
    interface Reading extends AutoCloseable {

        /**
         * The frame at a time in the file.
         *
         * @param fileMs where in the file, 0 or more
         * @return the frame, or empty when the file has none there (past its end)
         * @throws gregochr.cinefilm.credits.CreditsFrameReader.ReadException when the read fails
         */
        Optional<GreyFrame> frameAt(long fileMs);

        /**
         * The frame at a time in the file at the file's own resolution, as PNG bytes.
         *
         * @param fileMs where in the file, 0 or more
         * @return the PNG, or empty when the file has none there (past its end)
         * @throws gregochr.cinefilm.credits.CreditsFrameReader.ReadException when the read fails
         */
        Optional<byte[]> fullPngAt(long fileMs);

        @Override
        void close();
    }
}
