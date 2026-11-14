package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The checks of one film's directory in the dataset, for the measurement tool. It only reads: nothing is written and
 * nothing outside the directory is opened.
 *
 * <p>A directory without a usable format-1 {@code complete.json} is {@link Status#INCOMPLETE}, and nothing else is
 * read from it. One whose {@code mediaFileId} differs from its directory name is {@link Status#MISMATCHED}. Otherwise,
 * for every offset in {@code coarse} and {@code dense}, both files must exist and decode, the grey frame as half the
 * frame width wide and the colour frame as {@code colourSize} square: a listed file that is absent counts as
 * {@code missing}, one that cannot be read, does not decode or has the wrong size as {@code corrupt} (whatever the
 * decoder throws), an offset listed twice in one list as a {@code duplicate} (checked and measured once, and the film
 * is {@code damaged}), and a file in {@code coarse/} or
 * {@code dense/} that is not listed as {@code extra}. Each frame whose two files are sound is handed to the sink, one
 * at a time, so no more than one frame pair is held.
 */
final class FilmCheck {

    /** What the check found out about a directory. */
    enum Status {
        /** Every listed file is there and sound. */
        OK,
        /** No usable {@code complete.json}. */
        INCOMPLETE,
        /** The {@code complete.json} belongs to another media file than the directory's name. */
        MISMATCHED,
        /** A listed file is missing or corrupt. */
        DAMAGED
    }

    /** The folder of the coarse frames. */
    static final String COARSE = "coarse";
    /** The folder of the dense frames. */
    static final String DENSE = "dense";

    private FilmCheck() {
    }

    /**
     * A frame whose two files are sound.
     *
     * @param kind {@value #COARSE} or {@value #DENSE}
     * @param offsetMs the frame's offset
     * @param grey the grey frame
     * @param colour the colour frame
     * @param pngBytes the size of the grey file
     * @param jpgBytes the size of the colour file
     */
    record Measured(String kind, long offsetMs, GreyFrame grey, RgbFrame colour, long pngBytes, long jpgBytes) {
    }

    /** Receives each sound frame. */
    @FunctionalInterface
    interface Sink {
        /**
         * Takes a frame.
         *
         * @param frame the frame
         */
        void accept(Measured frame);
    }

    /**
     * What the check found.
     *
     * @param status the verdict
     * @param complete the {@code complete.json}; null for {@code INCOMPLETE} and {@code MISMATCHED}
     * @param missing how many listed files are absent
     * @param corrupt how many listed files cannot be read, do not decode or have the wrong size
     * @param extra how many files in {@code coarse/} and {@code dense/} are not listed
     * @param duplicates how many listings repeat an offset already listed in the same list (each offset is checked
     *     and measured once)
     * @param pngBytes the size of the listed grey files that exist
     * @param jpgBytes the size of the listed colour files that exist
     * @param totalBytes the size of every file in the directory, {@code complete.json} included
     * @param earliestModified the earliest modification time of a listed file that exists; null when none does
     */
    record Result(Status status, DatasetComplete complete, int missing, int corrupt, int extra, int duplicates,
                  long pngBytes, long jpgBytes, long totalBytes, Instant earliestModified) {

        /** A result for a directory nothing was read from. */
        static Result unread(Status status) {
            return new Result(status, null, 0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    /** The running totals of one check. */
    private static final class Tally {
        private int missing;
        private int corrupt;
        private int extra;
        private int duplicates;
        private long pngBytes;
        private long jpgBytes;
        private Instant earliest;

        private void seen(Path file) throws IOException {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            if (earliest == null || modified.isBefore(earliest)) {
                earliest = modified;
            }
        }
    }

    /**
     * Checks a film's directory.
     *
     * @param dir the film's directory, named for its media file id
     * @param sink receives each frame whose two files are sound
     * @return what was found
     * @throws IOException when the directory cannot be listed
     */
    static Result check(Path dir, Sink sink) throws IOException {
        return check(dir, sink, FramePrep::decodePng, FramePrep::decodeJpeg);
    }

    /** As {@link #check(Path, Sink)}, with the decoders given so that a test can make one fail. */
    static Result check(Path dir, Sink sink, Function<byte[], GreyFrame> greyDecoder,
                        Function<byte[], RgbFrame> colourDecoder) throws IOException {
        Optional<DatasetComplete> read = DatasetComplete.read(dir);
        if (read.isEmpty()) {
            return Result.unread(Status.INCOMPLETE);
        }
        DatasetComplete complete = read.get();
        if (!dir.getFileName().toString().equals(Long.toString(complete.mediaFileId()))) {
            return Result.unread(Status.MISMATCHED);
        }
        Tally tally = new Tally();
        Decoders decoders = new Decoders(greyDecoder, colourDecoder);
        scan(dir.resolve(COARSE), COARSE, complete.coarse(), complete, decoders, sink, tally);
        scan(dir.resolve(DENSE), DENSE, complete.dense(), complete, decoders, sink, tally);
        long total = 0;
        try (Stream<Path> tree = Files.walk(dir)) {
            for (Path path : tree.filter(Files::isRegularFile).toList()) {
                total += Files.size(path);
            }
        }
        Status status = tally.missing > 0 || tally.corrupt > 0 || tally.duplicates > 0 ? Status.DAMAGED : Status.OK;
        return new Result(status, complete, tally.missing, tally.corrupt, tally.extra, tally.duplicates,
            tally.pngBytes, tally.jpgBytes, total, tally.earliest);
    }

    /** The two decoders of a check. */
    private record Decoders(Function<byte[], GreyFrame> grey, Function<byte[], RgbFrame> colour) {
    }

    private static void scan(Path folder, String kind, List<Long> offsets, DatasetComplete complete,
                             Decoders decoders, Sink sink, Tally tally) throws IOException {
        Set<String> listed = new HashSet<>();
        for (long offset : offsets) {
            if (!listed.add(offset + ".png")) {
                tally.duplicates++;
                continue;
            }
            listed.add(offset + ".jpg");
            Path png = folder.resolve(offset + ".png");
            Path jpg = folder.resolve(offset + ".jpg");
            byte[] pngData = load(png, tally);
            byte[] jpgData = load(jpg, tally);
            GreyFrame grey = pngData == null ? null : decodeGrey(pngData, complete, decoders, tally);
            RgbFrame colour = jpgData == null ? null : decodeColour(jpgData, complete, decoders, tally);
            if (pngData != null) {
                tally.pngBytes += pngData.length;
            }
            if (jpgData != null) {
                tally.jpgBytes += jpgData.length;
            }
            if (grey != null && colour != null) {
                sink.accept(new Measured(kind, offset, grey, colour, pngData.length, jpgData.length));
            }
        }
        if (Files.isDirectory(folder)) {
            try (Stream<Path> entries = Files.list(folder)) {
                tally.extra += (int) entries.filter(entry -> !listed.contains(entry.getFileName().toString())).count();
            }
        }
    }

    /**
     * The file's bytes, or null when it cannot be had: counted as missing when it is not a regular file, as corrupt
     * when it exists but cannot be read (no permission, or gone since the check began).
     */
    private static byte[] load(Path file, Tally tally) {
        if (!Files.isRegularFile(file)) {
            tally.missing++;
            return null;
        }
        try {
            tally.seen(file);
            return Files.readAllBytes(file);
        } catch (IOException e) {
            tally.corrupt++;
            return null;
        }
    }

    private static GreyFrame decodeGrey(byte[] data, DatasetComplete complete, Decoders decoders, Tally tally) {
        try {
            GreyFrame frame = decoders.grey().apply(data);
            if (frame.width() == complete.frame().width() / 2) {
                return frame;
            }
        } catch (RuntimeException e) {
            // Falls through to the count below: not a readable grey picture.
        }
        tally.corrupt++;
        return null;
    }

    private static RgbFrame decodeColour(byte[] data, DatasetComplete complete, Decoders decoders, Tally tally) {
        try {
            RgbFrame frame = decoders.colour().apply(data);
            int size = complete.settings().colourSize();
            if (frame.width() == size && frame.height() == size) {
                return frame;
            }
        } catch (RuntimeException e) {
            // Falls through to the count below: not a readable colour picture.
        }
        tally.corrupt++;
        return null;
    }
}
