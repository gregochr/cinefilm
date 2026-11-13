package gregochr.cinefilm.credits;

import gregochr.cinefilm.library.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * A stand-in for ffmpeg: records every command and its timeout, and writes the PGM files the command asks for, the
 * picture of each coming from a function of the offset it was asked to take.
 */
final class FakeFfmpeg implements CommandRunner {

    final List<List<String>> commands = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    /** The directory the last command wrote into. */
    Path lastDirectory;
    /** The picture at an offset in milliseconds. */
    LongFunction<GreyFrame> frameAt = ms -> SyntheticFrames.picture();
    /** The 1-based call that fails (0 for none). */
    int failOnCall;
    /** What the failing call does: throw this... */
    IOException ioFailure;
    /** ...or return this. */
    Result failure = new Result(1, "", false);
    /** When set, the files are not written. */
    boolean writeNothing;
    /** When set, this is written instead of a picture. */
    byte[] garbage;
    /** How many dense frames to write; -1 for as many as were asked for. */
    int denseCount = -1;

    /** The bytes of a binary PGM, as ffmpeg writes one. */
    static byte[] pgm(GreyFrame frame) {
        byte[] header = ("P5\n" + frame.width() + " " + frame.height() + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[header.length + frame.width() * frame.height()];
        System.arraycopy(header, 0, data, 0, header.length);
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                data[header.length + y * frame.width() + x] = (byte) frame.luma(x, y);
            }
        }
        return data;
    }

    @Override
    public Result run(List<String> command, Duration timeout) throws IOException {
        commands.add(command);
        timeouts.add(timeout);
        if (commands.size() == failOnCall) {
            if (ioFailure != null) {
                throw ioFailure;
            }
            return failure;
        }
        Path output = Path.of(command.getLast());
        lastDirectory = output.getParent();
        if (writeNothing) {
            return new Result(0, "", false);
        }
        long seekMs = Math.round(Double.parseDouble(command.get(command.indexOf("-ss") + 1)) * 1000);
        if (command.getLast().contains("%05d")) {
            int asked = Integer.parseInt(command.get(command.indexOf("-frames:v") + 1));
            int count = denseCount < 0 ? asked : denseCount;
            for (int i = 0; i < count; i++) {
                Path frame = output.getParent().resolve("d_%05d.pgm".formatted(i));
                Files.write(frame, pgm(frameAt.apply(seekMs + 500L * i)));
            }
        } else {
            Files.write(output, garbage != null ? garbage : pgm(frameAt.apply(seekMs)));
        }
        return new Result(0, "", false);
    }
}
